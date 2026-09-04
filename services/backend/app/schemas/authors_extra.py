"""Response models for the author routes not covered by ``schemas.core``.

These wrap routes that were untyped until now and whose live payloads vary
(cache hits, Firestore docs, scraped grant listings). The models therefore
declare no required fields and allow extras through (``extra="allow"``) — their
job is "typed route, no leaked internals, no dropped field", not to reject a
real response. The authoritative field lists for the web/Android clients live
in ``apps/web/src/lib/types.ts`` (``NetworkCollaborator``, ``CitationHeatmap``,
``JournalRecommendation``, ``GrantMatch``).
"""

from __future__ import annotations

from pydantic import BaseModel, ConfigDict


class RefreshAuthorResponse(BaseModel):
    """``GET /refresh_author`` — kicks the teleport worker."""

    status: str
    author_id: str


class NetworkCollaborator(BaseModel):
    """One row of ``GET /network_collaborators`` (bare JSON array)."""

    model_config = ConfigDict(extra="allow")

    id: str | None = None
    name: str | None = None
    institution: str | None = None
    field: str | None = None
    connection_path: str | None = None
    relevance_score: float | None = None


class CollaboratorSynergyResponse(BaseModel):
    """``GET /collaborator_synergy`` — pairwise synergy metrics (LLM/graph-shaped)."""

    model_config = ConfigDict(extra="allow")


class CitationHeatmap(BaseModel):
    """``GET /citation_heatmap``."""

    model_config = ConfigDict(extra="allow")

    years: list[int] = []
    citations: list[int] = []
    works: list[int] = []
    institutional_reach: float = 0.0
    h_index: int = 0


class GrantMatch(BaseModel):
    """One row of ``GET /match_grants`` (bare JSON array)."""

    model_config = ConfigDict(extra="allow")

    title: str | None = None
    agency: str | None = None
    match_score: float | None = None
    url: str | None = None


class JournalRecommendation(BaseModel):
    """One row of ``GET /journal_advisor`` (bare JSON array)."""

    model_config = ConfigDict(extra="allow")

    journal_name: str | None = None
    match_score: float | None = None
    rationale: str | None = None


class AuthorMetricsResponse(BaseModel):
    """``POST /internal/author_metrics_enrich`` — the LLM-scored metric bundle.

    ``GET /author_metrics`` itself is served by the Go gateway now
    (decisions/0010); Python only runs the enrichment step. Shape:
    ``topic_toughness``/``velocity``/``overall_score`` ints, ``skills``/``tools``
    string arrays, ``analysis`` string. ``extra="allow"`` keeps it a passthrough.
    """

    model_config = ConfigDict(extra="allow")


class AuthorMetricsEnrichRequest(BaseModel):
    """Body of ``POST /internal/author_metrics_enrich`` — a title/concepts digest
    of the author's recent works, built by the Go gateway."""

    context: str
