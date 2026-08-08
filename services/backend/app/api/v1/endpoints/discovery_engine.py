from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel
from typing import Optional, List, Dict, Any

from app.services.ai.prediction_service import PredictionService
from app.api.dependencies import get_prediction_service, get_optional_user

router = APIRouter()


class PredictRequest(BaseModel):
    field: str
    focus_area: Optional[str] = None
    # The requesting researcher's own OpenAlex id (frontend already resolves
    # this for the logged-in user elsewhere, e.g. useMyProfile) — lets the
    # prediction be shaped by who's actually asking, not just the typed
    # field/focus_area, which previously produced identical output for
    # anyone who typed the same string.
    author_id: Optional[str] = None


class NexusChatRequest(BaseModel):
    papers: List[Dict[str, Any]]
    messages: List[Dict[str, str]]


@router.post("/discovery/predict")
async def predict_discovery(
    req: PredictRequest,
    prediction_service: PredictionService = Depends(get_prediction_service),
    _user: Optional[dict] = Depends(get_optional_user),
):
    try:
        if not req.field.strip():
            raise HTTPException(status_code=400, detail="Field query cannot be empty.")
        return await prediction_service.predict_next_big_thing(
            field=req.field, focus_area=req.focus_area, author_id=req.author_id
        )
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/discovery/nexus-chat")
async def nexus_chat(
    req: NexusChatRequest,
    prediction_service: PredictionService = Depends(get_prediction_service),
    _user: Optional[dict] = Depends(get_optional_user),
):
    try:
        response_text = await prediction_service.nexus_chat(
            papers=req.papers, messages=req.messages
        )
        return {"content": response_text}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))
