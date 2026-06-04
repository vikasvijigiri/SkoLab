from fastapi import APIRouter, Depends, HTTPException

from app.schemas.core import UserMemoryEventsRequest, UserMemoryProfileResponse
from app.api.dependencies import get_user_memory_service
from app.services.user_memory_service import UserMemoryService

router = APIRouter()


@router.post("/user_memory/events")
async def sync_user_memory_events(
    req: UserMemoryEventsRequest,
    user_memory_service: UserMemoryService = Depends(get_user_memory_service),
):
    """
    Ingests and saves a batch of local researcher activity events to PostgreSQL.
    Wipes the researcher's memory cache to trigger real-time re-aggregation on the next read.
    """
    try:
        synced_count = await user_memory_service.sync_events(req)
        return {"status": "success", "synced": synced_count}
    except Exception as e:
        raise HTTPException(
            status_code=500, detail=f"Database synchronization failed: {str(e)}"
        )


@router.get("/user_memory/{userId}", response_model=UserMemoryProfileResponse)
async def get_user_memory(
    userId: str,
    user_memory_service: UserMemoryService = Depends(get_user_memory_service),
):
    """
    Loads and aggregates user activity logs to derive a personalized researcher memory profile.
    Caches the results for subsequent fast lookups.
    """
    try:
        return await user_memory_service.get_user_memory(userId)
    except Exception as e:
        raise HTTPException(
            status_code=500, detail=f"Failed to load user research memory: {str(e)}"
        )
