import datetime
import time
from collections import Counter
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.future import select

from app.schemas.core import UserMemoryEventsRequest, UserMemoryProfileResponse
from app.models.analytics_models import UserActivityLog
from app.core.cache import _user_memory_cache


class UserMemoryService:
    """
    Service layer for user memory sync and dynamic profile aggregation.
    Decoupled from FastAPI HTTP routes to ensure separation of concerns.
    """

    def __init__(self, db: AsyncSession):
        """Initializes UserMemoryService with an injected request-scoped database session."""
        self.db = db

    async def sync_events(self, req: UserMemoryEventsRequest) -> int:
        """
        Ingests a batch of activity events and syncs them to PostgreSQL database log.
        """
        db_events = []
        for event in req.events:
            # Resolve entity attributes for quick PostgreSQL queries
            entity_id = (
                event.paperTitle
                or event.authorName
                or event.query
                or event.filterValue
                or event.collaboratorName
            )
            entity_name = (
                event.paperDomain
                or event.authorInstitution
                or event.collaboratorField
                or event.paperJournal
            )

            # Build comprehensive metadata dict
            meta = event.model_dump(exclude_none=True)

            # Parse timestamp safely
            created_at = datetime.datetime.now(datetime.UTC).replace(tzinfo=None)
            if event.timestamp:
                try:
                    created_at = datetime.datetime.fromtimestamp(
                        event.timestamp / 1000.0, datetime.UTC
                    ).replace(tzinfo=None)
                except Exception:
                    pass

            db_event = UserActivityLog(
                user_id=req.user_id,
                event_type=event.type,
                entity_id=entity_id,
                entity_name=entity_name,
                event_metadata=meta,
                created_at=created_at,
            )
            db_events.append(db_event)

        if db_events:
            try:
                self.db.add_all(db_events)
                await self.db.commit()
            except Exception:
                await self.db.rollback()
                raise

        # Invalidate current user memory L1/L2 cache
        await _user_memory_cache.delete(req.user_id)

        return len(db_events)

    async def get_user_memory(self, user_id: str) -> UserMemoryProfileResponse:
        """
        Retrieves user logs from the database, aggregates stats, and derives a memory profile.
        """
        # 1. Check L1/L2 cache
        try:
            cached = await _user_memory_cache.get(user_id)
            if cached is not None:
                return UserMemoryProfileResponse(**cached)
        except Exception as e:
            print(f"[UserMemoryService] Cache lookup error: {e}", flush=True)

        # 2. Query logs from DB and dynamically aggregate
        stmt = (
            select(UserActivityLog)
            .where(UserActivityLog.user_id == user_id)
            .order_by(UserActivityLog.created_at.asc())
        )
        result = await self.db.execute(stmt)
        logs = result.scalars().all()

        if not logs:
            return UserMemoryProfileResponse(
                user_id=user_id, last_updated=int(time.time() * 1000)
            )

        # Aggregate stats
        top_topics_counter = Counter()
        active_hours_counter = Counter()
        frequent_collaborators_counter = Counter()
        frequent_search_terms_counter = Counter()

        paper_durations = []  # list of (title, duration_sec)

        for log in logs:
            meta = log.event_metadata or {}
            event_type = log.event_type

            # Topics & Domains
            domain = meta.get("paperDomain")
            if domain and len(domain.strip()) > 3:
                top_topics_counter[domain.strip().title()] += 1

            query_val = meta.get("query")
            if query_val and len(query_val.strip()) > 3:
                frequent_search_terms_counter[query_val.strip()] += 1
                top_topics_counter[query_val.strip().title()] += 1

            filter_val = meta.get("filterValue")
            if filter_val and len(filter_val.strip()) > 3:
                top_topics_counter[filter_val.strip().title()] += 1

            # Peak Active Hours
            hour = meta.get("hourOfDay")
            if hour is not None:
                active_hours_counter[int(hour)] += 1

            # Paper Sessions
            if event_type == "PAPER_CLOSED" and meta.get("paperTitle"):
                title = meta.get("paperTitle")
                duration = meta.get("durationSeconds") or 0
                paper_durations.append((title, duration))

            # Collaborators
            collab = meta.get("collaboratorName") or meta.get("authorName")
            if collab and len(collab.strip()) > 2:
                frequent_collaborators_counter[collab.strip()] += 1

        # Top elements
        top_topics = [t for t, _ in top_topics_counter.most_common(6)]
        active_hours = [h for h, _ in active_hours_counter.most_common(4)]
        frequent_collaborators = [
            c for c, _ in frequent_collaborators_counter.most_common(5)
        ]
        frequent_search_terms = [
            s for s, _ in frequent_search_terms_counter.most_common(5)
        ]

        avg_read_minutes = 0.0
        reading_pace = "unknown"
        unfinished_papers = []
        recently_read_papers = []
        total_papers_read = 0

        if paper_durations:
            durations = [d for _, d in paper_durations]
            avg_read_sec = sum(durations) / len(durations)
            avg_read_minutes = float(avg_read_sec / 60.0)

            if avg_read_minutes >= 5.0:
                reading_pace = "deep_reader"
            elif avg_read_minutes >= 2.0:
                reading_pace = "moderate_reader"
            elif avg_read_minutes > 0.0:
                reading_pace = "quick_scanner"

            unfinished_papers = [t for t, d in paper_durations if 1 <= d < 90][-3:]
            recently_read_papers = [t for t, d in paper_durations if d >= 90][-5:]
            total_papers_read = sum(1 for _, d in paper_durations if d > 30)

        # Research style (number of unique fields explored)
        distinct_domains = {
            meta.get("paperDomain")
            for log in logs
            if (meta := log.event_metadata) and meta.get("paperDomain")
        }
        distinct_domains.discard(None)

        if len(distinct_domains) >= 4:
            research_style = "interdisciplinary"
        elif len(distinct_domains) >= 2:
            research_style = "focused"
        else:
            research_style = "exploratory"

        last_active_topic = top_topics[0] if top_topics else ""

        # Dynamically generate semantic biography using LLM reflection
        researcher_bio = "An active researcher currently focused on exploratory academic topics."
        try:
            from app.services.llm_service import LLMService, is_llm_working
            if is_llm_working():
                llm = LLMService()
                prompt = (
                    f"You are an expert academic advisor. Summarize the research profile of a user based on their recent activity logs:\n"
                    f"- Top Research Topics: {', '.join(top_topics[:4]) if top_topics else 'None'}\n"
                    f"- Frequent Search Terms: {', '.join(frequent_search_terms[:3]) if frequent_search_terms else 'None'}\n"
                    f"- Recently Read Papers: {', '.join(recently_read_papers[:3]) if recently_read_papers else 'None'}\n"
                    f"- Research Style: {research_style}\n"
                    f"- Reading Pace: {reading_pace}\n"
                    f"- Collaborators: {', '.join(frequent_collaborators[:3]) if frequent_collaborators else 'None'}\n\n"
                    f"Write a concise, professional 2-3 sentence academic biography/profile of this researcher's current trajectory, focus, and collaboration network. "
                    f"Write in the third person. Keep it highly specific, dense, and professional. Do not use generic filler."
                )
                res = await llm.query(
                    messages=[
                        {"role": "system", "content": "You are a professional academic career assistant."},
                        {"role": "user", "content": prompt}
                    ],
                    temperature=0.3,
                    max_tokens=256
                )
                if res.content:
                    researcher_bio = res.content.strip()
        except Exception as e:
            print(f"[UserMemoryService] Failed to generate semantic bio: {e}", flush=True)

        profile = UserMemoryProfileResponse(
            user_id=user_id,
            top_topics=top_topics,
            active_hours=active_hours,
            reading_pace=reading_pace,
            research_style=research_style,
            avg_read_minutes=avg_read_minutes,
            unfinished_papers=unfinished_papers,
            recently_read_papers=recently_read_papers,
            frequent_collaborators=frequent_collaborators,
            frequent_search_terms=frequent_search_terms,
            last_active_topic=last_active_topic,
            total_papers_read=total_papers_read,
            researcher_bio=researcher_bio,
            last_updated=int(time.time() * 1000),
        )

        # Save to L1/L2 cache
        try:
            await _user_memory_cache.set(user_id, profile.model_dump())
        except Exception as e:
            print(f"[UserMemoryService] Cache write error: {e}", flush=True)

        return profile
