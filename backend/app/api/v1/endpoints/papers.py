import datetime
from typing import Optional
from fastapi import APIRouter, Depends, Query, HTTPException

from app.schemas.core import PaperIntelligenceResponse
from app.services.summarization_service import SummarizationService
from app.services.pipeline_services import PipelineServices
from app.services.openalex_service import OpenAlexService
from app.api.dependencies import (
    get_summarization_service,
    get_pipeline_services,
    get_openalex_service
)
from app.core.cache import analyze_paper_cache, _semantic_trending_cache

router = APIRouter()

@router.get("/summarize_work")
async def summarize_work(
    title: str = Query(...),
    doi: Optional[str] = None,
    summarization_service: SummarizationService = Depends(get_summarization_service)
):
    try:
        return await summarization_service.summarize_paper(title, doi)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@router.get("/analyze_paper", response_model=PaperIntelligenceResponse)
async def analyze_paper(
    title: str = Query(..., description="Full paper title"),
    doi: Optional[str] = Query(None, description="DOI of the paper (e.g. 10.48550/arXiv.1706.03762)"),
    openalex_id: Optional[str] = Query(None, description="OpenAlex work ID (e.g. W2741809807 or full URL)"),
    summarization_service: SummarizationService = Depends(get_summarization_service)
):
    """
    Deep paper intelligence: reads the FULL paper text (PDF when available)
    and extracts 9 structured insight dimensions via a Research Intelligence Agent LLM.
    """
    cache_key = f"intelligence:{openalex_id or doi or title}"
    cached = await analyze_paper_cache.get(cache_key)
    if cached is not None:
        print(f"[/analyze_paper] Cache hit for: {title[:50]}", flush=True)
        return cached

    try:
        result = await summarization_service.analyze_paper(
            title=title,
            doi=doi,
            openalex_id=openalex_id,
        )
        await analyze_paper_cache.set(cache_key, result)
        return result
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@router.get("/presentation_outline")
async def presentation_outline(
    title: str = Query(...),
    doi: Optional[str] = None,
    summarization_service: SummarizationService = Depends(get_summarization_service)
):
    try:
        return await summarization_service.generate_presentation(title, doi)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@router.get("/semantic_trending")
async def get_semantic_trending(
    author_id: str = Query(..., description="Full OpenAlex author URI or short ID"),
    limit: int = Query(10, ge=1, le=30),
    pipeline_services: PipelineServices = Depends(get_pipeline_services),
    openalex_service: OpenAlexService = Depends(get_openalex_service)
):
    """
    Returns papers trending specifically in the author's research field.
    """
    cache_key = f"semantic_trending_{author_id}_{limit}"
    cached = await _semantic_trending_cache.get(cache_key)
    if cached is not None:
        print(f"[SemanticTrending] Cache hit for author={author_id}", flush=True)
        return cached

    # ── also check Postgres long-term cache ──────────────────────────────────
    try:
        pg_cached = await pipeline_services._load_from_postgres(cache_key)
        if pg_cached is not None:
            await _semantic_trending_cache.set(cache_key, pg_cached)
            print(f"[SemanticTrending] Postgres cache hit for author={author_id}", flush=True)
            return pg_cached
    except Exception as e:
        print(f"[SemanticTrending] Postgres cache read error: {e}", flush=True)

    now = datetime.datetime.utcnow()
    now_year = now.year
    now_month = now.month

    # ── Step 1: Fetch author record ───────────────────────────────────────
    clean_id = author_id.split("/")[-1]
    author_data = await openalex_service.fetch_author_by_id(clean_id)
    if not author_data:
        raise HTTPException(status_code=404, detail="OpenAlex author fetch failed.")

    # Extract top concepts/topics (prefer x_concepts, fall back to topics)
    raw_concepts = author_data.get("x_concepts") or author_data.get("topics") or []
    # Sort by score descending, take top 5
    top_concepts = sorted(raw_concepts, key=lambda c: c.get("score", 0), reverse=True)[:5]
    author_concept_ids = {c.get("id", "").split("/")[-1] for c in top_concepts}
    author_concept_names = [c.get("display_name", "") for c in top_concepts if c.get("display_name")]

    if not top_concepts:
        raise HTTPException(status_code=404, detail="Author has no resolvable research concepts in OpenAlex.")

    print(f"[SemanticTrending] Author concepts: {author_concept_names}", flush=True)

    # ── Step 2: Fetch works per concept ──────────────────────────────────
    prev_year = now_year - 1
    all_works: list[dict] = []
    seen_ids: set[str] = set()

    for concept in top_concepts[:4]:
        concept_id = concept.get("id", "").split("/")[-1]
        if not concept_id:
            continue
        try:
            works_data = await openalex_service.fetch_works_by_concept(
                concept_id,
                prev_year,
                now_year,
                per_page=15
            )
            for w in works_data:
                wid = w.get("id", "")
                if wid and wid not in seen_ids:
                    seen_ids.add(wid)
                    # tag which concept sourced this paper (for overlap scoring)
                    w["_source_concept_id"] = concept_id
                    all_works.append(w)
        except Exception as e:
            print(f"[SemanticTrending] Concept {concept_id} fetch error: {e}", flush=True)
            continue

    if not all_works:
        raise HTTPException(status_code=404, detail="No relevant trending papers found for this author's research area.")

    # ── Step 3: Score each paper ──────────────────────────────────────────
    def score_paper(w: dict) -> float:
        pub_year = w.get("publication_year") or now_year
        # Estimate month (OpenAlex often has publication_date YYYY-MM-DD)
        pub_date = w.get("publication_date") or f"{pub_year}-01-01"
        try:
            parts = pub_date.split("-")
            pub_month = int(parts[1]) if len(parts) > 1 else 1
            pub_year_int = int(parts[0])
        except Exception:
            pub_month = 1
            pub_year_int = pub_year

        months_alive = max(1, (now_year - pub_year_int) * 12 + (now_month - pub_month))
        cited = w.get("cited_by_count") or 0
        velocity = cited / months_alive  # citations per month

        # Concept overlap: how many author concepts appear in paper's concepts
        paper_concepts = w.get("concepts") or w.get("topics") or []
        paper_concept_ids = {c.get("id", "").split("/")[-1] for c in paper_concepts}
        overlap = len(paper_concept_ids & author_concept_ids)

        oa_bonus = 0.1 if w.get("open_access", {}).get("is_oa") else 0.0

        return velocity * 0.5 + overlap * 3.0 + oa_bonus

    scored = sorted(all_works, key=score_paper, reverse=True)
    top_papers = scored[:limit]

    # ── Step 4: Build response ────────────────────────────────────────────
    result_items = []
    for w in top_papers:
        pub_year = w.get("publication_year") or 0
        pub_date = w.get("publication_date") or f"{pub_year}-01-01"
        try:
            parts = pub_date.split("-")
            pub_year_int = int(parts[0])
            pub_month_int = int(parts[1]) if len(parts) > 1 else 1
        except Exception:
            pub_year_int = pub_year
            pub_month_int = 1

        months_alive = max(1, (now_year - pub_year_int) * 12 + (now_month - pub_month_int))
        cited = w.get("cited_by_count") or 0
        velocity = round(cited / months_alive, 2)

        # Concept tags: paper concepts that match author concepts
        paper_concepts = w.get("concepts") or w.get("topics") or []
        matched_tags = [
            c.get("display_name", "")
            for c in paper_concepts
            if c.get("id", "").split("/")[-1] in author_concept_ids and c.get("display_name")
        ][:3]
        if not matched_tags and author_concept_names:
            matched_tags = [author_concept_names[0]]

        loc = w.get("primary_location") or {}
        source = loc.get("source") or {}
        result_items.append({
            "id": w.get("id", ""),
            "title": w.get("title") or "Untitled",
            "journal": source.get("display_name") or "Unknown Journal",
            "year": pub_year_int,
            "cited_by_count": cited,
            "velocity_score": velocity,
            "is_open_access": (w.get("open_access") or {}).get("is_oa", False),
            "concept_tags": matched_tags,
            "doi": w.get("doi") or "",
        })

    response = {
        "author_concepts": author_concept_names[:5],
        "papers": result_items,
    }

    # ── Step 5: Cache ─────────────────────────────────────────────────────
    await _semantic_trending_cache.set(cache_key, response)
    try:
        await pipeline_services._save_to_postgres(cache_key, response)
    except Exception as e:
        print(f"[SemanticTrending] Postgres cache write error: {e}", flush=True)

    print(f"[SemanticTrending] Returning {len(result_items)} personalized papers for author={author_id}", flush=True)
    return response
