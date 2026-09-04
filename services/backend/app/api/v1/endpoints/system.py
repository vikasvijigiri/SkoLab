import logging
import json
from pathlib import Path
from fastapi import APIRouter
from app.services.ai.summarization_service import is_llm_working
from app.schemas.system import (
    AiStatusResponse,
    RootResponse,
    SystemStatusResponse,
)

logger = logging.getLogger(__name__)

router = APIRouter()


def _find_repo_root() -> Path:
    current = Path(__file__).resolve()
    for parent in current.parents:
        if (parent / ".git").exists() or (parent / "docs" / "incidents.json").exists():
            return parent
    # Fallback to backend root
    for parent in current.parents:
        if parent.name == "backend":
            return parent
    return current.parents[min(len(current.parents) - 1, 3)]


_REPO_ROOT = _find_repo_root()
_INCIDENTS_PATH = _REPO_ROOT / "docs" / "incidents.json"


@router.get("/", response_model=RootResponse)
def read_root():
    return {"message": "Welcome to the SkoLab API!"}


@router.get("/ai_status", response_model=AiStatusResponse)
async def ai_status():
    """Checks if the AI services have valid API keys and are reachable."""
    import os

    groq_key = os.getenv("GROQ_API")
    has_key = groq_key is not None and len(groq_key) > 10
    llm_ok = is_llm_working()
    return {
        "groq_api_configured": has_key,
        "llm_active": llm_ok,
        "model": "llama-3.3-70b-versatile",
        # Not the real key bytes: this route is unauthenticated (the admin gate
        # was retired with the metrics store, see docs/plans/2026-09-04-retire-
        # python-infra.md). Only report presence, never a prefix of the secret.
        "key_prefix": "***" if has_key else "None",
    }


@router.get("/status", response_model=SystemStatusResponse)
async def get_system_status():
    """Returns a public system status report detailing service availability and active/past incidents."""
    db_status = "operational"
    cache_status = "operational"

    # Check Database
    from app.db.database import AsyncSessionLocal
    from sqlalchemy import text

    try:
        async with AsyncSessionLocal() as session:
            await session.execute(text("SELECT 1"))
    except Exception as exc:
        logger.warning("Database health check failed: %s", exc)
        db_status = "degraded"

    # Check Cache — read-only. This is a polled status endpoint; a write per
    # call (the previous `suggestions_cache.set`) is pure overhead on the DB.
    try:
        from app.db.pg_cache import _redis_active, _redis_client

        if _redis_active and _redis_client is not None:
            await _redis_client.ping()
        else:
            # L2 is Postgres; its reachability tracks the DB probe above.
            cache_status = db_status
    except Exception as exc:
        logger.warning("Cache health check failed: %s", exc)
        cache_status = "degraded"

    # Read incidents database — resolved once at module level path
    incidents: list = []
    try:
        if _INCIDENTS_PATH.exists():
            with open(_INCIDENTS_PATH, "r", encoding="utf-8") as f:
                incidents = json.load(f)
    except Exception as exc:
        logger.error("Failed to read incidents file at %s: %s", _INCIDENTS_PATH, exc)

    active_incidents = [inc for inc in incidents if inc.get("status") != "resolved"]

    # Determine overall state — call is_llm_working() once
    llm_ok = is_llm_working()

    if db_status == "degraded" or cache_status == "degraded":
        overall_state = "outage" if db_status == "degraded" else "degraded"
    elif active_incidents:
        overall_state = "degraded"
    else:
        overall_state = "operational"

    return {
        "status": overall_state,
        "services": {
            "api_gateway": "operational",
            "database": db_status,
            "cache_layer": cache_status,
            "ai_inference": "operational" if llm_ok else "degraded",
        },
        "incidents": incidents,
    }
