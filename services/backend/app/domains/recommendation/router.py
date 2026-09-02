"""
Recommendation System Router — router.py
=========================================
Peer/collaborator autocomplete for CoLab project invites. See
`decisions/0007-retire-dormant-unified-recommendations.md` for why this
no longer exposes a unified GET /recommendations endpoint.
"""

from typing import Optional, List
from fastapi import APIRouter, Depends, Query
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.dependencies import get_db
from app.domains.recommendation.schemas import (
    PeerRecommendation,
    PeerInviteLogRequest,
    RegisteredCheckRequest,
    RegisteredCheckResponse,
)
from app.schemas.recommendation_extra import LogPeerInviteResponse
from app.domains.recommendation.service import RecommendationService

router = APIRouter(prefix="/recommendations", tags=["Recommendations"])


@router.get(
    "/peers",
    response_model=List[PeerRecommendation],
    summary="Get peer recommendations for autocomplete",
    description="Search and rank registered and cached researchers matching query criteria.",
)
async def get_peer_recommendations(
    query: str = Query(..., description="Query name, username, email, phone, or focus"),
    user_id: Optional[str] = Query(
        None, description="Current logged-in user ID for personalized ranking"
    ),
    db: AsyncSession = Depends(get_db),
) -> List[PeerRecommendation]:
    service = RecommendationService(db=db)
    return await service.get_peer_recommendations(query=query, user_id=user_id)


@router.post(
    "/peers/invite",
    response_model=LogPeerInviteResponse,
    summary="Log peer invitation to update recommendation engine scores",
)
async def log_peer_invite(
    request: PeerInviteLogRequest,
    db: AsyncSession = Depends(get_db),
):
    service = RecommendationService(db=db)
    success = await service.log_peer_invite(request)
    return {"success": success}


@router.post(
    "/peers/check-registered",
    response_model=RegisteredCheckResponse,
    summary="Check which emails/phones are registered in SkoLab",
)
async def check_registered_peers(
    request: RegisteredCheckRequest,
    db: AsyncSession = Depends(get_db),
) -> RegisteredCheckResponse:
    service = RecommendationService(db=db)
    return await service.check_registered_peers(request)
