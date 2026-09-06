"""Internal service-to-service routes — not for public clients.

The only caller is the Go gateway. ``GET /search_author`` and
``GET /refresh_author`` moved to Go (``services/backend-go/internal/author/
search.go``), but the teleport *enrichment* worker
(``app/services/data/researcher_worker.py::teleport_researcher``) is an LLM job
and stays here. The gateway hands it off with a fire-and-forget
``POST /api/v1/internal/teleport/{author_id}``.

Auth: a shared-secret header ``X-Internal-Token`` must equal the
``INTERNAL_API_TOKEN`` env var. When the var is unset the check is skipped, so
local dev works with no configuration. The route is otherwise unauthenticated —
it is never exposed to browsers (the gateway does not proxy ``/internal/*`` from
clients) and it only enqueues a background task.
"""

from __future__ import annotations

import logging
import os

from fastapi import APIRouter, BackgroundTasks, Header, HTTPException
from pydantic import BaseModel

logger = logging.getLogger("skolab")

router = APIRouter()


class TeleportAck(BaseModel):
    """``POST /internal/teleport/{author_id}`` — 202, the task is now queued."""

    status: str
    author_id: str


try:
    from app.services.data.researcher_worker import teleport_researcher
except ImportError:  # pragma: no cover - firebase/worker deps optional in some envs
    teleport_researcher = None


async def _run_teleport(author_id: str) -> None:
    """Background wrapper — never raises into the event loop."""
    if teleport_researcher is None:
        logger.info("[internal] teleport worker unavailable — skipping %s", author_id)
        return
    try:
        await teleport_researcher(author_id)
    except Exception as exc:  # pragma: no cover - worker logs its own detail
        logger.warning("[internal] teleport failed for %s: %s", author_id, exc)


def _check_internal_token(x_internal_token: str | None) -> None:
    expected = os.environ.get("INTERNAL_API_TOKEN", "")
    if expected and x_internal_token != expected:
        raise HTTPException(status_code=401, detail="invalid internal token")


@router.post(
    "/internal/teleport/{author_id}", response_model=TeleportAck, status_code=202
)
async def trigger_teleport(
    author_id: str,
    background_tasks: BackgroundTasks,
    x_internal_token: str | None = Header(default=None),
) -> TeleportAck:
    """Enqueue ``teleport_researcher(author_id)`` as a background task (202)."""
    _check_internal_token(x_internal_token)

    background_tasks.add_task(_run_teleport, author_id)
    return TeleportAck(status="teleport enqueued", author_id=author_id)


class EmbedWorkRequest(BaseModel):
    work_id: str


class EmbedWorkAck(BaseModel):
    ok: bool
    work_id: str


@router.post("/internal/similar/embed_work", response_model=EmbedWorkAck)
async def embed_work(
    body: EmbedWorkRequest,
    x_internal_token: str | None = Header(default=None),
) -> EmbedWorkAck:
    """Fetch one OpenAlex work, embed title+abstract, upsert into
    ``work_embeddings``. The Go gateway's ``/similar_papers`` cold path calls
    this once for a query work it has never seen, then runs its kNN.

    Synchronous (not a background task): the caller waits on the row so its
    immediately-following kNN sees it. Best-effort — any failure returns
    ``ok: false`` and the caller falls back to OpenAlex ``related_works``.
    """
    _check_internal_token(x_internal_token)

    from sqlalchemy import text as _sql

    from app.db.database import AsyncSessionLocal
    from app.services.ai.embedding_service import embed_texts
    from app.services.data.openalex_service import OpenAlexService
    from app.services.data.researcher_worker import _clean_oa_id, _vec_literal

    wid = _clean_oa_id(body.work_id)
    if not wid:
        return EmbedWorkAck(ok=False, work_id=body.work_id)

    try:
        raw = await OpenAlexService().fetch_work_by_id(wid)
    except Exception as exc:  # pragma: no cover - network
        logger.warning(
            "[internal] embed_work OpenAlex fetch failed for %s: %s", wid, exc
        )
        return EmbedWorkAck(ok=False, work_id=wid)
    if not raw:
        return EmbedWorkAck(ok=False, work_id=wid)

    from app.services.data.researcher_worker import _reconstruct_abstract

    title = raw.get("title") or ""
    abstract = _reconstruct_abstract(raw.get("abstract_inverted_index"))
    blob = f"{title} {abstract}".strip()
    if not blob:
        return EmbedWorkAck(ok=False, work_id=wid)

    try:
        vec = (await embed_texts([blob]))[0]
    except Exception as exc:
        logger.warning("[internal] embed_work embed failed for %s: %s", wid, exc)
        return EmbedWorkAck(ok=False, work_id=wid)

    concepts = [
        c.get("display_name")
        for c in (raw.get("concepts") or [])
        if c.get("display_name")
    ][:25]
    refs = [_clean_oa_id(r) for r in (raw.get("referenced_works") or []) if r][:200]

    stmt = _sql(
        """
        INSERT INTO work_embeddings
            (work_id, embedding, title, concepts, referenced_works, publication_year, updated_at)
        VALUES
            (:work_id, CAST(:embedding AS vector), CAST(:title AS text),
             CAST(:concepts AS text[]), CAST(:referenced_works AS text[]),
             CAST(:year AS integer), now())
        ON CONFLICT (work_id) DO UPDATE SET
            embedding = EXCLUDED.embedding,
            title = EXCLUDED.title,
            concepts = EXCLUDED.concepts,
            referenced_works = EXCLUDED.referenced_works,
            publication_year = EXCLUDED.publication_year,
            updated_at = now()
        """
    )
    async with AsyncSessionLocal() as session:
        try:
            await session.execute(
                stmt,
                {
                    "work_id": wid,
                    "embedding": _vec_literal(vec),
                    "title": title[:2000],
                    "concepts": concepts,
                    "referenced_works": refs,
                    "year": raw.get("publication_year"),
                },
            )
            await session.commit()
        except Exception as exc:
            logger.warning("[internal] embed_work upsert failed for %s: %s", wid, exc)
            await session.rollback()
            return EmbedWorkAck(ok=False, work_id=wid)

    return EmbedWorkAck(ok=True, work_id=wid)
