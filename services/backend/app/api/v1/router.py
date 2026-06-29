from fastapi import APIRouter
from app.api.v1.endpoints import (
    system,
    agent,
    papers,
    feed,
    authors,
    support,
    industry_academic,
)
from app.domains.quest.router import router as quest_router

api_router = APIRouter()

api_router.include_router(system.router, tags=["System"])
api_router.include_router(quest_router, tags=["Quests"])
api_router.include_router(agent.router, tags=["Agent"])
api_router.include_router(papers.router, tags=["Papers"])
api_router.include_router(feed.router, tags=["Feed"])
api_router.include_router(authors.router, tags=["Authors"])
# user_memory endpoints fully migrated to Go gateway (internal/handlers/memory.go)
api_router.include_router(support.router, prefix="/support", tags=["Support"])
api_router.include_router(industry_academic.router, tags=["Industry Academic"])
