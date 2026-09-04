import logging
from fastapi import APIRouter
from app.services.ai.summarization_service import is_llm_working
from app.schemas.system import AiStatusResponse

logger = logging.getLogger(__name__)

router = APIRouter()

# GET /  and  GET /status  — migrated to the Go gateway (internal/system).
# Both are non-LLM metadata routes: Go serves the API-router root and the
# public status report (DB/cache probe + incidents + LLM-inference flag).
# decisions/0010. `/ai_status` stays here — it reports the LLM key/health.


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
