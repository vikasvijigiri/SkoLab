import os
from fastapi import APIRouter
from app.services.summarization_service import is_llm_working

router = APIRouter()

@router.get("/")
def read_root():
    return {"message": "Welcome to the SkoLab API!"}

@router.get("/ai_status")
async def ai_status():
    """Checks if the AI services have valid API keys and are reachable."""
    groq_key = os.getenv("GROQ_API")
    has_key = groq_key is not None and len(groq_key) > 10
    return {
        "groq_api_configured": has_key,
        "llm_active": is_llm_working(),
        "model": "llama-3.3-70b-versatile",
        "key_prefix": groq_key[:7] if has_key else "None"
    }
