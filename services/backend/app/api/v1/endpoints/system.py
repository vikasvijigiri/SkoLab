from fastapi import APIRouter
from app.core.config import settings
from app.services.ai.summarization_service import is_llm_working
from app.schemas.system import AiStatusResponse

router = APIRouter()

# GET /  and  GET /status  — migrated to the Go gateway (internal/system).
# Both are non-LLM metadata routes: Go serves the API-router root and the
# public status report (DB/cache probe + incidents + LLM-inference flag).
# `/ai-status` stays here because it reports LLM-key availability and health.


@router.get("/ai-status", response_model=AiStatusResponse)
async def ai_status():
    """Checks if the AI services have valid API keys and are reachable."""
    import os

    groq_key = os.getenv("GROQ_API")
    has_key = groq_key is not None and len(groq_key) > 10
    llm_ok = is_llm_working()
    return {
        "groq_api_configured": has_key,
        "llm_active": llm_ok,
        # The actually-configured primary model (LLM_PRIMARY_MODEL), not a
        # hardcoded literal — this field itself was wrong throughout the
        # 2026-09-04 dead-model incident (see config.py's
        # llm_primary_model docstring) because it never reflected reality.
        "model": settings.llm_primary_model,
        # This unauthenticated route reports key presence only, never key bytes
        # or a secret prefix.
        "key_prefix": "***" if has_key else "None",
    }
