"""Phase A of the similarity engine — the Python vector-store writers.

Postgres-only: the pgvector tables (work_embeddings / author_embeddings) do
not exist on the SQLite offline fallback, so the whole module skips there.
"""

from __future__ import annotations

import importlib.util
from pathlib import Path

import numpy as np
import pytest
from sqlalchemy import text

from app.db.database import AsyncSessionLocal, engine

pytestmark = pytest.mark.skipif(
    engine.dialect.name != "postgresql",
    reason="pgvector similarity tables exist only on Postgres",
)

_DIM = 384


def _fake_embed_texts():
    async def _embed(texts):
        rows = []
        for t in texts:
            rng = np.random.default_rng(abs(hash(t)) % (2**32))
            v = rng.standard_normal(_DIM).astype(np.float32)
            v /= np.linalg.norm(v) or 1.0
            rows.append(v)
        return np.stack(rows) if rows else np.zeros((0, _DIM), dtype=np.float32)

    return _embed


async def _cleanup(work_ids: list[str], author_ids: list[str]) -> None:
    async with AsyncSessionLocal() as s:
        if work_ids:
            await s.execute(
                text("DELETE FROM work_embeddings WHERE work_id = ANY(:ids)"),
                {"ids": work_ids},
            )
        if author_ids:
            await s.execute(
                text("DELETE FROM author_embeddings WHERE author_id = ANY(:ids)"),
                {"ids": author_ids},
            )
        await s.commit()


async def test_pg_upsert_embeddings_writes_work_and_author_rows(monkeypatch):
    import app.services.data.researcher_worker as rw

    monkeypatch.setattr(
        "app.services.ai.embedding_service.embed_texts", _fake_embed_texts()
    )

    # All values are synthetic fixtures — the engine reads every real topic /
    # institution / name from OpenAlex or Postgres at runtime, never a literal.
    works = [
        {
            "id": "https://openalex.org/W_sim_1",
            "title": "Synthetic work one",
            "abstract": "Fixture abstract one.",
            "concepts": ["Field Alpha", "Subtopic One"],
            "year": 2023,
        },
        {
            "id": "https://openalex.org/W_sim_2",
            "title": "Synthetic work two",
            "abstract": "Fixture abstract two.",
            "concepts": ["Field Alpha"],
            "year": 2024,
        },
    ]
    raw_works = [
        {
            "id": "https://openalex.org/W_sim_1",
            "referenced_works": ["https://openalex.org/W_ref_9"],
            "authorships": [
                {"author": {"id": "https://openalex.org/A_sim_self"}},
                {"author": {"id": "https://openalex.org/A_sim_co1"}},
            ],
        },
        {
            "id": "https://openalex.org/W_sim_2",
            "referenced_works": [],
            "authorships": [{"author": {"id": "https://openalex.org/A_sim_self"}}],
        },
    ]

    try:
        await rw._pg_upsert_embeddings(
            "A_sim_self",
            works,
            raw_works,
            {"id": "A_sim_self"},
            "Field Alpha",
            ["Subfield Beta"],
            "Institution X",
            12,
            40,
        )

        async with AsyncSessionLocal() as s:
            n_works = (
                await s.execute(
                    text(
                        "SELECT count(*) FROM work_embeddings "
                        "WHERE work_id IN ('W_sim_1', 'W_sim_2')"
                    )
                )
            ).scalar()
            arow = (
                await s.execute(
                    text(
                        "SELECT coauthor_ids, institution, h_index, concepts "
                        "FROM author_embeddings WHERE author_id = 'A_sim_self'"
                    )
                )
            ).first()

        assert n_works == 2
        assert arow is not None
        assert "A_sim_co1" in (arow[0] or [])
        assert arow[1] == "Institution X"
        assert arow[2] == 12  # h_index arg (works_count=40 is a different column)
        assert "Subfield Beta" in (arow[3] or [])
    finally:
        await _cleanup(["W_sim_1", "W_sim_2"], ["A_sim_self"])


async def test_embed_work_route_upserts_row(client, monkeypatch):
    monkeypatch.setattr(
        "app.services.ai.embedding_service.embed_texts", _fake_embed_texts()
    )

    async def _fake_fetch_work(self, work_id):
        return {
            "id": f"https://openalex.org/{work_id}",
            "title": "Synthetic route work",
            "abstract_inverted_index": {"fixture": [0], "abstract": [1]},
            "concepts": [{"display_name": "Field Alpha"}],
            "referenced_works": [],
            "publication_year": 2022,
        }

    monkeypatch.setattr(
        "app.services.data.openalex_service.OpenAlexService.fetch_work_by_id",
        _fake_fetch_work,
        raising=False,
    )

    try:
        r = await client.post(
            "/api/v1/internal/similar/embed_work", json={"work_id": "W_sim_route"}
        )
        assert r.status_code == 200, r.text
        assert r.json()["ok"] is True

        async with AsyncSessionLocal() as s:
            n = (
                await s.execute(
                    text(
                        "SELECT count(*) FROM work_embeddings "
                        "WHERE work_id = 'W_sim_route'"
                    )
                )
            ).scalar()
        assert n == 1
    finally:
        await _cleanup(["W_sim_route"], [])


async def test_embed_work_route_rejects_bad_internal_token(client, monkeypatch):
    monkeypatch.setenv("INTERNAL_API_TOKEN", "s3cret")
    r = await client.post(
        "/api/v1/internal/similar/embed_work",
        json={"work_id": "W_sim_x"},
        headers={"X-Internal-Token": "wrong"},
    )
    assert r.status_code == 401


async def test_backfill_script_dry_run_writes_nothing():
    path = Path(__file__).resolve().parents[2] / "scripts" / "backfill_embeddings.py"
    spec = importlib.util.spec_from_file_location("backfill_embeddings", path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)

    async with AsyncSessionLocal() as s:
        before = (
            await s.execute(text("SELECT count(*) FROM author_embeddings"))
        ).scalar()

    rc = await mod._run(limit=5, dry_run=True)
    assert rc == 0

    async with AsyncSessionLocal() as s:
        after = (
            await s.execute(text("SELECT count(*) FROM author_embeddings"))
        ).scalar()
    assert before == after
