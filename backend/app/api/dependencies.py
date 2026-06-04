from typing import AsyncGenerator
from sqlalchemy.ext.asyncio import AsyncSession
from fastapi import Depends
from app.db.database import AsyncSessionLocal
from app.services.agent_service import AgentService
from app.services.pipeline_services import PipelineServices
from app.services.summarization_service import SummarizationService
from app.services.prediction_service import PredictionService
from app.services.scraping_service import ScrapingService
from app.services.openalex_service import OpenAlexService
from app.services.user_memory_service import UserMemoryService
from app.services.quests_service import QuestsService
from app.core.cache import history_summary_cache


async def get_db() -> AsyncGenerator[AsyncSession, None]:
    """
    FastAPI dependency that yields an async SQLAlchemy session.
    """
    async with AsyncSessionLocal() as session:
        yield session


async def get_agent_service(db: AsyncSession = Depends(get_db)) -> AgentService:
    """
    Dependency provider for AgentService.
    """
    return AgentService(history_summary_cache=history_summary_cache, db=db)


async def get_pipeline_services(db: AsyncSession = Depends(get_db)) -> PipelineServices:
    """
    Dependency provider for PipelineServices.
    """
    return PipelineServices(db=db)


async def get_user_memory_service(
    db: AsyncSession = Depends(get_db),
) -> UserMemoryService:
    """
    Dependency provider for UserMemoryService.
    """
    return UserMemoryService(db=db)


async def get_quests_service(db: AsyncSession = Depends(get_db)) -> QuestsService:
    """
    Dependency provider for QuestsService.
    """
    return QuestsService(db=db)


def get_summarization_service() -> SummarizationService:
    """
    Dependency provider for SummarizationService.
    """
    return SummarizationService()


def get_prediction_service() -> PredictionService:
    """
    Dependency provider for PredictionService.
    """
    return PredictionService()


def get_scraping_service() -> ScrapingService:
    """
    Dependency provider for ScrapingService.
    """
    return ScrapingService()


def get_openalex_service() -> OpenAlexService:
    """
    Dependency provider for OpenAlexService.
    """
    return OpenAlexService()


def get_openalex_headers(
    openalex_service: OpenAlexService = Depends(get_openalex_service),
) -> dict:
    """
    Returns default headers for OpenAlex requests.
    """
    return openalex_service.get_headers()
