import logging

from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy.ext.asyncio import AsyncSession

from app.schemas.core import IndustryAcademicTieupsResponse
from app.api.dependencies import get_db, require_owner
from app.core.exceptions import AIUnavailable
from app.services.industry.industry_academic_service import IndustryAcademicService

logger = logging.getLogger("skolab")

router = APIRouter()


@router.get("/industry_academic_tieups", response_model=IndustryAcademicTieupsResponse)
async def get_industry_academic_tieups(
    user_id: str = Query(..., description="Firebase UID of the user"),
    db: AsyncSession = Depends(get_db),
    _owner: dict = Depends(require_owner("user_id")),
):
    """
    Dynamically brainstorms and queries industry-academic tie-up ideas
    based on the user's semantic memory profile and recent active topics.
    Each idea is enriched with real academic papers searched on OpenAlex.

    Owner-scoped: reads the caller's private semantic-memory profile, so the
    verified Firebase uid must equal ``user_id`` (see docs/backend-auth-posture).
    """
    try:
        service = IndustryAcademicService(db)
        return await service.get_tieups(user_id)
    except (HTTPException, AIUnavailable):
        raise
    except Exception as e:
        # This route brainstorms via the LLM and has no local fallback; a
        # provider outage is transient, so surface 503 rather than 500. The
        # exception text is logged, never returned (it can carry internals).
        logger.error("industry_academic_tieups failed for %s: %s", user_id, e)
        raise AIUnavailable(
            "Industry–academic tie-up ideas are temporarily unavailable."
        ) from e
