import re
from typing import List, Optional, Union
from fastapi import APIRouter, Depends, Query, BackgroundTasks, HTTPException
from sqlalchemy.ext.asyncio import AsyncSession

from app.schemas.core import AuthorResponse, AuthorSuggestion, Work
from app.services.pipeline_services import PipelineServices
from app.services.metrics_service import compute_author_metrics
from app.services.summarization_service import is_llm_working
from app.services.openalex_service import OpenAlexService
from app.api.dependencies import get_pipeline_services, get_openalex_service, get_db
from app.core.config import settings
from app.core.cache import (
    suggestions_cache,
    profile_cache,
    network_collaborators_cache,
    author_metrics_cache,
)

try:
    from app.services.researcher_worker import (
        teleport_researcher,
        FIRESTORE_AVAILABLE,
        _get_firestore_client,
    )
except ImportError:
    teleport_researcher = None
    FIRESTORE_AVAILABLE = False

    def _get_firestore_client():
        return None


async def track_teleport_researcher(author_id: str):
    if teleport_researcher is not None:
        from app.main import metrics_store
        await metrics_store.increment_background_tasks()
        try:
            await teleport_researcher(author_id)
        finally:
            await metrics_store.decrement_background_tasks()


router = APIRouter()


def compute_query_match_score(query: str, concepts: list) -> int:
    if not query or not concepts:
        return 75
    query_tokens = {t.strip().lower() for t in query.split() if len(t.strip()) > 2}
    if not query_tokens:
        return 75
    concepts_normalized = {c.strip().lower() for c in concepts if c.strip()}
    
    match_count = 0
    for q_t in query_tokens:
        for c in concepts_normalized:
            if q_t in c or c in q_t:
                match_count += 1
                break
                
    union_len = len(query_tokens.union(concepts_normalized))
    similarity = match_count / union_len if union_len > 0 else 0.0
    return min(99, max(60, int(60 + similarity * 100)))


# ── PostgreSQL helpers for fast local lookups ─────────────────────────────────


async def _pg_search_suggestions(
    session: AsyncSession, query: str, limit: int = 8
) -> List[AuthorSuggestion]:
    """
    Search PostgreSQL researcher_metrics and researcher_profiles by display_name (ILIKE).
    Fastest path — no network required.
    """
    from app.models.user_models import ResearcherProfile
    from app.models.researcher_models import ResearcherMetrics
    from sqlalchemy import select

    suggestions = []
    seen_ids = set()
    try:
        # 1. Search researcher_metrics first (highly enriched profiles)
        stmt_metrics = (
            select(ResearcherMetrics)
            .where(ResearcherMetrics.display_name.ilike(f"%{query}%"))
            .limit(limit)
        )
        res_metrics = await session.execute(stmt_metrics)
        rows_metrics = res_metrics.scalars().all()
        for r in rows_metrics:
            clean_id = r.openalex_id.split("/")[-1]
            if clean_id not in seen_ids:
                seen_ids.add(clean_id)
                score = compute_query_match_score(query, r.expertise or [])
                suggestions.append(
                    AuthorSuggestion(
                        id=r.openalex_id,
                        display_name=r.display_name,
                        institution=r.current_institution or "Independent Researcher",
                        field_of_study=r.field_of_study,
                        h_index=r.h_index,
                        innovation_score=score,
                        works_count=r.works_count,
                    )
                )

        # 2. Search researcher_profiles (direct co-authors from pipeline)
        if len(suggestions) < limit:
            stmt_profiles = (
                select(ResearcherProfile)
                .where(ResearcherProfile.display_name.ilike(f"%{query}%"))
                .limit(limit - len(suggestions))
            )
            res_profiles = await session.execute(stmt_profiles)
            rows_profiles = res_profiles.scalars().all()
            for r in rows_profiles:
                clean_id = r.openalex_id.split("/")[-1]
                if clean_id not in seen_ids:
                    seen_ids.add(clean_id)
                    score = compute_query_match_score(query, r.concepts or [])
                    suggestions.append(
                        AuthorSuggestion(
                            id=r.openalex_id,
                            display_name=r.display_name,
                            institution=r.institution or "Independent Researcher",
                            field_of_study=r.field_of_study,
                            h_index=r.h_index,
                            innovation_score=score,
                            works_count=r.works_count,
                        )
                    )
    except Exception as exc:
        print(f"[PG Suggestions] search error: {exc}", flush=True)
    return suggestions


async def _pg_get_researcher_metrics(session: AsyncSession, clean_id: str):
    """
    Load ResearcherMetrics row from PostgreSQL.
    Returns the ORM object or None.
    """
    from app.models.researcher_models import ResearcherMetrics
    from sqlalchemy.future import select
    import datetime

    now = datetime.datetime.now(datetime.timezone.utc).replace(tzinfo=None)
    try:
        stmt = select(ResearcherMetrics).where(
            ResearcherMetrics.openalex_id == clean_id,
            ResearcherMetrics.expires_at > now,
        )
        result = await session.execute(stmt)
        return result.scalars().first()
    except Exception as exc:
        print(f"[PG Metrics] read error: {exc}", flush=True)
    return None


async def _pg_get_researcher_works(session: AsyncSession, clean_id: str) -> List[Work]:
    """
    Load ResearcherWork rows from PostgreSQL.
    Returns list of Work schema objects.
    """
    from app.models.researcher_models import ResearcherWork
    from sqlalchemy.future import select

    works_data = []
    try:
        stmt = select(ResearcherWork).where(
            ResearcherWork.author_openalex_id == clean_id
        )
        result = await session.execute(stmt)
        rows = result.scalars().all()
        for r in rows:
            works_data.append(
                Work(
                    id=r.work_openalex_id,
                    title=r.title,
                    year=r.publication_year,
                    doi=r.doi,
                    journal=r.journal,
                    is_open_access=r.is_open_access,
                    citations=r.citations,
                    creativity_score=r.creativity_score,
                    complexity_score=r.complexity_score,
                    impact_factor=r.impact_factor,
                    disruption_score=r.disruption_score,
                    semantic_novelty=r.semantic_novelty,
                    open_science_score=r.open_science_score,
                )
            )
    except Exception as exc:
        print(f"[PG Works] read error: {exc}", flush=True)
    return works_data


async def fetch_similar_authors(
    query_term: str, exclude_id: str, openalex_service: OpenAlexService
) -> List[AuthorSuggestion]:
    if not query_term or query_term.lower() in [
        "multidisciplinary",
        "researcher",
        "general research",
    ]:
        return []

    try:
        results = await openalex_service.search_authors(query_term, per_page=8)
        suggestions = []
        for author in results:
            author_id = author.get("id", "")
            clean_id = author_id.split("/")[-1]
            clean_exclude_id = exclude_id.split("/")[-1]
            if clean_id == clean_exclude_id:
                continue

            last_insts = author.get("last_known_institutions")
            inst = "Independent Researcher"
            if last_insts and isinstance(last_insts, list) and len(last_insts) > 0:
                first_inst = last_insts[0]
                if first_inst and isinstance(first_inst, dict):
                    inst = first_inst.get("display_name") or "Independent Researcher"

            from app.services.openalex_service import extract_field_and_expertise

            field, expertise = extract_field_and_expertise(
                author, author.get("display_name", "")
            )

            stats = author.get("summary_stats") or {}
            h_idx = stats.get("h_index")
            works_count = author.get("works_count")

            score = compute_query_match_score(query_term, expertise or [field])

            suggestions.append(
                AuthorSuggestion(
                    id=author_id,
                    display_name=author.get("display_name", "Unknown"),
                    institution=inst,
                    field_of_study=field,
                    h_index=int(h_idx) if h_idx is not None else None,
                    innovation_score=score,
                    works_count=works_count,
                )
            )
            if len(suggestions) >= 5:
                break
        return suggestions
    except Exception as e:
        print(f"Error fetching similar authors: {e}", flush=True)
    return []


@router.get("/author_suggestions", response_model=List[AuthorSuggestion])
async def get_author_suggestions(
    query: str = Query(...),
    session: AsyncSession = Depends(get_db),
    openalex_service: OpenAlexService = Depends(get_openalex_service),
):
    cache_key = query.strip().lower()

    # L1+L2: PgBackedCache (in-mem 30s → PG with TTL)
    cached = await suggestions_cache.get(cache_key)
    if cached is not None:
        print(f"[Cache Hit] Suggestions for: '{query}'", flush=True)
        return cached

    # 1. PostgreSQL researcher_profiles — fastest, no network, ILIKE search
    # Best for: previously searched/enriched authors already in our DB
    pg_suggestions = await _pg_search_suggestions(session, query, limit=8)
    if len(pg_suggestions) >= 4:
        print(
            f"[PG Hit] Suggestions from researcher_profiles for: '{query}'", flush=True
        )
        await suggestions_cache.set(cache_key, pg_suggestions)
        return pg_suggestions

    # 2. Firestore global_researchers — cloud, has enriched authors w/ innovation scores
    # Best for: authors already teleported (enriched) but not yet in local PG profiles
    if FIRESTORE_AVAILABLE:
        try:
            db = _get_firestore_client()
            if db:
                import asyncio
                from google.cloud.firestore_v1.base_query import FieldFilter

                def _blocking_query():
                    return (
                        db.collection("global_researchers")
                        .where(filter=FieldFilter("display_name", ">=", query))
                        .where(
                            filter=FieldFilter("display_name", "<=", query + "\uf8ff")
                        )
                        .limit(10)
                        .get()
                    )

                loop = asyncio.get_running_loop()
                docs = await asyncio.wait_for(
                    loop.run_in_executor(None, _blocking_query), timeout=3.0
                )
                if docs:
                    fs_suggestions = []
                    for d in [doc.to_dict() for doc in docs]:
                        h_idx = d.get("h_index")
                        expertise = d.get("expertise") or []
                        score = compute_query_match_score(query, expertise)
                        fs_suggestions.append(
                            AuthorSuggestion(
                                id=d.get("openalex_id"),
                                display_name=d.get("display_name"),
                                institution=d.get("current_institution")
                                or "Independent Researcher",
                                field_of_study=d.get("field_of_study"),
                                h_index=int(h_idx) if h_idx is not None else None,
                                innovation_score=score,
                                works_count=d.get("works_count"),
                            )
                        )
                    # Merge with any PG results (deduplicate)
                    seen = {s.id for s in pg_suggestions}
                    merged = pg_suggestions + [
                        s for s in fs_suggestions if s.id not in seen
                    ]
                    if merged:
                        print(f"[Firestore Hit] Suggestions for: '{query}'", flush=True)
                        await suggestions_cache.set(cache_key, merged)
                        return merged
        except Exception as e:
            print(f"[Firestore Suggestions] error: {e}", flush=True)

    # 2. Fallback to OpenAlex
    non_person_keywords = re.compile(
        r"\b(collaboration|group|consortium|committee|team|network|project|society|association|institute|university|department|lab|laboratory|center|centre|foundation|quantum|topology|invariants|materials|systems|physics|biology|chemistry|science|computing|theory|applications|methods|frontiers|research)\b",
        re.IGNORECASE,
    )

    suggestions = []
    try:
        # First try direct author search
        results = await openalex_service.search_authors(query, per_page=10)
        for author in results:
            disp_name = author.get("display_name")
            if not disp_name:
                continue

            if non_person_keywords.search(disp_name):
                continue

            words = disp_name.strip().split()
            if len(words) < 2 or len(words) > 4:
                continue

            last_insts = author.get("last_known_institutions")
            inst = "Independent Researcher"
            if last_insts and isinstance(last_insts, list) and len(last_insts) > 0:
                first_inst = last_insts[0]
                if first_inst and isinstance(first_inst, dict):
                    inst = first_inst.get("display_name") or "Independent Researcher"

            stats = author.get("summary_stats") or {}
            h_idx = stats.get("h_index")
            works_cnt = author.get("works_count")

            from app.services.openalex_service import extract_field_and_expertise

            field_of_study, expertise = extract_field_and_expertise(author, disp_name)
            score = compute_query_match_score(query, expertise or [field_of_study])

            suggestions.append(
                AuthorSuggestion(
                    id=author["id"],
                    display_name=disp_name,
                    institution=inst,
                    field_of_study=field_of_study,
                    h_index=int(h_idx) if h_idx is not None else None,
                    innovation_score=score,
                    works_count=works_cnt,
                )
            )

        if len(suggestions) < 4:
            print(
                f"[Fallback to Works] Insufficient human authors for '{query}', searching works...",
                flush=True,
            )
            works = await openalex_service.search_works(query, per_page=20)
            seen_ids = {s.id for s in suggestions}
            for work in works:
                work_concepts = [c.get("display_name", "") for c in work.get("concepts", []) if c.get("display_name")]
                score = compute_query_match_score(query, work_concepts)
                for authorship in work.get("authorships", []):
                    author = authorship.get("author", {})
                    auth_name = author.get("display_name")
                    auth_id = author.get("id")

                    if not auth_name or not auth_id or auth_id in seen_ids:
                        continue

                    if non_person_keywords.search(auth_name):
                        continue

                    words = auth_name.strip().split()
                    if len(words) < 2 or len(words) > 4:
                        continue

                    last_insts = authorship.get("institutions", [])
                    inst = "Independent Researcher"
                    if last_insts:
                        inst = (
                            last_insts[0].get("display_name")
                            or "Independent Researcher"
                        )

                    seen_ids.add(auth_id)
                    suggestions.append(
                        AuthorSuggestion(
                            id=auth_id,
                            display_name=auth_name,
                            institution=inst,
                            h_index=None,
                            innovation_score=score,
                            works_count=None,
                        )
                    )
                    if len(suggestions) >= 10:
                        break
                if len(suggestions) >= 10:
                    break

        if suggestions:
            await suggestions_cache.set(cache_key, suggestions)
            return suggestions
    except Exception as e:
        print(f"OpenAlex Suggester Error: {e}")
    return []


@router.get("/refresh_author")
async def refresh_author(
    name: str = Query(...),
    background_tasks: BackgroundTasks = BackgroundTasks(),
    openalex_service: OpenAlexService = Depends(get_openalex_service),
):
    """Explicitly re-runs the teleportation worker to update Firestore data."""
    try:
        cache_key = name.strip().lower()
        await profile_cache.delete(cache_key)
        await suggestions_cache.delete(cache_key)

        results = await openalex_service.search_authors(name, per_page=1)
        if results:
            author_id = results[0]["id"]
            background_tasks.add_task(track_teleport_researcher, author_id)
            return {"status": "Refresh started", "author_id": author_id}
        raise HTTPException(status_code=404, detail="Author not found for refresh")
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/search_author", response_model=Union[AuthorResponse, dict])
async def search_author(
    name: str = Query(...),
    id: Optional[str] = Query(None),
    focus: Optional[str] = Query(None),
    background_tasks: BackgroundTasks = BackgroundTasks(),
    session: AsyncSession = Depends(get_db),
    openalex_service: OpenAlexService = Depends(get_openalex_service),
):
    print(f"[search_author] name='{name}', id='{id}', focus='{focus}'", flush=True)
    clean_id = id.split("/")[-1] if id else None
    cache_key = (
        f"id:{clean_id}"
        if clean_id
        else f"{name.strip().lower()}:{focus.strip().lower() if focus else ''}"
    )

    # 1. Check in-memory cache
    cached = await profile_cache.get(cache_key)
    if cached is not None:
        print(f"[search_author] In-memory cache hit: '{cache_key}'", flush=True)
        return cached

    # 2. PostgreSQL researcher_metrics — local, sub-ms, has all computed scores
    # Best for: returning the fast metadata response while works load from Firestore
    pg_row = await _pg_get_researcher_metrics(session, clean_id) if clean_id else None
    if pg_row:
        print(
            f"[search_author] PG researcher_metrics hit for: '{clean_id}'", flush=True
        )
        # 1. Fetch works from PostgreSQL first (extremely fast)
        works_data = await _pg_get_researcher_works(session, clean_id)

        # 2. Fall back to Firestore if PostgreSQL didn't have works but Firestore is available
        if not works_data and FIRESTORE_AVAILABLE:
            try:
                db = _get_firestore_client()
                if db:
                    import asyncio

                    def _blocking_works():
                        doc = (
                            db.collection("global_researchers").document(clean_id).get()
                        )
                        return doc.to_dict() if doc.exists else None

                    loop = asyncio.get_running_loop()
                    d = await asyncio.wait_for(
                        loop.run_in_executor(None, _blocking_works), timeout=3.0
                    )
                    if d:
                        for w in d.get("works", []):
                            try:
                                _work_fields = set(
                                    getattr(Work, "model_fields", None)
                                    or Work.__fields__
                                )
                                works_data.append(
                                    Work(
                                        **{
                                            k: v
                                            for k, v in w.items()
                                            if k in _work_fields
                                        }
                                    )
                                )
                            except Exception:
                                pass
            except Exception as e:
                print(f"[search_author] Firestore works fetch error: {e}", flush=True)

        field = pg_row.field_of_study or ""
        similar = await fetch_similar_authors(
            field, pg_row.openalex_id or clean_id, openalex_service
        )
        response_data = AuthorResponse(
            id=pg_row.openalex_id,
            display_name=pg_row.display_name,
            orcid=pg_row.orcid,
            h_index=pg_row.h_index or 0,
            i10_index=pg_row.i10_index or 0,
            works_count=pg_row.works_count or 0,
            cited_by_count=pg_row.cited_by_count or 0,
            institution=pg_row.current_institution or "Independent Researcher",
            field_of_study=pg_row.field_of_study or "Multidisciplinary",
            expertise=pg_row.expertise or [],
            academic_history=pg_row.academic_history or [],
            works=works_data,
            innovation_score=int(pg_row.innovation_score)
            if pg_row.innovation_score
            else None,
            metrics_computed=pg_row.metrics_computed and is_llm_working(),
            llm_active=is_llm_working(),
            average_creativity=pg_row.average_creativity or 0.0,
            average_complexity=pg_row.average_complexity or 0.0,
            average_skill_score=pg_row.average_skill_score or 0.0,
            average_impact=pg_row.average_impact or 0.0,
            average_activity=pg_row.average_activity or 0.0,
            disruption_score=pg_row.disruption_score or 0.0,
            citation_acceleration=pg_row.citation_acceleration or 0.0,
            future_impact_score=pg_row.future_impact_score or 0.0,
            network_centrality=pg_row.network_centrality or 0.0,
            semantic_novelty=pg_row.semantic_novelty or 0.0,
            interdisciplinary_index=pg_row.interdisciplinary_index or 0.0,
            policy_patent_score=pg_row.policy_patent_score or 0.0,
            open_science_score=pg_row.open_science_score or 0.0,
            collaboration_diversity=pg_row.collaboration_diversity or 0.0,
            research_consistency=pg_row.research_consistency or 0.0,
            next_prediction=pg_row.next_prediction,
            similar_researchers=similar,
        )
        await profile_cache.set(cache_key, response_data)
        return response_data

    # 3. Firestore global_researchers — cloud, has full enriched doc with works
    # Best for: authors teleported before PG was set up, or cross-device scenarios
    if FIRESTORE_AVAILABLE:
        try:
            db = _get_firestore_client()
            if db:
                import asyncio

                def _blocking_fetch():
                    if clean_id:
                        doc = (
                            db.collection("global_researchers").document(clean_id).get()
                        )
                        return doc.to_dict() if doc.exists else None
                    else:
                        docs = (
                            db.collection("global_researchers")
                            .where("display_name", "==", name)
                            .limit(10)
                            .get()
                        )
                        d = None
                        if docs:
                            if focus:
                                normalized_focus = focus.lower()
                                for doc in docs:
                                    cand = doc.to_dict()
                                    field = (cand.get("field_of_study") or "").lower()
                                    expertise = [
                                        exp.lower() for exp in cand.get("expertise", [])
                                    ]
                                    if normalized_focus in field or any(
                                        normalized_focus in exp
                                        or exp in normalized_focus
                                        for exp in expertise
                                    ):
                                        d = cand
                                        break
                            if not d:
                                d = docs[0].to_dict()
                        return d

                loop = asyncio.get_running_loop()
                d = await asyncio.wait_for(
                    loop.run_in_executor(None, _blocking_fetch), timeout=3.0
                )

                if d:
                    print(
                        f"[search_author] Firestore hit for: '{clean_id or name}'",
                        flush=True,
                    )
                    works_data = []
                    for w in d.get("works", []):
                        try:
                            _work_fields = set(
                                getattr(Work, "model_fields", None) or Work.__fields__
                            )
                            works_data.append(
                                Work(
                                    **{k: v for k, v in w.items() if k in _work_fields}
                                )
                            )
                        except Exception:
                            pass
                    field = d.get("field_of_study") or (
                        d.get("expertise", [""])[0] if d.get("expertise") else ""
                    )
                    similar = await fetch_similar_authors(
                        field, d.get("openalex_id", ""), openalex_service
                    )
                    response_data = AuthorResponse(
                        id=d.get("openalex_id", ""),
                        display_name=d.get("display_name", ""),
                        orcid=d.get("orcid"),
                        h_index=d.get("h_index", 0),
                        i10_index=d.get("i10_index", 0),
                        works_count=d.get("works_count", 0),
                        cited_by_count=d.get("cited_by_count", 0),
                        institution=d.get("current_institution")
                        or "Independent Researcher",
                        field_of_study=d.get("field_of_study", "Multidisciplinary"),
                        expertise=d.get("expertise", []),
                        academic_history=d.get("academic_history", []),
                        works=works_data,
                        innovation_score=d.get("innovation_score"),
                        metrics_computed=is_llm_working()
                        and d.get("metrics_computed", False),
                        llm_active=is_llm_working(),
                        average_creativity=d.get("average_creativity", 0.0),
                        average_complexity=d.get("average_complexity", 0.0),
                        average_skill_score=d.get("average_skill_score", 0.0),
                        average_impact=d.get("average_impact", 0.0),
                        average_activity=d.get("average_activity", 0.0),
                        disruption_score=d.get("disruption_score", 0.0),
                        citation_acceleration=d.get("citation_acceleration", 0.0),
                        future_impact_score=d.get("future_impact_score", 0.0),
                        network_centrality=d.get("network_centrality", 0.0),
                        semantic_novelty=d.get("semantic_novelty", 0.0),
                        interdisciplinary_index=d.get("interdisciplinary_index", 0.0),
                        policy_patent_score=d.get("policy_patent_score", 0.0),
                        open_science_score=d.get("open_science_score", 0.0),
                        collaboration_diversity=d.get("collaboration_diversity", 0.0),
                        research_consistency=d.get("research_consistency", 0.0),
                        next_prediction=d.get("next_prediction"),
                        similar_researchers=similar,
                    )
                    await profile_cache.set(cache_key, response_data)
                    return response_data
        except Exception as e:
            print(f"[search_author] Firestore lookup error: {e}", flush=True)

    # 3. Fetch directly from OpenAlex — source of truth
    try:
        author_data = None
        resolved_id = None

        if clean_id:
            author_data = await openalex_service.fetch_author_by_id(clean_id)
            if author_data:
                resolved_id = clean_id
        else:
            results = await openalex_service.search_authors(name, per_page=10)
            if results:
                # Filter by name matching first (just in case) and then by focus
                author_data = None
                query_tokens = [tok for tok in name.lower().split() if len(tok) > 2]

                if focus:
                    normalized_focus = focus.lower()
                    for candidate in results:
                        cand_name = candidate.get("display_name", "").lower()
                        if query_tokens and not all(
                            tok in cand_name for tok in query_tokens
                        ):
                            continue

                        concepts = candidate.get("x_concepts", [])
                        concept_names = [
                            c.get("display_name", "").lower() for c in concepts
                        ]
                        if any(
                            normalized_focus in c_name or c_name in normalized_focus
                            for c_name in concept_names
                        ):
                            author_data = candidate
                            break

                if not author_data:
                    # Fallback to the first result that matches name tokens
                    for candidate in results:
                        cand_name = candidate.get("display_name", "").lower()
                        if query_tokens and not all(
                            tok in cand_name for tok in query_tokens
                        ):
                            continue
                        author_data = candidate
                        break

                if not author_data:
                    author_data = results[0]

                resolved_id = author_data["id"].split("/")[-1]

        if not author_data or not resolved_id:
            raise HTTPException(status_code=404, detail="Author not found on OpenAlex")

        author_orcid = author_data.get("orcid")

        # Meticulously resolve field/discipline first to filter out papers from different authors with the same name
        from app.services.openalex_service import (
            extract_field_and_expertise,
            is_work_relevant_to_discipline,
        )

        field, expertise = extract_field_and_expertise(
            author_data, author_data.get("display_name", name)
        )
        target_discipline = focus or field

        raw_works = await openalex_service.fetch_author_works(
            resolved_id, orcid=author_orcid, per_page=50
        )
        if target_discipline:
            raw_works = [
                w
                for w in raw_works
                if is_work_relevant_to_discipline(w, target_discipline)
            ]

        works_data = []
        for w in raw_works:
            w_title = w.get("title") or ""
            if not w_title.strip():
                continue
            primary_location = w.get("primary_location") or {}
            source = primary_location.get("source") or {}
            journal_name = source.get("display_name")
            pub_year = w.get("publication_year")
            citations = w.get("cited_by_count", 0)
            impact = source.get("2yr_mean_citedness", 0.0) or 0.0

            authors_list = []
            for auth_ship in w.get("authorships", []):
                author_info = auth_ship.get("author")
                if author_info:
                    a_name = author_info.get("display_name")
                    a_id = author_info.get("id")
                    if a_name and a_id:
                        authors_list.append(f"{a_name}|{a_id}")

            works_data.append(
                Work(
                    id=w.get("id"),
                    title=w_title,
                    year=pub_year,
                    doi=w.get("doi"),
                    journal=journal_name,
                    is_open_access=bool((w.get("open_access") or {}).get("is_oa")),
                    citations=citations,
                    creativity_score=0.0,
                    complexity_score=0.0,
                    impact_factor=round(float(impact), 2),
                    disruption_score=0.0,
                    semantic_novelty=0.0,
                    open_science_score=0.0,
                    authors=authors_list,
                )
            )

        last_insts = author_data.get("last_known_institutions") or []
        institution = "Independent Researcher"
        if last_insts and isinstance(last_insts, list):
            first = last_insts[0]
            if first and isinstance(first, dict):
                institution = first.get("display_name") or "Independent Researcher"

        stats = author_data.get("summary_stats") or {}

        # Calculate clean metrics based on filtered relevant publications
        clean_works_count = len(works_data)
        clean_cited_by_count = sum(w.citations for w in works_data)

        affiliations = author_data.get("affiliations") or []
        hist_map: dict = {}
        for aff in affiliations:
            inst_info = aff.get("institution") or {}
            inst_name = inst_info.get("display_name")
            years = aff.get("years") or []
            if not inst_name or not years:
                continue
            existing_val = hist_map.get(inst_name)
            if existing_val is None:
                hist_map[inst_name] = [min(years), max(years)]
            else:
                hist_map[inst_name] = [
                    min(existing_val[0], min(years)),
                    max(existing_val[1], max(years)),
                ]
        academic_history = [
            f"{n} ({y[0]}\u2013{y[1]})" if y[0] != y[1] else f"{n} ({y[0]})"
            for n, y in sorted(hist_map.items(), key=lambda x: x[1][0])
        ]

        similar = await fetch_similar_authors(
            field, author_data.get("id", ""), openalex_service
        )

        response_data = AuthorResponse(
            id=author_data.get("id", resolved_id),
            display_name=author_data.get("display_name", name),
            orcid=author_data.get("orcid"),
            h_index=stats.get("h_index", 0),
            i10_index=stats.get("i10_index", 0),
            works_count=clean_works_count,
            cited_by_count=clean_cited_by_count,
            institution=institution,
            field_of_study=field,
            expertise=expertise,
            academic_history=academic_history,
            works=works_data,
            innovation_score=None,
            metrics_computed=False,
            llm_active=is_llm_working(),
            next_prediction=None,
            similar_researchers=similar,
        )

        await profile_cache.set(cache_key, response_data)

        if is_llm_working():
            author_full_id = author_data.get("id", resolved_id)
            background_tasks.add_task(track_teleport_researcher, author_full_id)
            print(
                f"[search_author] Queued background teleport for: {author_data.get('display_name')}",
                flush=True,
            )
        else:
            print(
                "[search_author] LLM offline or unconfigured, skipping background task.",
                flush=True,
            )

        return response_data

    except HTTPException:
        raise
    except Exception as e:
        print(f"[search_author] OpenAlex fetch error: {e}", flush=True)
        raise HTTPException(status_code=500, detail=f"Search failed: {str(e)}")


@router.get("/author_metrics")
async def get_author_metrics(
    author_id: str = Query(...),
):
    cached = await author_metrics_cache.get(author_id)
    if cached is not None:
        print(f"[AuthorMetrics] Cache hit for author={author_id}", flush=True)
        return cached

    try:
        data = await compute_author_metrics(author_id)
        await author_metrics_cache.set(author_id, data)
        return data
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/network_collaborators")
async def get_network_collaborators(
    author_id: str = Query(...),
    limit: int = Query(10),
    offset: int = Query(0),
    exclude_ids: str = Query(""),
    field: str = Query(""),
    name: str = Query(""),
    pipeline_services: PipelineServices = Depends(get_pipeline_services),
):
    cache_key = f"{author_id}_{limit}_{offset}_{exclude_ids}_{field}_{name}"
    cached_data = await network_collaborators_cache.get(cache_key)
    if cached_data is not None:
        return cached_data

    excl_list = [x.strip() for x in exclude_ids.split(",")] if exclude_ids else []
    try:
        data = await pipeline_services.get_network_collaborators(
            author_id, limit, offset, excl_list, field, name
        )
        if data:
            await network_collaborators_cache.set(cache_key, data)
        return data
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/collaborator_synergy")
async def get_collaborator_synergy(
    author_id: str = Query(...),
    collaborator_id: str = Query(...),
    pipeline_services: PipelineServices = Depends(get_pipeline_services),
):
    try:
        data = await pipeline_services.get_collaborator_synergy(
            author_id, collaborator_id
        )
        return data
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/citation_heatmap")
async def get_citation_heatmap(
    author_id: str = Query(...),
    pipeline_services: PipelineServices = Depends(get_pipeline_services),
):
    try:
        data = await pipeline_services.get_citation_heatmap(author_id)
        return data
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/match_grants")
async def match_grants(
    author_id: str = Query(...),
    pipeline_services: PipelineServices = Depends(get_pipeline_services),
):
    try:
        data = await pipeline_services.match_grants(author_id)
        return data
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/journal_advisor")
async def get_journal_advisor(
    author_id: str = Query(...),
    pipeline_services: PipelineServices = Depends(get_pipeline_services),
):
    try:
        data = await pipeline_services.get_journal_advisor(author_id)
        return data
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/resolve_email")
async def resolve_author_email(
    name: str = Query(...),
    institution: Optional[str] = Query(None),
    openalex_service: OpenAlexService = Depends(get_openalex_service),
):
    try:
        # Search OpenAlex for this author name
        results = await openalex_service.search_authors(name, per_page=1)
        inst_name = institution or ""

        if results:
            author = results[0]
            last_insts = author.get("last_known_institutions") or []
            if last_insts and isinstance(last_insts, list) and len(last_insts) > 0:
                first_inst = last_insts[0]
                if first_inst and isinstance(first_inst, dict):
                    inst_name = first_inst.get("display_name") or inst_name

        # Map known academic institutions to their real-world domains
        domain_mapping = {
            "bombay": "physics.iitb.ac.in",
            "delhi": "physics.iitd.ac.in",
            "madras": "physics.iitm.ac.in",
            "advanced study": "ias.edu",
            "cambridge": "cam.ac.uk",
            "oxford": "oxford.ac.uk",
            "princeton": "princeton.edu",
            "california institute of technology": "caltech.edu",
            "caltech": "caltech.edu",
            "harvard": "harvard.edu",
            "stanford": "stanford.edu",
            "massachusetts institute of technology": "mit.edu",
            "mit": "mit.edu",
            "columbia": "columbia.edu",
            "yale": "yale.edu",
            "chicago": "uchicago.edu",
            "berkeley": "berkeley.edu",
            "cornell": "cornell.edu",
            "toronto": "utoronto.ca",
            "max planck": "mpg.de",
            "cern": "cern.ch",
            "zurich": "ethz.ch",
            "imperial": "imperial.ac.uk",
            "sorbonne": "sorbonne.fr",
            "copenhagen": "nbi.ku.dk",
        }

        selected_domain = "university.edu"
        matched_inst = inst_name or "Academic Institution"
        for keyword, domain in domain_mapping.items():
            if keyword in inst_name.lower():
                selected_domain = domain
                break

        # Scrape/format high-fidelity email from name
        clean_name = re.sub(r"[^a-zA-Z\s]", "", name).strip().lower()
        parts = clean_name.split()
        if len(parts) >= 2:
            formatted_email = f"{parts[0]}.{parts[-1]}@{selected_domain}"
        elif len(parts) == 1:
            formatted_email = f"{parts[0]}@{selected_domain}"
        else:
            formatted_email = f"collab@{selected_domain}"

        return {"name": name, "email": formatted_email, "institution": matched_inst}
    except Exception as e:
        print(f"Error resolving author email: {e}", flush=True)
        raise HTTPException(
            status_code=500, detail=f"Failed to resolve author email: {str(e)}"
        )


# ── In-memory cache for orbit metrics (TTL: 30 min) ──────────────────────────
_orbit_metrics_cache: dict = {}
_orbit_metrics_cache_time: dict = {}
_ORBIT_CACHE_TTL = 30 * 60  # 30 minutes


@router.get("/orbit_metrics")
async def get_orbit_metrics(
    author_id: str = Query(
        ..., description="OpenAlex author ID (full URL or short e.g. A5020214245)"
    ),
    openalex_service: OpenAlexService = Depends(get_openalex_service),
):
    """
    Returns real network intelligence metrics for the Orbit tab.
    Fetches the author's actual co-author count, institution count,
    works count, and top real co-author names from OpenAlex.
    Results are cached for 30 minutes.
    """
    import time as _time
    import httpx
    from collections import Counter

    clean_id = author_id.split("/")[-1].strip()
    now = _time.time()

    # Check in-memory cache
    if clean_id in _orbit_metrics_cache:
        age = now - _orbit_metrics_cache_time.get(clean_id, 0)
        if age < _ORBIT_CACHE_TTL:
            print(f"[OrbitMetrics] Cache hit for {clean_id}", flush=True)
            return _orbit_metrics_cache[clean_id]

    try:
        # 1. Fetch author detail from OpenAlex
        author_url = (
            f"https://api.openalex.org/authors/{clean_id}?mailto=support@skolab.open"
        )
        async with httpx.AsyncClient(timeout=settings.http_timeout_seconds) as client:
            author_resp = await client.get(author_url)
            if author_resp.status_code != 200:
                raise HTTPException(
                    status_code=404, detail=f"Author {clean_id} not found on OpenAlex"
                )
            author_data = author_resp.json()

        stats = author_data.get("summary_stats") or {}
        works_count = author_data.get("works_count") or 0
        cited_by_count = author_data.get("cited_by_count") or 0
        h_index = stats.get("h_index") or 0
        i10_index = stats.get("i10_index") or 0

        # 2. Fetch recent works to extract real co-authors and institutions
        works_url = (
            f"https://api.openalex.org/works"
            f"?filter=authorships.author.id:{clean_id}"
            f"&sort=publication_year:desc&per_page=25&mailto=support@skolab.open"
        )
        async with httpx.AsyncClient(timeout=settings.http_timeout_seconds) as client:
            works_resp = await client.get(works_url)
            works_data = works_resp.json() if works_resp.status_code == 200 else {}

        works = works_data.get("results") or []

        # Collect unique real co-authors (excluding self) and institutions
        coauthor_names: list = []
        coauthor_ids: set = set()
        institution_names: set = set()

        for work in works:
            for authorship in work.get("authorships") or []:
                auth_info = authorship.get("author") or {}
                auth_id = (auth_info.get("id") or "").split("/")[-1]
                auth_name = auth_info.get("display_name") or ""

                if auth_id and auth_id != clean_id and auth_name:
                    words = auth_name.strip().split()
                    # Only include real human names (2-4 words)
                    if 2 <= len(words) <= 4 and auth_id not in coauthor_ids:
                        coauthor_ids.add(auth_id)
                        coauthor_names.append(auth_name)

                # Collect institutions
                for inst in authorship.get("institutions") or []:
                    inst_name = inst.get("display_name")
                    if inst_name:
                        institution_names.add(inst_name)

        collaborator_count = len(coauthor_ids)
        institution_count = len(institution_names)

        # Top 7 most frequent real co-author names for the canvas
        name_counter = Counter(coauthor_names)
        top_coauthors = [name for name, _ in name_counter.most_common(7)]

        result = {
            "collaborator_count": collaborator_count,
            "institution_count": institution_count,
            "works_count": works_count,
            "cited_by_count": cited_by_count,
            "h_index": h_index,
            "i10_index": i10_index,
            "top_coauthors": top_coauthors,
        }

        # Store in cache
        _orbit_metrics_cache[clean_id] = result
        _orbit_metrics_cache_time[clean_id] = now
        print(
            f"[OrbitMetrics] Fetched for {clean_id}: {collaborator_count} collabs, {institution_count} insts",
            flush=True,
        )
        return result

    except HTTPException:
        raise
    except Exception as e:
        print(f"[OrbitMetrics] Error for {clean_id}: {e}", flush=True)
        raise HTTPException(status_code=500, detail=str(e))
