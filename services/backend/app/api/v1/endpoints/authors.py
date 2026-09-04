from fastapi import APIRouter, Depends, Query, HTTPException

from app.schemas.authors_extra import (
    AuthorMetricsResponse,
    CollaboratorSynergyResponse,
    GrantMatch,
    JournalRecommendation,
)
from app.services.platform.pipeline_services import PipelineServices
from app.services.platform.metrics_service import compute_author_metrics
from app.api.dependencies import get_pipeline_services
from app.core.cache import author_metrics_cache

import logging

logger = logging.getLogger("skolab")

router = APIRouter()


# GET /author_suggestions — migrated to Go (internal/handlers/authors.go)
# GET /search_author, GET /refresh_author — migrated to Go
# (internal/author/search.go). The teleport enrichment worker is an LLM job and
# stays here, reached via POST /api/v1/internal/teleport/{author_id}
# (endpoints/internal.py).


@router.get("/author_metrics", response_model=AuthorMetricsResponse)
async def get_author_metrics(
    author_id: str = Query(...),
):
    cached = await author_metrics_cache.get(author_id)
    if cached is not None:
        logger.debug(f"[AuthorMetrics] Cache hit for author={author_id}")
        return cached

    try:
        data = await compute_author_metrics(author_id)
        await author_metrics_cache.set(author_id, data)
        return data
    except ValueError as e:
        # Deliberate 4xx the route owns: a bad/unresolvable author_id.
        raise HTTPException(status_code=422, detail=str(e))


# GET /network_collaborators — migrated to Go (internal/author/network.go).
# Depth-1/2 co-author fan-out + Jaccard similarity, no LLM/embeddings — see
# docs/plans/2026-09-04-network-collaborators-to-go.md.


@router.get("/collaborator_synergy", response_model=CollaboratorSynergyResponse)
async def get_collaborator_synergy(
    author_id: str = Query(...),
    collaborator_id: str = Query(...),
    pipeline_services: PipelineServices = Depends(get_pipeline_services),
):
    return await pipeline_services.get_collaborator_synergy(author_id, collaborator_id)


# GET /citation_heatmap  — migrated to Go (internal/author/heatmap.go)


@router.get("/match_grants", response_model=list[GrantMatch])
async def match_grants(
    author_id: str = Query(...),
    pipeline_services: PipelineServices = Depends(get_pipeline_services),
):
    return await pipeline_services.match_grants(author_id)


@router.get("/journal_advisor", response_model=list[JournalRecommendation])
async def get_journal_advisor(
    author_id: str = Query(...),
    pipeline_services: PipelineServices = Depends(get_pipeline_services),
):
    return await pipeline_services.get_journal_advisor(author_id)


# GET /resolve_email  — migrated to Go (internal/handlers/authors.go)
# GET /orbit_metrics  — migrated to Go (internal/handlers/authors.go)
