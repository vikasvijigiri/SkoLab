from typing import List
from fastapi import APIRouter, Depends, Query, HTTPException

from app.schemas.core import Quest, LeaderboardEntry
from app.api.dependencies import get_quests_service, get_openalex_service
from app.services.openalex_service import OpenAlexService
from app.services.quests_service import QuestsService

router = APIRouter()


@router.get("/users/quests", response_model=List[Quest])
async def get_user_quests(
    user_id: str = Query(..., description="The user ID"),
    quests_service: QuestsService = Depends(get_quests_service),
    openalex_service: OpenAlexService = Depends(get_openalex_service),
):
    """
    Retrieves the gamified research quests list for a specific user.
    If no quests exist, initializes them dynamically using the researcher's focus field.
    """
    try:
        return await quests_service.get_user_quests(user_id, openalex_service)
    except ValueError as e:
        raise HTTPException(status_code=502, detail=str(e))
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/users/quests/complete")
async def complete_quest(
    user_id: str = Query(...),
    quest_id: str = Query(...),
    quests_service: QuestsService = Depends(get_quests_service),
    openalex_service: OpenAlexService = Depends(get_openalex_service),
):
    """
    Marks a specific quest as completed and awards reward entropy to the user.
    """
    try:
        return await quests_service.complete_quest(user_id, quest_id, openalex_service)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/leaderboard/{field}", response_model=List[LeaderboardEntry])
async def get_leaderboard(
    field: str, quests_service: QuestsService = Depends(get_quests_service)
):
    """
    Fetches the top 10 researchers in a specific field based on their innovation scores.
    """
    try:
        return await quests_service.get_leaderboard(field)
    except ValueError as e:
        raise HTTPException(status_code=502, detail=str(e))
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))
