from typing import List, Optional, Union
from fastapi import APIRouter, Depends, Query, BackgroundTasks, HTTPException
from sqlalchemy.ext.asyncio import AsyncSession

from app.schemas.core import AuthorResponse, AuthorSuggestion, Work
from app.services.platform.pipeline_services import PipelineServices
from app.services.platform.metrics_service import compute_author_metrics
from app.services.ai.summarization_service import is_llm_working
from app.services.data.openalex_service import OpenAlexService
from app.api.dependencies import get_pipeline_services, get_openalex_service, get_db
from app.core.cache import (
    suggestions_cache,
    profile_cache,
    network_collaborators_cache,
    author_metrics_cache,
)

try:
    from app.services.data.researcher_worker import (
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
        import asyncio
        from app.services.data.openalex_service import (
            extract_field_and_expertise,
            derive_similar_authors_from_works,
        )

        # Derive candidates from authors of papers that already matched this
        # topic -- never call OpenAlex's author-name search (/authors?search=)
        # with a topic/concept string like `query_term`; it's a display-name
        # search, not topic discovery, and reliably surfaces unrelated authors
        # (see decisions/0005-similar-researchers-via-authorship.md).
        topic_matched_works = await openalex_service.search_works(
            query_term, per_page=20
        )
        candidates = derive_similar_authors_from_works(
            topic_matched_works, exclude_id, limit=5, discipline=query_term
        )
        if not candidates:
            return []

        profiles = await asyncio.gather(
            *(openalex_service.fetch_author_by_id(c["id"]) for c in candidates),
            return_exceptions=True,
        )

        suggestions = []
        for author in profiles:
            if not isinstance(author, dict):
                continue

            last_insts = author.get("last_known_institutions")
            inst = "Independent Researcher"
            if last_insts and isinstance(last_insts, list) and len(last_insts) > 0:
                first_inst = last_insts[0]
                if first_inst and isinstance(first_inst, dict):
                    inst = first_inst.get("display_name") or "Independent Researcher"

            field, expertise = extract_field_and_expertise(
                author, author.get("display_name", "")
            )

            stats = author.get("summary_stats") or {}
            h_idx = stats.get("h_index")
            works_count = author.get("works_count")

            score = compute_query_match_score(query_term, expertise or [field])

            suggestions.append(
                AuthorSuggestion(
                    id=author.get("id", ""),
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


# GET /author_suggestions — migrated to Go (internal/handlers/authors.go)


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
    except HTTPException:
        raise
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
        # Prefer the most specific expertise topic over the broad top-level
        # field for the similar-researchers query -- "Physics and Astronomy"
        # matches far too broadly (pulls in astronomers for a condensed-matter
        # profile); "Advanced Condensed Matter Physics" is what actually finds
        # topically-relevant peers via derive_similar_authors_from_works().
        similar_query_term = (pg_row.expertise or [None])[0] or field
        similar = await fetch_similar_authors(
            similar_query_term, pg_row.openalex_id or clean_id, openalex_service
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
            skills=pg_row.skills or [],
            tools=pg_row.tools or [],
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
                    # Prefer the specific expertise topic over the broad field
                    # for similar-researchers matching -- see the PG branch
                    # above for why.
                    similar_query_term = (d.get("expertise") or [None])[0] or field
                    similar = await fetch_similar_authors(
                        similar_query_term, d.get("openalex_id", ""), openalex_service
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
                        skills=d.get("skills", []),
                        tools=d.get("tools", []),
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
        from app.services.data.openalex_service import (
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
            citations = w.get("cited_by_count") or 0
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

        # Prefer the specific expertise topic over the broad field for
        # similar-researchers matching -- see the PG branch above for why.
        similar_query_term = (expertise or [None])[0] or field
        similar = await fetch_similar_authors(
            similar_query_term, author_data.get("id", ""), openalex_service
        )

        response_data = AuthorResponse(
            id=author_data.get("id", resolved_id),
            display_name=author_data.get("display_name", name),
            orcid=author_data.get("orcid"),
            h_index=stats.get("h_index") or 0,
            i10_index=stats.get("i10_index") or 0,
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
    except HTTPException:
        raise
    except ValueError as e:
        raise HTTPException(status_code=422, detail=str(e))
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


# GET /resolve_email  — migrated to Go (internal/handlers/authors.go)
# GET /orbit_metrics  — migrated to Go (internal/handlers/authors.go)
