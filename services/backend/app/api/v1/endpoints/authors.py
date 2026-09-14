from fastapi import APIRouter, Depends, Header, Query
from starlette.responses import JSONResponse

from app.schemas.authors_extra import (
    AuthorMetricsEnrichRequest,
    AuthorMetricsResponse,
    CollaboratorSynergyResponse,
    GrantMatch,
    JournalRecommendation,
)
from app.services.platform.pipeline_services import PipelineServices
from app.services.platform.metrics_service import analyze_author_metrics_context
from app.api.dependencies import get_pipeline_services
from app.api.v1.endpoints.internal import _check_internal_token
from app.core.pending_compute import PENDING, run_bounded
from app.api.v1.endpoints.feed import (
    DAILY_FEED_WAIT_TIMEOUT_SECONDS,
    DAILY_FEED_RETRY_AFTER_SECONDS,
)

import logging

logger = logging.getLogger("skolab")

router = APIRouter()


# GET /author_suggestions — migrated to Go (internal/handlers/authors.go)
# GET /search_author, GET /refresh_author — migrated to Go
# (internal/author/search.go). The teleport enrichment worker is an LLM job and
# stays here, reached via POST /api/v1/internal/teleport/{author_id}
# (endpoints/internal.py).


# GET /author_metrics — migrated to the Go gateway (internal/author/metrics.go).
# Go does the OpenAlex fetch, the "not enough papers" 422, and the 2 h cache;
# it calls the internal route below for the one model-bound step. decisions/0010.


@router.post("/internal/author_metrics_enrich", response_model=AuthorMetricsResponse)
async def author_metrics_enrich(
    req: AuthorMetricsEnrichRequest,
    x_internal_token: str | None = Header(default=None),
):
    """LLM enrichment for ``GET /author_metrics`` (served by the Go gateway).

    Takes the title/concepts digest the gateway built and returns the scored
    bundle. Raises 503 (``AIUnavailable``) if the LLM step fails — the gateway
    degrades that to an empty bundle on its side.

    Auth: same shared-secret ``X-Internal-Token`` check as this route's
    siblings in ``endpoints/internal.py`` — this route runs a real, billable
    LLM call and was found completely unauthenticated in production (2026-09
    audit): any caller with no headers at all could trigger it.
    """
    _check_internal_token(x_internal_token)
    return await analyze_author_metrics_context(req.context)


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
    # match_grants scrapes live funding portals and runs the results through
    # the embedding-grounded match-scoring pipeline (see grants.py) -- 34.9s
    # measured against production on a cache miss, unlike this route's
    # LLM-pipeline siblings (`daily_feed`, `industry_opportunities`) it had no
    # bounded-wait protection, so a cold request just blocked the client past
    # every upstream timeout. Same 202/Retry-After single-flight pattern as
    # those two (app/core/pending_compute.py) -- a request that catches the
    # result within the wait window gets it inline; a slower one gets a
    # prompt 202 while the pipeline keeps running and populates the cache.
    key = f"match_grants:{author_id}"
    result = await run_bounded(
        key,
        lambda: pipeline_services.match_grants(author_id),
        wait_timeout=DAILY_FEED_WAIT_TIMEOUT_SECONDS,
    )
    if result is PENDING:
        return JSONResponse(
            status_code=202,
            content=[],
            headers={"Retry-After": str(DAILY_FEED_RETRY_AFTER_SECONDS)},
        )
    return result


@router.get("/journal_advisor", response_model=list[JournalRecommendation])
async def get_journal_advisor(
    author_id: str = Query(...),
    pipeline_services: PipelineServices = Depends(get_pipeline_services),
):
    return await pipeline_services.get_journal_advisor(author_id)


# GET /resolve_email  — migrated to Go (internal/handlers/authors.go)
# GET /orbit_metrics  — migrated to Go (internal/handlers/authors.go)
