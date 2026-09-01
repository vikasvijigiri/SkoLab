"""Response models for the author routes not covered by ``schemas.core``.

Shapes mirror ``apps/web/src/lib/types.ts`` (``NetworkCollaborator``,
``CitationHeatmap``, ``JournalRecommendation``, ``GrantMatch``) — the live web
consumers. ``extra="allow"`` keeps any additional backend field flowing through
rather than being dropped or rejected.
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

    id: str
    name: str
    institution: str = ""
    field: str = ""
    connection_path: str = ""
    relevance_score: float = 0.0
    papers_collaborated: int | None = None
    total_publications: int | None = None
    h_index: int | None = None


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

    title: str
    agency: str = ""
    agency_color: str = ""
    days_left: int | None = None
    amount: str = ""
    field: str = ""
    match_score: float = 0.0
    url: str = ""
    rationale: str = ""


class JournalRecommendation(BaseModel):
    """One row of ``GET /journal_advisor`` (bare JSON array)."""

    model_config = ConfigDict(extra="allow")

    journal_name: str
    works_count: int = 0
    is_oa: bool = False
    citation_impact: float = 0.0
    match_score: float = 0.0
    rationale: str = ""


class AuthorMetricsResponse(BaseModel):
    """``GET /author_metrics`` — computed metric bundle (shape varies by pipeline)."""

    model_config = ConfigDict(extra="allow")
