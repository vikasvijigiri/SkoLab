from fastapi import APIRouter, Depends, HTTPException, Query
from sqlalchemy.ext.asyncio import AsyncSession

from app.schemas.core import IndustryAcademicTieupsResponse
from app.api.dependencies import get_db
from app.services.industry.industry_academic_service import IndustryAcademicService

router = APIRouter()


@router.get("/industry_academic_tieups", response_model=IndustryAcademicTieupsResponse)
async def get_industry_academic_tieups(
    user_id: str = Query(..., description="Firebase UID of the user"),
    db: AsyncSession = Depends(get_db),
):
    """
    Dynamically brainstorms and queries industry-academic tie-up ideas
    based on the user's semantic memory profile and recent active topics.
    Each idea is enriched with real academic papers searched on OpenAlex.
    """
    try:
        service = IndustryAcademicService(db)
        return await service.get_tieups(user_id)
    except Exception as e:
        raise HTTPException(
            status_code=500,
            detail=f"Failed to generate industry-academic tie-ups: {str(e)}"
        )
