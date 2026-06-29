import json
import logging
import asyncio
from typing import Dict, Any
from sqlalchemy.ext.asyncio import AsyncSession
from app.services.user.user_memory_service import UserMemoryService
from app.services.data.openalex_service import OpenAlexService
from app.services.ai.llm_service import LLMService, is_llm_working
from app.core.cache import industry_academic_cache

logger = logging.getLogger(__name__)


class IndustryAcademicService:
    def __init__(self, db: AsyncSession):
        self.db = db
        self.user_memory_service = UserMemoryService(db)
        self.openalex_service = OpenAlexService()
        self.llm_service = LLMService()

    async def get_tieups(self, user_id: str) -> Dict[str, Any]:
        # 1. Check cache first
        try:
            cached = await industry_academic_cache.get(user_id)
            if cached is not None:
                logger.info(
                    f"Loaded industry-academic tie-ups from cache for {user_id}"
                )
                return cached
        except Exception as e:
            logger.error(f"Cache lookup failed for industry-academic tieups: {e}")

        # 2. Get user memory profile
        try:
            memory = await self.user_memory_service.get_user_memory(user_id)
            top_topics = memory.top_topics
            bio = memory.researcher_bio or ""
            last_topic = memory.last_active_topic
        except Exception as e:
            logger.error(f"Failed to load user memory for tieups: {e}")
            top_topics = []
            bio = ""
            last_topic = ""

        # Determine user domain — derive from activity, then ResearcherProfile, never fake data
        focus_domain = last_topic
        if not focus_domain and top_topics:
            focus_domain = top_topics[0]
        if not focus_domain:
            try:
                from app.models.user_models import ResearcherProfile, User
                from sqlalchemy.future import select as sa_select

                # user_id may be Firebase UID or OpenAlex ID
                openalex_id = user_id
                if not user_id.startswith("A") or not user_id[1:].isdigit():
                    stmt = sa_select(User).where(User.id == user_id)
                    res = await self.db.execute(stmt)
                    user_row = res.scalars().first()
                    if user_row and user_row.openalex_id:
                        openalex_id = user_row.openalex_id
                stmt = sa_select(ResearcherProfile).where(
                    ResearcherProfile.openalex_id == openalex_id
                )
                res = await self.db.execute(stmt)
                rp = res.scalars().first()
                if rp:
                    focus_domain = rp.field_of_study or (
                        rp.concepts[0]
                        if isinstance(rp.concepts, list) and rp.concepts
                        else ""
                    )
            except Exception as e:
                logger.error(f"ResearcherProfile lookup failed for tieups: {e}")
        if not focus_domain:
            return {"trending": [], "futuristic": []}

        # 3. Call LLM to generate tie-ups
        tieups_data = {"trending": [], "futuristic": []}
        if is_llm_working():
            prompt = (
                f"You are an expert academic-industry bridge advisor. Brainstorm 2 trending industry-academic tie-up ideas "
                f"and 2 futuristic frontier research tie-up ideas tailored to this researcher's profile:\n"
                f"- Primary Domain: {focus_domain}\n"
                f"- Top Topics: {', '.join(top_topics[:4]) if top_topics else 'None'}\n"
                f"- Biography: {bio}\n\n"
                f"Requirements for each tie-up idea:\n"
                f"1. Title: A concise, professional title.\n"
                f"2. Description: 2-3 sentences explaining the bridging concept, why it matters, and the current industry or research challenge it solves.\n"
                f"3. Search Queries: Provide 1-2 distinct search query strings (each 3-5 keywords, no boolean operators like AND/OR, just terms) that can be run on a paper search engine (like OpenAlex) to find highly relevant academic papers for this specific tie-up.\n\n"
                f"Provide your response in raw JSON format matching this schema:\n"
                f"{{\n"
                f"  \"trending\": [\n"
                f"    {{\n"
                f"      \"title\": \"Title\",\n"
                f"      \"description\": \"Description\",\n"
                f"      \"search_queries\": [\"query 1\", \"query 2\"]\n"
                f"    }}\n"
                f"  ],\n"
                f"  \"futuristic\": [\n"
                f"    {{\n"
                f"      \"title\": \"Title\",\n"
                f"      \"description\": \"Description\",\n"
                f"      \"search_queries\": [\"query 1\", \"query 2\"]\n"
                f"    }}\n"
                f"  ]\n"
                f"}}\n"
                f"Do not wrap your output in markdown code blocks like ```json ... ```, output ONLY raw JSON."
            )
            try:
                res = await self.llm_service.query(
                    messages=[
                        {
                            "role": "system",
                            "content": "You are a professional assistant that outputs only valid, raw JSON data matching the requested schema.",
                        },
                        {"role": "user", "content": prompt},
                    ],
                    temperature=0.7,
                    response_format={"type": "json_object"},
                )
                if res.content:
                    content = res.content.strip()
                    if content.startswith("```json"):
                        content = content[7:]
                    if content.endswith("```"):
                        content = content[:-3]
                    content = content.strip()
                    tieups_data = json.loads(content)
            except Exception as e:
                logger.error(f"LLM query for tie-ups failed: {e}")
                tieups_data = get_fallback_tieups(focus_domain)
        else:
            tieups_data = get_fallback_tieups(focus_domain)

        # 4. Fetch associated papers from OpenAlex
        async def fetch_papers_for_idea(idea: Dict[str, Any]) -> Dict[str, Any]:
            queries = idea.get("search_queries", [])
            papers = []
            seen_ids = set()
            for q in queries[:2]:
                try:
                    results = await self.openalex_service.search_works(q, per_page=2)
                    for r in results:
                        work_id = r.get("id", "")
                        if work_id and work_id not in seen_ids:
                            seen_ids.add(work_id)
                            authorships = r.get("authorships") or []
                            author_names = []
                            for auth in authorships:
                                author_obj = auth.get("author") or {}
                                name = author_obj.get("display_name")
                                if name:
                                    author_names.append(name)
                            primary_loc = r.get("primary_location") or {}
                            source = primary_loc.get("source") or {}
                            venue = source.get("display_name") or "Conference/Journal"

                            papers.append(
                                {
                                    "id": work_id,
                                    "title": r.get("title") or "Untitled Paper",
                                    "doi": r.get("doi") or "",
                                    "year": r.get("publication_year"),
                                    "journal": venue,
                                    "citations": r.get("cited_by_count", 0),
                                    "authors": author_names[:3],
                                }
                            )
                except Exception as e:
                    logger.error(f"Failed to fetch papers for query '{q}': {e}")
            idea["papers"] = papers
            return idea

        # Run paper fetches in parallel
        trending_ideas = tieups_data.get("trending", [])
        futuristic_ideas = tieups_data.get("futuristic", [])

        trending_tasks = [fetch_papers_for_idea(idea) for idea in trending_ideas]
        futuristic_tasks = [fetch_papers_for_idea(idea) for idea in futuristic_ideas]

        results = await asyncio.gather(
            asyncio.gather(*trending_tasks), asyncio.gather(*futuristic_tasks)
        )

        final_response = {"trending": results[0], "futuristic": results[1]}

        # 5. Cache result
        try:
            await industry_academic_cache.set(user_id, final_response)
        except Exception as e:
            logger.error(f"Failed to cache industry-academic tie-ups: {e}")

        return final_response


def get_fallback_tieups(domain: str) -> Dict[str, Any]:
    return {
        "trending": [
            {
                "title": f"Industrialization of {domain}",
                "description": f"Translating theoretical concepts in {domain} to commercial-grade software and enterprise deployment pipelines.",
                "search_queries": [
                    f"{domain} industrial applications",
                    f"{domain} production systems",
                ],
            }
        ],
        "futuristic": [
            {
                "title": f"Autonomous Research Agents in {domain}",
                "description": f"Designing self-correcting AI systems that autonomously formulate hypotheses, search literature, and run simulations for {domain}.",
                "search_queries": [
                    f"autonomous scientific discovery {domain}",
                    f"AI scientist {domain}",
                ],
            }
        ],
    }
