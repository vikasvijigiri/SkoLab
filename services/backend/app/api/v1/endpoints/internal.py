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


@router.post(
    "/internal/teleport/{author_id}", response_model=TeleportAck, status_code=202
)
async def trigger_teleport(
    author_id: str,
    background_tasks: BackgroundTasks,
    x_internal_token: str | None = Header(default=None),
) -> TeleportAck:
    """Enqueue ``teleport_researcher(author_id)`` as a background task (202)."""
    expected = os.environ.get("INTERNAL_API_TOKEN", "")
    if expected and x_internal_token != expected:
        raise HTTPException(status_code=401, detail="invalid internal token")

    background_tasks.add_task(_run_teleport, author_id)
    return TeleportAck(status="teleport enqueued", author_id=author_id)
