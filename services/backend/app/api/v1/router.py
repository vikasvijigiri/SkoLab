from fastapi import APIRouter

from app.api.v1.endpoints import (
    agent,
    authors,
    colab,
    discovery_engine,
    feed,
    industry_academic,
    internal,
    papers,
    system,
)
from app.domains.quest.router import router as quest_router

api_router = APIRouter()

api_router.include_router(system.router, tags=["System"])
api_router.include_router(quest_router, tags=["Quests"])
api_router.include_router(agent.router, tags=["Agent"])
api_router.include_router(papers.router, tags=["Papers"])
api_router.include_router(feed.router, tags=["Feed"])
api_router.include_router(authors.router, tags=["Authors"])
api_router.include_router(discovery_engine.router, tags=["Discovery Engine"])
api_router.include_router(internal.router, tags=["Internal"])
api_router.include_router(colab.router, tags=["CoLab"])
api_router.include_router(industry_academic.router, tags=["Industry Academic"])
