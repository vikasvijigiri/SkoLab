"""
Recommendation System Router — router.py
=========================================
Exposes GET /recommendations with mode filtering.
"""

from typing import Optional
from fastapi import APIRouter, Depends, Query
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.dependencies import get_db
from app.domains.recommendation.schemas import RecommendationResponse
from app.domains.recommendation.service import RecommendationService

router = APIRouter(prefix="/recommendations", tags=["Recommendations"])


@router.get(
    "",
    response_model=RecommendationResponse,
    summary="Get unified hybrid recommendations",
    description=(
        "Returns a personalised feed of paper recommendations, grant matches, "
        "and collaborator suggestions using the SkoLab hybrid recommendation engine "
        "(time-decay profiling, cosine similarity, PageRank, MMR, serendipity injection, "
        "Bayesian grant scoring, and team composition optimisation)."
    ),
)
async def get_recommendations(
    author_id: Optional[str] = Query(
        default=None,
        description="OpenAlex author ID (e.g. A5023888391). If omitted, query fallback is used.",
    ),
    query: Optional[str] = Query(
        default=None,
        description="Fallback keyword query for anonymous / unregistered users.",
    ),
    mode: str = Query(
        default="full",
        description="Filter response: 'full' | 'papers' | 'grants' | 'collaborators'",
    ),
    db: AsyncSession = Depends(get_db),
) -> RecommendationResponse:
    service = RecommendationService(db=db)
    result = await service.get_unified_recommendations(
        author_id=author_id,
        query_fallback=query,
        mode=mode,
    )
    return result
