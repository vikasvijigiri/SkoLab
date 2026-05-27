import re
from typing import List, Optional, Union
from fastapi import APIRouter, Depends, Query, BackgroundTasks, HTTPException

from app.schemas.core import AuthorResponse, AuthorSuggestion, Work
from app.services.pipeline_services import PipelineServices
from app.services.metrics_service import compute_author_metrics
from app.services.summarization_service import is_llm_working
from app.services.openalex_service import OpenAlexService
from app.api.dependencies import (
    get_pipeline_services,
    get_openalex_service
)
from app.core.cache import (
    suggestions_cache,
    profile_cache,
    network_collaborators_cache
)

try:
    from app.services.researcher_worker import teleport_researcher, FIRESTORE_AVAILABLE
except ImportError:
    teleport_researcher = None
    FIRESTORE_AVAILABLE = False

if FIRESTORE_AVAILABLE:
    from firebase_admin import firestore

router = APIRouter()

async def fetch_similar_authors(
    query_term: str, 
    exclude_id: str, 
    openalex_service: OpenAlexService
) -> List[AuthorSuggestion]:
    if not query_term or query_term.lower() in ["multidisciplinary", "researcher", "general research"]:
        try:
            hot_works = await openalex_service.search_works(query="science", per_page=10)
            seen_concepts = []
            for w in hot_works:
                for c in w.get("concepts", []):
                    if c.get("level") == 1 and c.get("display_name"):
                        seen_concepts.append(c.get("display_name"))
            if seen_concepts:
                from collections import Counter
                query_term = Counter(seen_concepts).most_common(1)[0][0]
            else:
                query_term = "science"
        except Exception:
            query_term = "science"

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
            
            concepts = author.get("x_concepts", [])
            field = concepts[0].get("display_name", "Multidisciplinary") if concepts else "Multidisciplinary"
            
            stats = author.get("summary_stats") or {}
            h_idx = stats.get("h_index")
            
            suggestions.append(AuthorSuggestion(
                id=author_id,
                display_name=author.get("display_name", "Unknown"),
                institution=inst,
                field_of_study=field,
                h_index=int(h_idx) if h_idx is not None else None,
                innovation_score=None
            ))
            if len(suggestions) >= 5:
                break
        return suggestions
    except Exception as e:
        print(f"Error fetching similar authors: {e}", flush=True)
    return []

@router.get("/author_suggestions", response_model=List[AuthorSuggestion])
async def get_author_suggestions(
    query: str = Query(...),
    openalex_service: OpenAlexService = Depends(get_openalex_service)
):
    cache_key = query.strip().lower()
    cached = await suggestions_cache.get(cache_key)
    if cached is not None:
        print(f"[Cache Hit] Suggestions for: '{query}'", flush=True)
        return cached

    # 1. Search Firestore for suggestions first (Fast)
    if False:  # Keeping design from main.py as is
        try:
            db = firestore.client()
            from google.cloud.firestore_v1.base_query import FieldFilter
            
            docs = db.collection("global_researchers")\
                .where(filter=FieldFilter("display_name", ">=", query))\
                .where(filter=FieldFilter("display_name", "<=", query + "\uf8ff"))\
                .limit(10)\
                .get()
            
            if docs:
                suggestions = []
                for d in [doc.to_dict() for doc in docs]:
                    h_idx = d.get("h_index")
                    inv_score = d.get("innovation_score")
                    suggestions.append(AuthorSuggestion(
                        id=d.get("openalex_id"),
                        display_name=d.get("display_name"),
                        institution=d.get("current_institution") or "Independent Researcher",
                        field_of_study=d.get("field_of_study"),
                        h_index=int(h_idx) if h_idx is not None else None,
                        innovation_score=int(inv_score) if inv_score is not None else None
                    ))
                await suggestions_cache.set(cache_key, suggestions)
                return suggestions
        except Exception as e:
            print(f"Firestore Suggester Error: {e}")

    # 2. Fallback to OpenAlex
    non_person_keywords = re.compile(
        r"\b(collaboration|group|consortium|committee|team|network|project|society|association|institute|university|department|lab|laboratory|center|centre|foundation|quantum|topology|invariants|materials|systems|physics|biology|chemistry|science|computing|theory|applications|methods|frontiers|research)\b",
        re.IGNORECASE
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
            
            suggestions.append(AuthorSuggestion(
                id=author["id"],
                display_name=disp_name,
                institution=inst,
                h_index=int(h_idx) if h_idx is not None else None,
                innovation_score=None
            ))
        
        if len(suggestions) < 4:
            print(f"[Fallback to Works] Insufficient human authors for '{query}', searching works...", flush=True)
            works = await openalex_service.search_works(query, per_page=20)
            seen_ids = {s.id for s in suggestions}
            for work in works:
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
                        inst = last_insts[0].get("display_name") or "Independent Researcher"
                        
                    seen_ids.add(auth_id)
                    suggestions.append(AuthorSuggestion(
                        id=auth_id,
                        display_name=auth_name,
                        institution=inst,
                        h_index=None,
                        innovation_score=None
                    ))
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
    openalex_service: OpenAlexService = Depends(get_openalex_service)
):
    """Explicitly re-runs the teleportation worker to update Firestore data."""
    try:
        cache_key = name.strip().lower()
        await profile_cache.delete(cache_key)
        await suggestions_cache.delete(cache_key)

        results = await openalex_service.search_authors(name, per_page=1)
        if results:
            author_id = results[0]["id"]
            if teleport_researcher is not None:
                background_tasks.add_task(teleport_researcher, author_id)
            return {"status": "Refresh started", "author_id": author_id}
        raise HTTPException(status_code=404, detail="Author not found for refresh")
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@router.get("/search_author", response_model=Union[AuthorResponse, dict])
async def search_author(
    name: str = Query(...),
    id: Optional[str] = Query(None),
    background_tasks: BackgroundTasks = BackgroundTasks(),
    openalex_service: OpenAlexService = Depends(get_openalex_service)
):
    print(f"[search_author] name='{name}', id='{id}'", flush=True)
    if name.strip().lower() in ["vikas", "user_vikas"]:
        name = "Vikas Vijigiri"
    clean_id = id.split("/")[-1] if id else None
    cache_key = f"id:{clean_id}" if clean_id else name.strip().lower()

    # 1. Check in-memory cache
    cached = await profile_cache.get(cache_key)
    if cached is not None:
        print(f"[search_author] In-memory cache hit: '{cache_key}'", flush=True)
        return cached

    # 2. Check Firestore (pre-computed by teleport_researcher)
    if False: # Keeping design from main.py as is
        try:
            db = firestore.client()
            if clean_id:
                doc = db.collection("global_researchers").document(clean_id).get()
                d = doc.to_dict() if doc.exists else None
            else:
                docs = db.collection("global_researchers").where("display_name", "==", name).limit(1).get()
                d = docs[0].to_dict() if docs else None

            if d:
                print(f"[search_author] Firestore cache hit for: '{clean_id or name}'", flush=True)
                works_data = []
                for w in d.get("works", []):
                    try:
                        _work_fields = set(getattr(Work, 'model_fields', None) or Work.__fields__)
                        works_data.append(Work(**{k: v for k, v in w.items() if k in _work_fields}))
                    except Exception:
                        pass
                field = d.get("field_of_study") or (d.get("expertise", [""])[0] if d.get("expertise") else "")
                similar = await fetch_similar_authors(field, d.get("openalex_id", ""), openalex_service)
                response_data = AuthorResponse(
                    id=d.get("openalex_id", ""),
                    display_name=d.get("display_name", ""),
                    orcid=d.get("orcid"),
                    h_index=d.get("h_index", 0),
                    i10_index=d.get("i10_index", 0),
                    works_count=d.get("works_count", 0),
                    cited_by_count=d.get("cited_by_count", 0),
                    institution=d.get("current_institution") or "Independent Researcher",
                    field_of_study=d.get("field_of_study", "Multidisciplinary"),
                    expertise=d.get("expertise", []),
                    academic_history=d.get("academic_history", []),
                    works=works_data,
                    innovation_score=d.get("innovation_score"),
                    metrics_computed=is_llm_working() and d.get("metrics_computed", False),
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
                    similar_researchers=similar
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
            results = await openalex_service.search_authors(name, per_page=1)
            if results:
                author_data = results[0]
                resolved_id = author_data["id"].split("/")[-1]

        if not author_data or not resolved_id:
            raise HTTPException(status_code=404, detail="Author not found on OpenAlex")

        author_orcid = author_data.get("orcid")
        raw_works = await openalex_service.fetch_author_works(resolved_id, orcid=author_orcid, per_page=50)

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

            works_data.append(Work(
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
                authors=authors_list
            ))

        last_insts = author_data.get("last_known_institutions") or []
        institution = "Independent Researcher"
        if last_insts and isinstance(last_insts, list):
            first = last_insts[0]
            if first and isinstance(first, dict):
                institution = first.get("display_name") or "Independent Researcher"

        stats = author_data.get("summary_stats") or {}
        concepts = author_data.get("x_concepts") or []
        field = next((c.get("display_name") for c in concepts if c.get("level") == 1), None) \
                or (concepts[0].get("display_name") if concepts else "Multidisciplinary")
        expertise = [c.get("display_name") for c in concepts
                     if c.get("level") in [1, 2] and c.get("display_name")][:6]

        affiliations = author_data.get("affiliations") or []
        hist_map: dict = {}
        for aff in affiliations:
            inst_info = (aff.get("institution") or {})
            inst_name = inst_info.get("display_name")
            years = aff.get("years") or []
            if not inst_name or not years:
                continue
            existing_val = hist_map.get(inst_name)
            if existing_val is None:
                hist_map[inst_name] = [min(years), max(years)]
            else:
                hist_map[inst_name] = [min(existing_val[0], min(years)), max(existing_val[1], max(years))]
        academic_history = [
            f"{n} ({y[0]}\u2013{y[1]})" if y[0] != y[1] else f"{n} ({y[0]})"
            for n, y in sorted(hist_map.items(), key=lambda x: x[1][0])
        ]

        similar = await fetch_similar_authors(field, author_data.get("id", ""), openalex_service)

        response_data = AuthorResponse(
            id=author_data.get("id", resolved_id),
            display_name=author_data.get("display_name", name),
            orcid=author_data.get("orcid"),
            h_index=stats.get("h_index", 0),
            i10_index=stats.get("i10_index", 0),
            works_count=author_data.get("works_count", 0),
            cited_by_count=author_data.get("cited_by_count", 0),
            institution=institution,
            field_of_study=field,
            expertise=expertise,
            academic_history=academic_history,
            works=works_data,
            innovation_score=None,
            metrics_computed=False,
            llm_active=is_llm_working(),
            next_prediction=None,
            similar_researchers=similar
        )

        await profile_cache.set(cache_key, response_data)

        if is_llm_working() and teleport_researcher is not None:
            author_full_id = author_data.get("id", resolved_id)
            background_tasks.add_task(teleport_researcher, author_full_id)
            print(f"[search_author] Queued background teleport for: {author_data.get('display_name')}", flush=True)
        else:
            print(f"[search_author] LLM offline or unconfigured, skipping background task.", flush=True)

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
    try:
        data = await compute_author_metrics(author_id)
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
    pipeline_services: PipelineServices = Depends(get_pipeline_services)
):
    cache_key = f"{author_id}_{limit}_{offset}_{exclude_ids}_{field}"
    cached_data = await network_collaborators_cache.get(cache_key)
    if cached_data is not None:
        return cached_data
        
    excl_list = [x.strip() for x in exclude_ids.split(",")] if exclude_ids else []
    try:
        data = await pipeline_services.get_network_collaborators(author_id, limit, offset, excl_list, field)
        if data:
            await network_collaborators_cache.set(cache_key, data)
        return data
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@router.get("/collaborator_synergy")
async def get_collaborator_synergy(
    author_id: str = Query(...),
    collaborator_id: str = Query(...),
    pipeline_services: PipelineServices = Depends(get_pipeline_services)
):
    try:
        data = await pipeline_services.get_collaborator_synergy(author_id, collaborator_id)
        return data
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@router.get("/citation_heatmap")
async def get_citation_heatmap(
    author_id: str = Query(...),
    pipeline_services: PipelineServices = Depends(get_pipeline_services)
):
    try:
        data = await pipeline_services.get_citation_heatmap(author_id)
        return data
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@router.get("/match_grants")
async def match_grants(
    author_id: str = Query(...),
    pipeline_services: PipelineServices = Depends(get_pipeline_services)
):
    try:
        data = await pipeline_services.match_grants(author_id)
        return data
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@router.get("/journal_advisor")
async def get_journal_advisor(
    author_id: str = Query(...),
    pipeline_services: PipelineServices = Depends(get_pipeline_services)
):
    try:
        data = await pipeline_services.get_journal_advisor(author_id)
        return data
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))
