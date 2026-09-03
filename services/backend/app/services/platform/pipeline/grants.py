import datetime
from typing import Any, Dict, List, Optional


class GrantsMixin:
    _GRANT_AGENCY_COLORS = [
        "#009688",
        "#3F51B5",
        "#4CAF50",
        "#E91E63",
        "#FF9800",
        "#9C27B0",
    ]

    @staticmethod
    def _parse_grant_days_left(deadline: str) -> Optional[int]:
        """
        Best-effort parse of a real scraped deadline string into days-remaining.
        Returns None (not a fabricated number) for "Rolling"/"Open Now"/anything
        that doesn't match the specific date format the scraping prompt asks
        for ("Dec 15, 2026") — the frontend already treats a missing days_left
        as "no live countdown" rather than inventing one.
        """
        if not deadline:
            return None
        for fmt in ("%b %d, %Y", "%B %d, %Y", "%Y-%m-%d", "%m/%d/%Y"):
            try:
                parsed = datetime.datetime.strptime(deadline.strip(), fmt)
                delta = (parsed.date() - datetime.datetime.now().date()).days
                return delta if delta >= 0 else None
            except ValueError:
                continue
        return None

    async def match_grants(self, author_id: str) -> List[Dict[str, Any]]:
        """
        Matches real, live-scraped funding opportunities (grants/fellowships)
        against the researcher's real profile — reuses the same real-data
        scraping + embedding-grounded match-scoring pipeline as
        fetch_industry_opportunities (which already scrapes both JOB and
        FUNDING listings from real portals) instead of a hardcoded 6-grant
        list with static, never-counting-down "days_left" values.
        """
        clean_id = author_id.split("/")[-1]
        cache_key = f"match_grants_{clean_id}"
        cached_data = await self._load_from_postgres(cache_key)
        if isinstance(cached_data, dict) and "items" in cached_data:
            print(
                f"[Postgres Cache Hit] match_grants for author_id={clean_id}",
                flush=True,
            )
            return cached_data["items"]
        _fs_cached = await self._firestore_get_safe(
            "match_grants", clean_id, timeout=5.0
        )
        if isinstance(_fs_cached, dict) and "items" in _fs_cached:
            print(
                f"[Firestore Cache Hit] match_grants for author_id={clean_id}",
                flush=True,
            )
            await self._save_to_postgres(cache_key, {"items": _fs_cached["items"]})
            return _fs_cached["items"]

        author_name, concepts = await self._resolve_author_concepts_and_name(
            author_id, clean_id
        )
        focus = concepts[0] if concepts else "STEM Research"

        from app.services.industry.industry_service import fetch_industry_opportunities

        async with self._db_session() as session:
            opportunities = await fetch_industry_opportunities(
                focus,
                name=author_name,
                openalex_service=self.openalex_service,
                db=session,
            )

        funding_items = [o for o in opportunities if o.get("type") == "FUNDING"]

        scored_grants = [
            {
                "title": opp.get("title") or "Research Funding Opportunity",
                "agency": opp.get("companyOrFunder") or "Funding Agency",
                "agency_color": self._GRANT_AGENCY_COLORS[
                    i % len(self._GRANT_AGENCY_COLORS)
                ],
                "days_left": self._parse_grant_days_left(opp.get("deadline") or ""),
                "amount": opp.get("amount") or "Varies",
                "field": (opp.get("tags") or [focus])[0],
                "match_score": opp.get("matchScore") or 70,
                "url": opp.get("url") or "",
                # Already grounded in the real scraped listing text (see
                # fetch_industry_opportunities' own LLM extraction step) —
                # reused instead of firing a second LLM call per grant with
                # GRANT_ADVISOR_PROMPT_TEMPLATE for the same purpose.
                "rationale": opp.get("relevanceExplanation")
                or f"Aligned with your research track in {focus}.",
            }
            for i, opp in enumerate(funding_items)
        ]
        if scored_grants:
            try:
                await self._save_to_postgres(cache_key, {"items": scored_grants})
                print(
                    f"[Postgres Cache Save] match_grants for author_id={clean_id}",
                    flush=True,
                )
            except Exception as e:
                print(
                    f"[Postgres Cache Error] match_grants write failed: {e}", flush=True
                )
        if scored_grants:
            try:
                from firebase_admin import firestore as _fs

                await self._firestore_set_safe(
                    "match_grants",
                    clean_id,
                    {"items": scored_grants, "last_synced": _fs.SERVER_TIMESTAMP},
                )
            except Exception as e:
                print(
                    f"[Firestore Cache Error] match_grants write failed: {e}",
                    flush=True,
                )
        return scored_grants
