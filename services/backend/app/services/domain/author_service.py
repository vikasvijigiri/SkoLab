from typing import List, Union
from sqlalchemy.ext.asyncio import AsyncSession
from app.schemas.core import AuthorResponse, AuthorSuggestion, Work
from app.repositories.author_repository import AuthorRepository
from app.services.data.openalex_service import OpenAlexService
from app.core.cache import profile_cache, suggestions_cache
import re

class AuthorService:
    """
    Service Layer for Author Domain.
    Orchestrates logic between DB Repositories, Caches, and External APIs (OpenAlex).
    """

    def __init__(self, session: AsyncSession, openalex: OpenAlexService):
        self.session = session
        self.openalex = openalex

    async def get_author_suggestions(self, query: str) -> List[AuthorSuggestion]:
        cache_key = query.strip().lower()
        cached = await suggestions_cache.get(cache_key)
        if cached is not None:
            return cached

        # Try DB first
        pg_suggestions = await AuthorRepository.pg_search_suggestions_metrics(self.session, query, limit=4)
        if not pg_suggestions:
            pg_suggestions = await AuthorRepository.pg_search_suggestions_profiles(self.session, query, limit=4)

        if len(pg_suggestions) >= 4:
            suggestions = [
                AuthorSuggestion(
                    id=r.openalex_id,
                    display_name=r.display_name,
                    institution=r.current_institution if hasattr(r, 'current_institution') else getattr(r, 'institution', "Independent"),
                    field_of_study=r.field_of_study,
                    h_index=r.h_index,
                    innovation_score=100, # Placeholder
                    works_count=r.works_count,
                ) for r in pg_suggestions
            ]
            await suggestions_cache.set(cache_key, suggestions)
            return suggestions
            
        # Fallback to OpenAlex logic (simplified for the service layer)
        results = await self.openalex.search_authors(query, per_page=5)
        suggestions = []
        for author in results:
            disp_name = author.get("display_name")
            if not disp_name:
                continue
            suggestions.append(
                AuthorSuggestion(
                    id=author.get("id"),
                    display_name=disp_name,
                    institution="OpenAlex Entity",
                    field_of_study="General",
                    h_index=None,
                    innovation_score=80,
                    works_count=author.get("works_count", 0),
                )
            )
        await suggestions_cache.set(cache_key, suggestions)
        return suggestions

    async def search_author(self, name: str, author_id: str = None) -> Union[AuthorResponse, dict]:
        clean_id = author_id.split("/")[-1] if author_id else None
        
        # Check PG
        if clean_id:
            pg_row = await AuthorRepository.pg_get_researcher_metrics(self.session, clean_id)
            if pg_row:
                works = await AuthorRepository.pg_get_researcher_works(self.session, clean_id)
                # Map works to Pydantic
                mapped_works = [
                    Work(id=w.work_openalex_id, title=w.title, year=w.publication_year) 
                    for w in works
                ]
                return AuthorResponse(
                    id=pg_row.openalex_id,
                    display_name=pg_row.display_name,
                    works_count=pg_row.works_count,
                    works=mapped_works,
                    institution=pg_row.current_institution,
                )
        
        # Return mock / empty if not in DB for now to simplify refactor demonstration
        return {}
