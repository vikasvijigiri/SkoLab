"""Response models for the feed routes.

Fields mirror the published response contracts (``DailyFeedItem``,
``IndustryOpportunity``) — those are the live web/Android consumers. Every model
allows extras through so a backend field the clients don't read is never the
reason a response fails validation, and no field they *do* read is dropped.
"""

from __future__ import annotations

from typing import Any

from pydantic import BaseModel, ConfigDict


class DailyFeedItem(BaseModel):
    """One card in ``GET /feed/daily`` (returned as a bare JSON array)."""

    model_config = ConfigDict(extra="allow")

    id: str | None = None
    title: str | None = None
    authors: list[str] = []
    journal: str = ""
    year: int = 0
    publication_date: str | None = None
    relevance_score: float = 0.0
    recommendation_reason: str = ""
    doi: str | None = None
    abstract: str | None = None
    methodology: str | None = None
    tools_used: list[str] | None = None
    key_findings: str | None = None


# ``DismissResponse`` (``POST /feed/daily/dismiss``) moved to the Go gateway in
# Phase 2 — the handler now lives in services/backend-go/internal/feed/feed.go.


class IndustryOpportunity(BaseModel):
    """One item in ``GET /feed/industry-opportunities`` (bare JSON array)."""

    model_config = ConfigDict(extra="allow")

    id: str | None = None
    type: str | None = None
    title: str | None = None
    companyOrFunder: str = ""
    tags: list[str] = []
    description: str = ""
    postedAgo: str | None = None
    url: str | None = None
    eligibility: str | None = None
    amount: str | None = None
    procedureSteps: list[str] | None = None
    deadline: str | None = None
    status: str | None = None
    requiredSkills: list[str] | None = None
    matchScore: float | None = None
    relevanceExplanation: str | None = None
    location: str | None = None
    positionLevel: str | None = None
    remoteType: str | None = None


class RoadmapResponse(BaseModel):
    """``GET /feed/roadmap`` — milestone/checklist/template plan."""

    model_config = ConfigDict(extra="allow")

    userName: str | None = None
    researchFocus: str | None = None
    userMetrics: dict[str, Any] = {}
    targetMetrics: dict[str, Any] = {}
    milestones: list[dict[str, Any]] = []
    checklist: list[dict[str, Any]] = []
    peerCoauthors: list[dict[str, Any]] = []
    templates: list[dict[str, Any]] = []
    # True when milestones/checklist/templates are the generic hardcoded
    # set (LLM personalization failed) rather than tailored to the
    # researcher's own metrics -- userMetrics/targetMetrics stay real
    # either way (2026-09-12 endpoint audit).
    is_fallback: bool = False
