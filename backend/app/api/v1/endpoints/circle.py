import logging
from fastapi import APIRouter, Depends, HTTPException, Query
from pydantic import BaseModel
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.dependencies import get_db, get_verified_user
from app.services.circle_service import CircleService

logger = logging.getLogger("skolab.circle")
router = APIRouter()


class AddPeerRequest(BaseModel):
    peer_id: str
    relationship_type: str = "manual"
    field_tags: list[str] = []
    relevance_score: float = 0.5


class SparkSessionRequest(BaseModel):
    seeker_id: str
    helper_id: str
    field_tags: list[str] = []


@router.get("/circles/{userId}")
async def get_circle(
    userId: str,
    db: AsyncSession = Depends(get_db),
    verified_user: dict = Depends(get_verified_user),
):
    if verified_user.get("uid") != userId:
        raise HTTPException(status_code=403, detail="Forbidden")
    svc = CircleService(db)
    return {"circle": await svc.get_circle(userId)}


@router.post("/circles/{userId}/peers")
async def add_peer(
    userId: str,
    body: AddPeerRequest,
    db: AsyncSession = Depends(get_db),
    verified_user: dict = Depends(get_verified_user),
):
    if verified_user.get("uid") != userId:
        raise HTTPException(status_code=403, detail="Forbidden")
    if userId == body.peer_id:
        raise HTTPException(status_code=400, detail="Cannot add yourself to your circle")
    svc = CircleService(db)
    return await svc.add_to_circle(
        userId, body.peer_id, body.relationship_type, body.field_tags, body.relevance_score
    )


@router.delete("/circles/{userId}/peers/{peerId}")
async def remove_peer(
    userId: str,
    peerId: str,
    db: AsyncSession = Depends(get_db),
    verified_user: dict = Depends(get_verified_user),
):
    if verified_user.get("uid") != userId:
        raise HTTPException(status_code=403, detail="Forbidden")
    svc = CircleService(db)
    return await svc.remove_from_circle(userId, peerId)


@router.post("/circles/spark-session")
async def record_spark_session(
    body: SparkSessionRequest,
    db: AsyncSession = Depends(get_db),
    verified_user: dict = Depends(get_verified_user),
):
    uid = verified_user.get("uid")
    if uid not in (body.seeker_id, body.helper_id):
        raise HTTPException(status_code=403, detail="Forbidden")
    svc = CircleService(db)
    await svc.record_spark_session(body.seeker_id, body.helper_id, body.field_tags)
    return {"status": "ok"}


@router.get("/circles/{userId}/matches")
async def find_field_matches(
    userId: str,
    tags: list[str] = Query(default=[]),
    limit: int = Query(default=20, le=50),
    db: AsyncSession = Depends(get_db),
    verified_user: dict = Depends(get_verified_user),
):
    if verified_user.get("uid") != userId:
        raise HTTPException(status_code=403, detail="Forbidden")
    svc = CircleService(db)
    return {"matches": await svc.find_field_matches(userId, tags, limit)}
