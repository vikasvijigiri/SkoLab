from typing import List
from fastapi import APIRouter, Depends, Query, HTTPException

from app.domains.quest.schemas import Quest, LeaderboardEntry
from app.api.dependencies import (
    get_quests_service,
    get_openalex_service,
    require_owner,
)
from app.services.data.openalex_service import OpenAlexService
from app.domains.quest.service import QuestsService

router = APIRouter()


@router.get("/leaderboard/{field}", response_model=List[LeaderboardEntry])
async def get_quests_leaderboard(
    field: str,
    quests_service: QuestsService = Depends(get_quests_service),
):
    """
    Get the quest leaderboard for a specific scientific field.
    """
    try:
        return await quests_service.get_leaderboard(field)
    except ValueError:
        # Unknown field → empty leaderboard, not an error.
        return []


@router.get("/users/quests", response_model=List[Quest])
async def get_user_quests(
    user_id: str = Query(..., description="The user ID"),
    quests_service: QuestsService = Depends(get_quests_service),
    openalex_service: OpenAlexService = Depends(get_openalex_service),
    _owner: dict = Depends(require_owner("user_id")),
):
    """
    LLM-based quest initialisation for new users.
    Called only when Go gateway proxies here because quests don't exist in PG yet.

    Owner-scoped: creates/returns the caller's own quest records, so the
    verified Firebase uid must equal ``user_id`` (see docs/backend-auth-posture).
    """
    try:
        return await quests_service.get_user_quests(user_id, openalex_service)
    except ValueError as e:
        # Upstream (OpenAlex / LLM) could not produce a quest set.
        raise HTTPException(status_code=502, detail=str(e))
