from typing import List
from fastapi import APIRouter, Depends, Query, HTTPException

from app.domains.quest.schemas import Quest
from app.api.dependencies import (
    get_quests_service,
    get_openalex_service,
    require_owner,
)
from app.services.data.openalex_service import OpenAlexService
from app.domains.quest.service import QuestsService

router = APIRouter()

# GET /leaderboard/{field} is intentionally not defined here: the Go gateway
# serves it natively (main.go -> quest.GetLeaderboard) and route precedence
# made the Python copy unreachable dead code. Per decisions/0010 ("Python is
# LLM-only"), a non-LLM route must not exist in Python at all — see
# app/domains/quest/service.py::get_leaderboard for the still-used service
# method (kept for its unit test coverage; nothing calls it over HTTP here).


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
