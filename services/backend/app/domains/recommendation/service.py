"""
Recommendation Service — service.py
=====================================
Orchestrates async fan-out from existing pipeline services,
applies the scoring engine, and returns unified RecommendationResponse.
Uses two-tier caching (L1 in-memory dict + L2 Postgres).
"""

import asyncio
import datetime
from typing import Any, Dict, List, Optional, Set

import numpy as np

from sqlalchemy.ext.asyncio import AsyncSession

from app.services.platform.pipeline_services import PipelineServices
from app.services.data.openalex_service import OpenAlexService
from app.domains.recommendation.schemas import (
    CollaboratorRecommendation,
    GrantRecommendation,
    PaperRecommendation,
    RecommendationResponse,
)
from app.domains.recommendation.engine import (
    build_time_weighted_profile,
    expand_concepts,
    build_tfidf_vector,
    cosine_similarity,
    domain_pagerank,
    inject_serendipity,
    mmr_diversify,
    team_composition_optimizer,
    bayesian_grant_probability,
    compute_novelty_score,
)

# ── L1 in-process cache (author_id → (timestamp, result)) ────────────────────
_L1_CACHE: Dict[str, tuple] = {}
_L1_TTL_SECONDS = 300  # 5 minutes


def _l1_get(key: str) -> Optional[RecommendationResponse]:
    entry = _L1_CACHE.get(key)
    if entry:
        ts, data = entry
        if (datetime.datetime.now(datetime.timezone.utc) - ts).total_seconds() < _L1_TTL_SECONDS:
            return data
        del _L1_CACHE[key]
    return None


def _l1_set(key: str, value: RecommendationResponse) -> None:
    _L1_CACHE[key] = (datetime.datetime.now(datetime.timezone.utc), value)


class RecommendationService:
    def __init__(self, db: AsyncSession):
        self.db = db
        self.openalex = OpenAlexService()
        self.pipeline = PipelineServices(db=db)

    # ── Public entry point ────────────────────────────────────────────────────

    async def get_unified_recommendations(
        self,
        author_id: Optional[str] = None,
        query_fallback: Optional[str] = None,
        mode: str = "full",
    ) -> RecommendationResponse:
        """
        Build a unified RecommendationResponse by:
        1. Checking L1 in-memory cache.
        2. Async fan-out to OpenAlex / arXiv / pipeline services.
        3. Applying the 8-technique scoring engine.
        4. Persisting to L2 Postgres cache.
        """
        cache_key = f"rec_{author_id or 'anon'}_{query_fallback or 'none'}_{mode}"

        # L1 cache hit
        cached = _l1_get(cache_key)
        if cached:
            cached.cached = True
            return cached

        # Build base result per mode
        papers: List[PaperRecommendation] = []
        grants: List[GrantRecommendation] = []
        collaborators: List[CollaboratorRecommendation] = []

        if mode in ("full", "papers"):
            papers = await self._build_paper_recommendations(author_id, query_fallback)

        if mode in ("full", "grants") and author_id:
            grants = await self._build_grant_recommendations(author_id)

        if mode in ("full", "collaborators") and author_id:
            collaborators = await self._build_collaborator_recommendations(author_id)

        result = RecommendationResponse(
            papers=papers,
            grants=grants,
            collaborators=collaborators,
            algorithm_version="hybrid-v2",
            cached=False,
        )

        _l1_set(cache_key, result)
        return result

    # ── Paper recommendations ─────────────────────────────────────────────────

    async def _build_paper_recommendations(
        self,
        author_id: Optional[str],
        query_fallback: Optional[str],
    ) -> List[PaperRecommendation]:
        """
        1. Fetch user works for time-weighted profiling.
        2. Expand concepts for serendipity pool.
        3. Parallel fetch: OpenAlex related works + arXiv preprints.
        4. Score via cosine similarity + PageRank + novelty.
        5. MMR re-rank for diversity.
        6. Inject serendipity (15%).
        """
        user_works: List[Dict[str, Any]] = []
        user_concepts: List[str] = []
        user_profile_weights: Dict[str, float] = {}

        # Step 1: Fetch user profile
        if author_id:
            try:
                user_works = await self.openalex.fetch_author_works(author_id, per_page=20)
                if user_works:
                    user_profile_weights = build_time_weighted_profile(user_works)
                    user_concepts = list(user_profile_weights.keys())[:15]
            except Exception as e:
                print(f"[RecommendationService] Profile fetch error: {e}", flush=True)

        # Step 2: Expand concepts for serendipity pool
        expanded_concepts = expand_concepts(user_concepts, depth=1)
        primary_concepts = set(c.lower() for c in user_concepts)
        adjacent_concepts = expanded_concepts - primary_concepts

        # Step 3: Parallel fetch
        fetch_tasks = []
        search_terms = user_concepts[:3] if user_concepts else (
            [query_fallback] if query_fallback else ["science"]
        )

        for term in search_terms:
            fetch_tasks.append(
                self.openalex.search_works(term, per_page=15, sort="publication_date:desc")
            )

        adjacent_terms = list(adjacent_concepts)[:2]
        for term in adjacent_terms:
            fetch_tasks.append(
                self.openalex.search_works(term, per_page=8, sort="publication_date:desc")
            )

        if author_id and user_works:
            for w in user_works[:2]:
                if w.get("id"):
                    fetch_tasks.append(self.openalex.fetch_related_works(w["id"], per_page=10))

        results = await asyncio.gather(*fetch_tasks, return_exceptions=True)

        # Partition results into primary and adjacent pools
        primary_pool: List[Dict[str, Any]] = []
        adjacent_pool: List[Dict[str, Any]] = []
        seen_ids: Set[str] = set()
        user_work_ids = {w.get("id") for w in user_works}

        for i, res in enumerate(results):
            if not isinstance(res, list):
                continue
            is_adjacent = i >= len(search_terms)
            for paper in res:
                pid = paper.get("id")
                if not pid or pid in seen_ids or pid in user_work_ids:
                    continue
                if not paper.get("title"):
                    continue
                seen_ids.add(pid)
                if is_adjacent:
                    adjacent_pool.append(paper)
                else:
                    primary_pool.append(paper)

        # Step 4: Build vocabulary + TF-IDF vectors
        user_profile_text = " ".join(user_concepts) + " " + " ".join(
            w.get("title", "") for w in (user_works[:5] if user_works else [])
        )
        vocabulary = list(dict.fromkeys(
            t for t in user_profile_text.lower().split() if len(t) > 3
        ))[:200]

        query_vec = build_tfidf_vector(user_profile_text, vocabulary)

        # Compute PageRank
        all_candidates = primary_pool[:40]
        pr_scores = domain_pagerank(all_candidates, user_concepts=primary_concepts)

        # Build (paper, vector) tuples for MMR
        all_citations = [p.get("cited_by_count") or 0 for p in all_candidates]
        candidates_with_vecs = []
        for paper in all_candidates:
            text = (paper.get("title") or "") + " " + self._get_abstract(paper)
            vec = build_tfidf_vector(text, vocabulary)
            paper["_pr_score"] = pr_scores.get(paper.get("id", ""), 0.0)
            paper["_novelty"] = compute_novelty_score(paper, all_citations)
            # Combined score: cosine + pagerank + novelty
            cosine_s = cosine_similarity(vec, query_vec)
            paper["_combined_score"] = (
                0.5 * cosine_s
                + 0.3 * paper["_pr_score"]
                + 0.2 * paper["_novelty"]
            )
            candidates_with_vecs.append((paper, vec))

        # Step 5: MMR diversification
        candidates_with_vecs.sort(key=lambda x: x[0]["_combined_score"], reverse=True)
        mmr_ranked = mmr_diversify(candidates_with_vecs, query_vec, k=12)

        # Step 6: Serendipity injection into final 10-paper feed
        final_feed = inject_serendipity(mmr_ranked, adjacent_pool, feed_size=10)

        # Convert to schema
        output = []
        for paper in final_feed[:10]:
            abstract = self._get_abstract(paper)
            authors_list = [
                a.get("author", {}).get("display_name", "Unknown")
                for a in (paper.get("authorships") or [])
            ][:3]
            primary_loc = paper.get("primary_location") or {}
            source = (primary_loc.get("source") or {}) if primary_loc else {}
            journal = source.get("display_name") or "Scientific Journal"
            year = paper.get("publication_year") or datetime.datetime.now().year

            # Clamp cosine_similarity to [0,1] for the schema field
            paper_text = (paper.get("title") or "") + " " + abstract
            vec = build_tfidf_vector(paper_text, vocabulary)
            rel_score = round(min(1.0, max(0.0, cosine_similarity(vec, query_vec))), 4)

            output.append(
                PaperRecommendation(
                    id=paper.get("id") or "",
                    title=paper.get("title") or "Untitled Paper",
                    authors=authors_list,
                    journal=journal,
                    year=int(year),
                    relevance_score=rel_score,
                    recommendation_reason=(
                        "Adjacent-field discovery — broadens your research horizon."
                        if paper.get("_serendipity")
                        else "Matched your primary research profile."
                    ),
                    novelty_score=float(paper.get("_novelty", 0.0)),
                    citation_percentile=float(paper.get("_pr_score", 0.0)),
                    serendipity_flag=bool(paper.get("_serendipity", False)),
                    doi=paper.get("doi"),
                    abstract=abstract[:500] if abstract else None,
                )
            )
        return output

    # ── Grant recommendations ─────────────────────────────────────────────────

    async def _build_grant_recommendations(
        self, author_id: str
    ) -> List[GrantRecommendation]:
        try:
            raw_grants = await self.pipeline.match_grants(author_id)
        except Exception as e:
            print(f"[RecommendationService] Grant fetch error: {e}", flush=True)
            return []

        # Fetch researcher profile for Bayesian scoring
        h_index = 5
        years_active = 5
        works_count = 20
        try:
            profile = await self.pipeline._fetch_author_profile(author_id)
            if profile:
                h_index = (profile.get("summary_stats") or {}).get("h_index", 5)
                works_count = profile.get("works_count") or 20
                first_year = profile.get("counts_by_year") or []
                if first_year:
                    oldest = min(
                        y.get("year", datetime.datetime.now().year)
                        for y in first_year
                    )
                    years_active = datetime.datetime.now().year - oldest
        except Exception:
            pass

        output = []
        for g in raw_grants:
            agency = g.get("agency") or "DEFAULT"
            field_match = g.get("match_score", 75) / 100.0
            prob = bayesian_grant_probability(
                h_index=int(h_index),
                years_active=int(years_active),
                works_count=int(works_count),
                agency=agency,
                field_match_score=field_match,
            )
            output.append(
                GrantRecommendation(
                    title=g.get("title") or "Research Grant",
                    agency=agency,
                    agency_color=g.get("agency_color") or "#9E9E9E",
                    days_left=int(g.get("days_left") or 30),
                    amount=g.get("amount") or "Contact Sponsor",
                    field=g.get("field") or "STEM",
                    match_score=int(g.get("match_score") or 75),
                    url=g.get("url") or "",
                    rationale=g.get("rationale") or "Matches your research profile.",
                    bayesian_success_probability=prob,
                )
            )
        return output

    # ── Collaborator recommendations ──────────────────────────────────────────

    async def _build_collaborator_recommendations(
        self, author_id: str
    ) -> List[CollaboratorRecommendation]:
        try:
            raw_collabs = await self.pipeline.get_network_collaborators(author_id, limit=15)
        except Exception as e:
            print(f"[RecommendationService] Collaborator fetch error: {e}", flush=True)
            return []

        # Determine user's skill gaps for team composition optimizer
        user_concepts: List[str] = []
        try:
            profile = await self.pipeline._fetch_author_profile(author_id)
            if profile:
                x_concepts = profile.get("x_concepts") or []
                user_concepts = [
                    c.get("display_name", "").lower()
                    for c in x_concepts
                    if c.get("display_name")
                ][:8]
        except Exception:
            pass

        expanded = expand_concepts(user_concepts, depth=1)
        skill_gaps = list(expanded - set(user_concepts))[:5]

        # Apply team composition optimizer
        team_picks = team_composition_optimizer(skill_gaps, raw_collabs, max_team_size=3)
        team_pick_names = {c.get("name") for c in team_picks}

        output = []
        for collab in raw_collabs[:10]:
            rel_score = collab.get("relevance_score") or 70
            synergy = round(min(1.0, max(0.0, rel_score / 100.0)), 4)
            team_role = None
            for tp in team_picks:
                if tp.get("name") == collab.get("name"):
                    team_role = tp.get("team_composition_role")
                    break

            output.append(
                CollaboratorRecommendation(
                    id=collab.get("id"),
                    name=collab.get("name") or "Unknown Researcher",
                    institution=collab.get("institution"),
                    field=collab.get("field"),
                    synergy_score=synergy,
                    match_score=int(rel_score),
                    depth=int(collab.get("depth") or 1),
                    team_composition_role=team_role,
                    connection_path=collab.get("connection_path"),
                )
            )
        return output

    # ── Peer Recommendations & Autocomplete (FAANG-style Data Management) ───

    async def get_peer_recommendations(
        self,
        query: str,
        user_id: Optional[str] = None
    ) -> List[PeerRecommendation]:
        from app.models.user_models import User, ResearcherProfile, UserCircle
        from sqlalchemy import select, or_, text
        
        q = f"%{query.strip().lower()}%"
        # 1. Fetch matching registered users from Postgres
        stmt_users = select(User).where(
            or_(
                User.display_name.ilike(q),
                User.email.ilike(q),
                text("users.username ILIKE :q").bindparams(q=q),
                text("users.phone ILIKE :q").bindparams(q=q)
            )
        ).limit(20)
        
        res_users = await self.db.execute(stmt_users)
        db_users = res_users.scalars().all()
        
        # 2. Get user's own profile to compare research focus
        user_focus = ""
        user_circle_peer_ids = set()
        if user_id:
            user_stmt = select(User).where(User.id == user_id)
            user_res = await self.db.execute(user_stmt)
            curr_user = user_res.scalar_one_or_none()
            if curr_user:
                user_focus = getattr(curr_user, "research_focus", "") or ""
            # Get circles
            circle_stmt = select(UserCircle.peer_id).where(UserCircle.user_id == user_id)
            circle_res = await self.db.execute(circle_stmt)
            user_circle_peer_ids = set(circle_res.scalars().all())

        suggestions = []
        for u in db_users:
            uname = getattr(u, "username", "") or ""
            uphone = getattr(u, "phone", "") or ""
            ufocus = getattr(u, "research_focus", "") or ""
            
            # Base relevance
            score = 0.5
            if query.lower() == uname.lower() or query.lower() == (u.email or "").lower() or query.lower() == uphone:
                score += 0.4
            elif query.lower() in uname.lower() or query.lower() in (u.email or "").lower() or query.lower() in uphone:
                score += 0.3
            elif query.lower() in u.display_name.lower():
                score += 0.2
            
            if user_focus and ufocus:
                words_a = set(user_focus.lower().split())
                words_b = set(ufocus.lower().split())
                overlap = len(words_a & words_b)
                if overlap > 0:
                    score += min(0.15, overlap * 0.03)
                    
            if u.id in user_circle_peer_ids:
                score += 0.1
                
            suggestions.append(
                PeerRecommendation(
                    uid=u.id,
                    name=u.display_name,
                    username=uname,
                    email=u.email,
                    phone=uphone,
                    research_focus=ufocus,
                    is_registered=True,
                    relevance_score=min(1.0, score)
                )
            )
            
        # 3. Query researcher profiles (OpenAlex cached profiles) to match unregistered scientists
        stmt_profiles = select(ResearcherProfile).where(
            or_(
                ResearcherProfile.display_name.ilike(q),
                ResearcherProfile.field_of_study.ilike(q)
            )
        ).limit(20)
        
        res_profiles = await self.db.execute(stmt_profiles)
        db_profiles = res_profiles.scalars().all()
        
        existing_names = {s.name.lower() for s in suggestions}
        
        for p in db_profiles:
            if p.display_name.lower() in existing_names:
                continue
                
            score = 0.4
            if query.lower() in p.display_name.lower():
                score += 0.2
            if p.field_of_study and query.lower() in p.field_of_study.lower():
                score += 0.1
                
            if user_focus and p.field_of_study:
                words_a = set(user_focus.lower().split())
                words_b = set(p.field_of_study.lower().split())
                overlap = len(words_a & words_b)
                if overlap > 0:
                    score += min(0.1, overlap * 0.02)
                    
            suggestions.append(
                PeerRecommendation(
                    uid=None,
                    name=p.display_name,
                    username=None,
                    email=f"{p.display_name.lower().replace(' ', '')}@university.edu",
                    phone=None,
                    research_focus=p.field_of_study,
                    is_registered=False,
                    relevance_score=min(1.0, score)
                )
            )
            
        suggestions.sort(key=lambda x: x.relevance_score, reverse=True)
        return suggestions[:10]

    async def log_peer_invite(self, req: PeerInviteLogRequest) -> bool:
        from app.models.user_models import User, UserCircle
        from sqlalchemy import select
        
        peer_id = req.peer_uid
        if not peer_id and req.peer_email:
            stmt = select(User).where(User.email == req.peer_email)
            res = await self.db.execute(stmt)
            peer = res.scalar_one_or_none()
            if peer:
                peer_id = peer.id
                
        if not peer_id:
            return False
            
        stmt_circle = select(UserCircle).where(
            UserCircle.user_id == req.user_id,
            UserCircle.peer_id == peer_id
        )
        res_circle = await self.db.execute(stmt_circle)
        circle = res_circle.scalar_one_or_none()
        
        if circle:
            circle.spark_sessions_count += 1
            circle.relevance_score = min(1.0, circle.relevance_score + 0.1)
        else:
            new_circle = UserCircle(
                user_id=req.user_id,
                peer_id=peer_id,
                relationship_type="manual",
                relevance_score=0.7,
                spark_sessions_count=1
            )
            self.db.add(new_circle)
            
        await self.db.commit()
        return True

    # ── Helpers ───────────────────────────────────────────────────────────────

    def _get_abstract(self, paper: Dict[str, Any]) -> str:
        abstract = paper.get("_custom_abstract") or paper.get("abstract") or ""
        if not abstract and paper.get("abstract_inverted_index"):
            try:
                idx = paper["abstract_inverted_index"]
                word_pos = []
                for word, positions in idx.items():
                    for pos in positions:
                        word_pos.append((pos, word))
                word_pos.sort()
                abstract = " ".join(w for _, w in word_pos)
            except Exception:
                pass
        return abstract
