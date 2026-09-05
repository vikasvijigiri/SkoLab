import asyncio
import json
import logging
from contextlib import asynccontextmanager
from typing import Any, AsyncGenerator, Dict, List, Optional, Tuple

from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.future import select

from app.core.config import settings
from app.db.database import AsyncSessionLocal
from app.db.pg_cache import PgBackedCache
from app.services.ai.llm_service import is_llm_working
from app.services.data.openalex_service import OpenAlexService

logger = logging.getLogger(__name__)

# Shared across every _PipelineBase instance and every call to
# _save_to_postgres/_load_from_postgres below. These used to construct a
# fresh PgBackedCache(name="pipeline") on every single call — a brand-new
# instance means a brand-new, empty `_l1` dict every time, so the 30s
# in-memory L1 layer never actually served a hit for this cache namespace;
# every "cached" read still went to Postgres. A per-call `ttl_seconds`
# stays supported via PgBackedCache.set()'s own override parameter, so
# different callers wanting different TTLs don't need their own instance.
_pipeline_cache = PgBackedCache(ttl_seconds=3600, name="pipeline")


class _PipelineBase:
    def __init__(self, db: Optional[AsyncSession] = None):
        self.db = db
        from app.services.ai.llm_service import LLMService

        self.llm_service = LLMService()
        # Env-configurable (LLM_PRIMARY_MODEL) — see config.py's
        # llm_primary_model docstring for why this must never be a
        # hardcoded literal here.
        self.model = settings.llm_primary_model
        self.openalex_service = OpenAlexService()

    @asynccontextmanager
    async def _db_session(self) -> AsyncGenerator[AsyncSession, None]:
        if self.db is not None:
            yield self.db
        else:
            async with AsyncSessionLocal() as session:
                yield session

    def _get_firestore_db(self) -> Any:
        """Returns a Firestore client if available, else None."""
        try:
            from app.services.data.researcher_worker import FIRESTORE_AVAILABLE

            if not FIRESTORE_AVAILABLE:
                return None
            from firebase_admin import firestore as _firestore

            return _firestore.client()
        except Exception as exc:
            logger.warning(f"Firestore unavailable: {exc}")
            return None

    async def _firestore_get_safe(
        self, collection: str, doc_id: str, timeout: float = 5.0
    ) -> Optional[Dict[str, Any]]:
        """
        Wraps a synchronous Firestore document.get() in a thread executor with a short timeout.
        Prevents the blocking Firestore SDK from stalling the asyncio event loop when
        credentials are expired or the network is unavailable.
        Returns the document dict if found and within time, else None.
        """
        db = self._get_firestore_db()
        if not db:
            return None
        loop = asyncio.get_event_loop()
        try:

            def _blocking_get() -> Optional[Dict[str, Any]]:
                doc = db.collection(collection).document(doc_id).get()
                return doc.to_dict() if doc.exists else None

            result = await asyncio.wait_for(
                loop.run_in_executor(None, _blocking_get), timeout=timeout
            )
            return result
        except asyncio.TimeoutError:
            logger.warning(
                f"Firestore get timed out ({timeout}s) for {collection}/{doc_id}"
            )
        except Exception as e:
            logger.warning(f"Firestore get error for {collection}/{doc_id}: {e}")
        return None

    async def _firestore_set_safe(
        self, collection: str, doc_id: str, data: Dict[str, Any], timeout: float = 5.0
    ) -> bool:
        """
        Wraps a synchronous Firestore document.set() in a thread executor with a short timeout.
        Prevents the blocking Firestore SDK from stalling the asyncio event loop.
        """
        db = self._get_firestore_db()
        if not db:
            return False
        loop = asyncio.get_event_loop()
        try:

            def _blocking_set() -> bool:
                db.collection(collection).document(doc_id).set(data)
                return True

            await asyncio.wait_for(
                loop.run_in_executor(None, _blocking_set), timeout=timeout
            )
            return True
        except asyncio.TimeoutError:
            logger.warning(
                f"Firestore set timed out ({timeout}s) for {collection}/{doc_id}"
            )
        except Exception as e:
            logger.warning(f"Firestore set error for {collection}/{doc_id}: {e}")
        return False

    async def _save_to_postgres(
        self, cache_key: str, data: Dict[str, Any], ttl_seconds: int = 3600
    ) -> None:
        """Save data to PostgreSQL cache_entries with TTL via PgBackedCache."""
        await _pipeline_cache.set(cache_key, data, ttl_seconds=ttl_seconds)

    async def _load_from_postgres(
        self, cache_key: str, ttl_seconds: int = 3600
    ) -> Optional[Dict[str, Any]]:
        """Load data from PostgreSQL cache_entries with TTL check.

        `ttl_seconds` is accepted for call-site compatibility but unused
        here — a read's TTL was already applied when the value was
        written (PgBackedCache.get() checks CacheEntry.expires_at, set
        at write time), so there's nothing for a read to re-apply.
        """
        return await _pipeline_cache.get(cache_key)

    async def _fetch_author_profile(self, author_id: str) -> Optional[Dict[str, Any]]:
        """Helper to fetch author profile from OpenAlex or database."""
        return await self.openalex_service.fetch_author_by_id(author_id)

    async def _extract_recent_paper_keywords(
        self, user_works: List[Dict[str, Any]]
    ) -> List[str]:
        """
        LLM-extracted technical keywords/phrases from the researcher's 5 most
        recent papers (title + abstract). Sharper and more specific than raw
        title/concept tokens alone — surfaces the actual methods, models, and
        sub-topics the researcher is currently working on (e.g. "spin-echo
        decoherence", "Jaynes-Cummings model") rather than just broad field
        tags, which is what feeds the embedding query below.
        """
        if not is_llm_working() or not user_works:
            return []

        def sort_key(w: Dict[str, Any]) -> str:
            return w.get("publication_date") or str(w.get("publication_year") or "0")

        recent = sorted(
            [w for w in user_works if w.get("title")], key=sort_key, reverse=True
        )[:5]
        if not recent:
            return []

        context_parts = []
        for w in recent:
            abstract = w.get("abstract") or w.get("_custom_abstract") or ""
            if not abstract and w.get("abstract_inverted_index"):
                abstract = self._reconstruct_abstract(w["abstract_inverted_index"])
            context_parts.append(f"Title: {w.get('title')}\nAbstract: {abstract[:600]}")
        context = "\n\n".join(context_parts)

        try:
            response = await self.llm_service.query(
                messages=[
                    {
                        "role": "system",
                        "content": "You are a helpful assistant that outputs only valid raw JSON.",
                    },
                    {
                        "role": "user",
                        "content": (
                            "Extract 10-15 precise technical research keywords/phrases "
                            "(specific methods, models, phenomena, materials — not generic "
                            "field names like 'physics' or 'machine learning') that best "
                            "characterize this researcher's current work, based on their 5 "
                            "most recent papers below.\n\n"
                            f"{context}\n\n"
                            'Return raw JSON: {"keywords": ["...", "..."]}'
                        ),
                    },
                ],
                temperature=0.2,
                max_tokens=300,
                response_format={"type": "json_object"},
            )
            if response.content:
                data = json.loads(response.content)
                keywords = data.get("keywords")
                if isinstance(keywords, list):
                    return [str(k) for k in keywords if k][:15]
        except Exception as e:
            print(f"[DailyFeed] LLM keyword extraction failed: {e}", flush=True)
        return []

    async def _resolve_author_concepts_and_name(
        self, author_id: str, doc_id: str
    ) -> Tuple[str, List[str]]:
        """Resolves concepts and name for a researcher from profile cache or local DB."""
        profile = await self._fetch_author_profile(author_id)
        if profile:
            author_name = profile.get("display_name", "Researcher")
            from app.services.data.openalex_service import extract_field_and_expertise

            _, concepts = extract_field_and_expertise(profile, author_name)
            return author_name, concepts or []

        # Fallback: Query local database metrics for concepts
        try:
            from app.models.researcher_models import ResearcherMetrics

            async with self._db_session() as session:
                stmt = select(ResearcherMetrics).where(
                    ResearcherMetrics.openalex_id == doc_id
                )
                res = await session.execute(stmt)
                rm = res.scalars().first()
                if rm:
                    return rm.display_name, rm.expertise or []
        except Exception as e:
            print(f"[DailyFeed] Database lookup fallback error: {e}", flush=True)
        return "Researcher", []

    def _reconstruct_abstract(
        self, abstract_index: Optional[Dict[str, List[int]]]
    ) -> Optional[str]:
        """Reconstructs the abstract from the OpenAlex abstract_inverted_index."""
        if not abstract_index:
            return None
        try:
            word_list = []
            for word, pos_list in abstract_index.items():
                for pos in pos_list:
                    word_list.append((pos, word))
            word_list.sort()
            return " ".join([w[1] for w in word_list])
        except Exception:
            return None

    def _reconstruct_abstract(self, inv_idx: Optional[Dict[str, List[int]]]) -> str:
        if not inv_idx or not isinstance(inv_idx, dict):
            return ""
        try:
            word_pos = [
                (pos, word) for word, positions in inv_idx.items() for pos in positions
            ]
            return " ".join(wp[1] for wp in sorted(word_pos))
        except Exception:
            return ""
