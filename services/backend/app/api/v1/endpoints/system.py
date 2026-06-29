import logging
import json
from pathlib import Path
from fastapi import APIRouter
from app.services.ai.summarization_service import is_llm_working

logger = logging.getLogger(__name__)

router = APIRouter()

# Incidents file is at <repo_root>/docs/incidents.json.
# This file lives at <repo_root>/backend/app/api/v1/endpoints/system.py,
# so we need to go up 6 directories to reach the repo root.
_REPO_ROOT = Path(__file__).resolve().parents[6]
_INCIDENTS_PATH = _REPO_ROOT / "docs" / "incidents.json"


@router.get("/")
def read_root():
    return {"message": "Welcome to the SkoLab API!"}


@router.get("/ai_status")
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
        "key_prefix": groq_key[:7] if has_key else "None",
    }


@router.get("/status")
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

    # Check Cache
    from app.core.cache import suggestions_cache

    try:
        await suggestions_cache.set("status_ping", "1")
        val = await suggestions_cache.get("status_ping")
        if val != "1":
            cache_status = "degraded"
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
