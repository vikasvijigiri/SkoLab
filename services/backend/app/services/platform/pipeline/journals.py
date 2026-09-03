import asyncio
from typing import Any, Dict, List, Set

from sqlalchemy.future import select

from app.prompts import JOURNAL_ADVISOR_RATIONALE_PROMPT_TEMPLATE
from app.services.ai.embedding_service import score_candidates_against_profile
from app.services.ai.llm_service import is_llm_working


class JournalsMixin:
    async def get_journal_advisor(self, author_id: str) -> List[Dict[str, Any]]:
        """
        Recommends real journal venues from OpenAlex, ranked by embedding
        similarity to the researcher's actual profile. The LLM is used only
        to write a short plain-English rationale per journal — never to
        invent the journal identity, its stats, or (the past source of
        garbled-LaTeX bugs) a "submission tip" with gratuitous equations.
        """
        clean_id = author_id.split("/")[-1]
        cache_key = f"journal_advisor_{clean_id}"
        cached_data = await self._load_from_postgres(cache_key, ttl_seconds=7200)
        if isinstance(cached_data, dict) and "venues" in cached_data:
            print(
                f"[Postgres Cache Hit] journal_advisor for author_id={clean_id}",
                flush=True,
            )
            return cached_data["venues"]
        _fs_cached = await self._firestore_get_safe(
            "journal_advisor_recommendations", clean_id, timeout=5.0
        )
        if isinstance(_fs_cached, dict) and "venues" in _fs_cached:
            print(
                f"[Firestore Cache Hit] journal_advisor_recommendations for author_id={clean_id}",
                flush=True,
            )
            await self._save_to_postgres(
                cache_key, {"venues": _fs_cached["venues"]}, ttl_seconds=7200
            )
            return _fs_cached["venues"]

        # x_concepts is largely empty on current OpenAlex author objects
        # (confirmed live — deprecated in favor of `topics`), so reading it
        # directly silently fell back to a useless "science" query term.
        # extract_field_and_expertise (topics-first, same helper get_daily_feed
        # uses via _resolve_author_concepts_and_name) fixes that — called
        # directly here (not through the helper) because it also needs the
        # broader single `field` label the helper discards.
        profile = await self._fetch_author_profile(author_id)
        author_name = "Researcher"
        field = ""
        concepts: List[str] = []
        if profile:
            from app.services.data.openalex_service import extract_field_and_expertise

            author_name = profile.get("display_name", "Researcher")
            field, concepts = extract_field_and_expertise(profile, author_name)
        else:
            try:
                from app.models.researcher_models import ResearcherMetrics

                async with self._db_session() as session:
                    stmt = select(ResearcherMetrics).where(
                        ResearcherMetrics.openalex_id == clean_id
                    )
                    res = await session.execute(stmt)
                    rm = res.scalars().first()
                    if rm:
                        author_name = rm.display_name
                        field = rm.field_of_study or ""
                        concepts = rm.expertise or []
            except Exception as e:
                print(
                    f"[JournalAdvisor] Database lookup fallback error: {e}", flush=True
                )

        # Richer profile signal than concepts alone — same recent-paper
        # keyword extraction get_daily_feed uses, so sparse profiles (few
        # broad concepts) still get sharp, current-work-specific query terms.
        recent_keywords: List[str] = []
        try:
            user_works = await self.openalex_service.fetch_author_works(
                clean_id, per_page=10
            )
            if user_works:
                recent_keywords = await self._extract_recent_paper_keywords(user_works)
        except Exception as e:
            print(f"[JournalAdvisor] Recent-works fetch failed: {e}", flush=True)

        # `field` first — OpenAlex's /sources search matches fairly literally
        # against journal names/descriptions (confirmed live: short, standard
        # field labels like "Theoretical Physics" or "Cosmology" reliably
        # match real journals; composite topic labels like "Black Holes and
        # Theoretical Physics" or specific technical keywords usually match
        # nothing at all) — so lead with the label most likely to actually
        # hit, and treat the sharper concepts/keywords as additional tries
        # rather than the primary query.
        query_terms = list(
            dict.fromkeys([t for t in [field, *concepts, *recent_keywords] if t])
        )[:8] or ["science"]

        # Fetch real candidate journals from OpenAlex — multiple query terms,
        # deduped by id, same "gather from several search terms" pattern
        # get_daily_feed uses for candidate papers. Tries more terms than a
        # typical candidate search since several will legitimately return
        # zero results (see note above) rather than being a bug to fix.
        seen_ids: Set[str] = set()
        candidates: List[Dict[str, Any]] = []
        for term in query_terms[:6]:
            try:
                results = await self.openalex_service.search_sources(term, per_page=8)
            except Exception as e:
                print(
                    f"[JournalAdvisor] search_sources failed for '{term}': {e}",
                    flush=True,
                )
                continue
            for src in results:
                src_id = src.get("id")
                if not src_id or src_id in seen_ids:
                    continue
                # OpenAlex /sources search matches repositories, conference
                # proceedings, ebook platforms, and funding-agency
                # aggregators alongside real journals (confirmed live —
                # "Open Science Framework", "Lecture notes in computer
                # science", a Portuguese funding agency's repository all
                # matched generic queries) — "Journal Advisor" should only
                # ever recommend type == "journal".
                if src.get("type") != "journal":
                    continue
                # Drop implausibly inactive/defunct-looking venues rather
                # than inventing a scoring system for venue health.
                if (src.get("works_count") or 0) < 20:
                    continue
                seen_ids.add(src_id)
                candidates.append(src)

        if not candidates:
            # Real data genuinely has nothing for this niche — return empty
            # and let the frontend show its existing "not enough data yet"
            # state, instead of falling back to invented journals.
            return []

        # Rank by real embedding similarity between the researcher's profile
        # and each journal's own real text (name + topics + publisher) — the
        # same grounding already used for match_score, now also deciding
        # *which* journals to show, not just their displayed score.
        profile_text = (author_name + " " + " ".join(query_terms))[:1000]
        candidate_texts = [
            f"{c.get('display_name', '')} "
            + " ".join(t.get("display_name", "") for t in (c.get("topics") or [])[:5])
            + f" {c.get('host_organization_name') or ''}"
            for c in candidates
        ]
        try:
            scores = await score_candidates_against_profile(
                profile_text, candidate_texts
            )
        except Exception as e:
            print(f"[JournalAdvisor] Grounded scoring failed: {e}", flush=True)
            scores = [70] * len(candidates)

        ranked = sorted(zip(candidates, scores), key=lambda pair: pair[1], reverse=True)
        top3 = ranked[:3]

        async def _rationale_for(src: Dict[str, Any]) -> str:
            default = f"Aligned with your work in {', '.join(query_terms[:2])}."
            if not is_llm_working():
                return default
            try:
                response = await self.llm_service.query(
                    messages=[
                        {
                            "role": "user",
                            "content": JOURNAL_ADVISOR_RATIONALE_PROMPT_TEMPLATE.format(
                                author_name=author_name,
                                concepts=", ".join(query_terms[:3]),
                                journal_name=src.get("display_name") or "this journal",
                                works_count=src.get("works_count") or 0,
                                oa_status="fully open access"
                                if src.get("is_oa")
                                else "hybrid/subscription access",
                                host_organization=src.get("host_organization_name")
                                or "an academic publisher",
                            ),
                        }
                    ],
                    models=[self.model],
                    temperature=0.4,
                    max_tokens=100,
                )
                return (response.content or default).strip() or default
            except Exception as e:
                print(
                    f"[JournalAdvisor] Rationale generation failed for "
                    f"{src.get('display_name')}: {e}",
                    flush=True,
                )
                return default

        rationales = await asyncio.gather(*[_rationale_for(src) for src, _ in top3])

        venues: List[Dict[str, Any]] = []
        for (src, score), rationale in zip(top3, rationales):
            stats = src.get("summary_stats") or {}
            venues.append(
                {
                    "journal_name": src.get("display_name") or "Unknown Journal",
                    "works_count": src.get("works_count") or 0,
                    "is_oa": bool(src.get("is_oa")),
                    "citation_impact": round(stats.get("2yr_mean_citedness") or 0.0, 2),
                    "match_score": score,
                    "rationale": rationale,
                }
            )

        try:
            await self._save_to_postgres(
                cache_key, {"venues": venues}, ttl_seconds=7200
            )
            print(
                f"[Postgres Cache Save] journal_advisor for author_id={clean_id}",
                flush=True,
            )
        except Exception as e:
            print(
                f"[Postgres Cache Error] journal_advisor write failed: {e}", flush=True
            )

        try:
            from firebase_admin import firestore as _fs

            await self._firestore_set_safe(
                "journal_advisor_recommendations",
                clean_id,
                {"venues": venues, "last_synced": _fs.SERVER_TIMESTAMP},
            )
        except Exception as e:
            print(
                f"[Firestore Cache Error] journal_advisor_recommendations write failed: {e}",
                flush=True,
            )
        return venues
