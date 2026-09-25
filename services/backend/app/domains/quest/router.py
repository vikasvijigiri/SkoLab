from typing import List

from fastapi import APIRouter, Depends, HTTPException, Query

from app.api.dependencies import get_openalex_service, get_quests_service, require_owner
from app.domains.quest.schemas import Quest
from app.domains.quest.service import QuestsService
from app.services.data.openalex_service import OpenAlexService

router = APIRouter()


@router.get("/users/quests", response_model=List[Quest])
async def get_user_quests(
    user_id: str = Query(..., description="The user ID"),
    quests_service: QuestsService = Depends(get_quests_service),
    openalex_service: OpenAlexService = Depends(get_openalex_service),
    _owner: dict = Depends(require_owner("user_id")),
):
    """Create or return the authenticated user's LLM-assisted quest records."""
    try:
        return await quests_service.get_user_quests(user_id, openalex_service)
    except ValueError as exc:
        raise HTTPException(status_code=502, detail=str(exc)) from exc
