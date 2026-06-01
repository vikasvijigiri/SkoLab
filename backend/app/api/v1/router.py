from fastapi import APIRouter
from app.api.v1.endpoints import (
    system,
    quests,
    agent,
    papers,
    feed,
    authors,
    user_memory
)

api_router = APIRouter()

api_router.include_router(system.router, tags=["System"])
api_router.include_router(quests.router, tags=["Quests"])
api_router.include_router(agent.router, tags=["Agent"])
api_router.include_router(papers.router, tags=["Papers"])
api_router.include_router(feed.router, tags=["Feed"])
api_router.include_router(authors.router, tags=["Authors"])
api_router.include_router(user_memory.router, tags=["User Memory"])
