"""Bulk-seed the similarity engine's vector store from data already in Postgres.

The teleport worker populates ``work_embeddings`` / ``author_embeddings`` going
forward (researcher_worker._pg_upsert_embeddings). This script fills the gap for
authors that were teleported before that code existed, using the rows already in
``researcher_metrics`` + ``researcher_works`` — no OpenAlex calls.

    cd services/backend
    python scripts/backfill_embeddings.py --limit 200        # seed 200 authors
    python scripts/backfill_embeddings.py --dry-run          # count, write nothing

Idempotent: ON CONFLICT upserts. Safe to re-run. Requires a Postgres
``DATABASE_URL`` with the pgvector tables present (``alembic upgrade head`` or
``init_db``); exits 1 on SQLite.
"""

from __future__ import annotations

import argparse
import asyncio
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import numpy as np  # noqa: E402
from sqlalchemy import select, text  # noqa: E402

from app.db.database import AsyncSessionLocal, engine  # noqa: E402
from app.models.researcher_models import ResearcherMetrics, ResearcherWork  # noqa: E402
from app.services.ai.embedding_service import embed_texts  # noqa: E402
from app.services.data.researcher_worker import _vec_literal  # noqa: E402

_WORK_SQL = text(
    """
    INSERT INTO work_embeddings
        (work_id, embedding, title, concepts, referenced_works, publication_year, updated_at)
    VALUES
        (:work_id, CAST(:embedding AS vector), CAST(:title AS text),
         CAST(:concepts AS text[]), CAST(:referenced_works AS text[]),
         CAST(:year AS integer), now())
    ON CONFLICT (work_id) DO UPDATE SET
        embedding = EXCLUDED.embedding, title = EXCLUDED.title,
        concepts = EXCLUDED.concepts, publication_year = EXCLUDED.publication_year,
        updated_at = now()
    """
)
_AUTHOR_SQL = text(
    """
    INSERT INTO author_embeddings
        (author_id, embedding, concepts, coauthor_ids, institution, works_count, h_index, updated_at)
    VALUES
        (:author_id, CAST(:embedding AS vector), CAST(:concepts AS text[]),
         CAST(:coauthor_ids AS text[]), CAST(:institution AS text),
         CAST(:works_count AS integer), CAST(:h_index AS integer), now())
    ON CONFLICT (author_id) DO UPDATE SET
        embedding = EXCLUDED.embedding, concepts = EXCLUDED.concepts,
        institution = EXCLUDED.institution, works_count = EXCLUDED.works_count,
        h_index = EXCLUDED.h_index, updated_at = now()
    """
)


async def _run(limit: int, dry_run: bool) -> int:
    if engine.dialect.name != "postgresql":
        print("DATABASE_URL is not Postgres — the pgvector store is unavailable.")
        return 1

    seeded_authors = 0
    seeded_works = 0
    async with AsyncSessionLocal() as session:
        metrics = (
            (await session.execute(select(ResearcherMetrics).limit(limit)))
            .scalars()
            .all()
        )
        print(
            f"{len(metrics)} researcher_metrics row(s) to seed"
            + (" (dry run)" if dry_run else "")
        )

        for m in metrics:
            works = (
                (
                    await session.execute(
                        select(ResearcherWork)
                        .where(ResearcherWork.author_openalex_id == m.openalex_id)
                        .limit(25)
                    )
                )
                .scalars()
                .all()
            )
            pairs = [
                (w.work_openalex_id, f"{w.title or ''} {w.abstract or ''}".strip(), w)
                for w in works
                if (w.title or w.abstract)
            ]
            if not pairs:
                continue
            if dry_run:
                seeded_authors += 1
                seeded_works += len(pairs)
                continue

            vectors = np.asarray(
                await embed_texts([p[1] for p in pairs]), dtype=np.float32
            )
            if vectors.ndim != 2 or vectors.shape[0] != len(pairs):
                continue

            for (wid, _blob, w), vec in zip(pairs, vectors):
                await session.execute(
                    _WORK_SQL,
                    {
                        "work_id": wid,
                        "embedding": _vec_literal(vec),
                        "title": (w.title or "")[:2000],
                        "concepts": [c for c in (w.concepts or []) if c][:25],
                        "referenced_works": [],
                        "year": w.publication_year,
                    },
                )
                seeded_works += 1

            mean_vec = vectors.mean(axis=0)
            norm = float(np.linalg.norm(mean_vec))
            if norm > 1e-9:
                mean_vec = mean_vec / norm
            await session.execute(
                _AUTHOR_SQL,
                {
                    "author_id": m.openalex_id,
                    "embedding": _vec_literal(mean_vec),
                    "concepts": (
                        ([m.field_of_study] if m.field_of_study else [])
                        + [e for e in (m.expertise or []) if e][:24]
                    ),
                    "coauthor_ids": [],
                    "institution": m.current_institution,
                    "works_count": int(m.works_count or 0),
                    "h_index": int(m.h_index or 0),
                },
            )
            seeded_authors += 1
            await session.commit()

    print(
        f"{'would seed' if dry_run else 'seeded'} "
        f"{seeded_authors} author(s), {seeded_works} work(s)."
    )
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--limit", type=int, default=200, help="max authors to seed")
    ap.add_argument(
        "--dry-run", action="store_true", help="write nothing, print counts"
    )
    args = ap.parse_args()
    return asyncio.run(_run(args.limit, args.dry_run))


if __name__ == "__main__":
    raise SystemExit(main())
