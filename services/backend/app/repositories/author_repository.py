from typing import List, Optional
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy import select
from app.models.user_models import ResearcherProfile
from app.models.researcher_models import ResearcherMetrics, ResearcherWork
import datetime


class AuthorRepository:
    """
    Data Access Object (DAO) for Author/Researcher data.
    Isolates PostgreSQL and Firestore queries from API routes.
    """

    @staticmethod
    async def pg_search_suggestions_metrics(
        session: AsyncSession, query: str, limit: int
    ) -> List[ResearcherMetrics]:
        stmt = (
            select(ResearcherMetrics)
            .where(ResearcherMetrics.display_name.ilike(f"%{query}%"))
            .limit(limit)
        )
        result = await session.execute(stmt)
        return list(result.scalars().all())

    @staticmethod
    async def pg_search_suggestions_profiles(
        session: AsyncSession, query: str, limit: int
    ) -> List[ResearcherProfile]:
        stmt = (
            select(ResearcherProfile)
            .where(ResearcherProfile.display_name.ilike(f"%{query}%"))
            .limit(limit)
        )
        result = await session.execute(stmt)
        return list(result.scalars().all())

    @staticmethod
    async def pg_get_researcher_metrics(
        session: AsyncSession, clean_id: str
    ) -> Optional[ResearcherMetrics]:
        now = datetime.datetime.now(datetime.timezone.utc).replace(tzinfo=None)
        stmt = select(ResearcherMetrics).where(
            ResearcherMetrics.openalex_id == clean_id,
            ResearcherMetrics.expires_at > now,
        )
        result = await session.execute(stmt)
        return result.scalars().first()

    @staticmethod
    async def pg_get_researcher_works(
        session: AsyncSession, clean_id: str
    ) -> List[ResearcherWork]:
        stmt = select(ResearcherWork).where(
            ResearcherWork.author_openalex_id == clean_id
        )
        result = await session.execute(stmt)
        return list(result.scalars().all())
