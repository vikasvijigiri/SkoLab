"""Response models for the papers routes not already covered by ``schemas.core``.

``/summarize_work`` and ``/presentation_outline`` return an LLM-shaped JSON
object whose exact keys vary with the model output, so their models declare the
known/degraded fields and allow extras through (``extra="allow"`` — FastAPI
serialises the extras, so no client field is dropped).
"""

from __future__ import annotations

from typing import Any

from pydantic import BaseModel, ConfigDict


class SummarizeWorkResponse(BaseModel):
    """``GET /summarize_work`` — legacy bullets/metrics summary or a degraded stub."""

    model_config = ConfigDict(extra="allow")

    bullets: list[Any] = []
    metrics: dict[str, Any] | None = None
    top_skills: list[Any] | None = None
    status: str | None = None
    message: str | None = None


class PresentationOutlineResponse(BaseModel):
    """``GET /presentation_outline`` — 7-slide outline; keys are LLM-shaped."""

    model_config = ConfigDict(extra="allow")

    slides: list[Any] = []


class SemanticTrendingPaper(BaseModel):
    model_config = ConfigDict(extra="allow")

    id: str = ""
    title: str = "Untitled"
    journal: str | None = None
    year: int = 0
    cited_by_count: int = 0
    velocity_score: float = 0.0
    is_open_access: bool = False
    concept_tags: list[str] = []
    doi: str = ""


class SemanticTrendingResponse(BaseModel):
    """``GET /semantic_trending`` — field-trending papers for one author."""

    author_concepts: list[str] = []
    papers: list[SemanticTrendingPaper] = []
