"""Response models for the discovery-engine routes.

Mirrors ``apps/web/src/lib/types.ts`` ``BreakthroughPrediction`` / ``PaperSource``
field-for-field — the Horizon page (`getHorizonPrediction`) and Nexus chat
(`nexusChat`) consume these directly.
"""

from __future__ import annotations

from pydantic import BaseModel, ConfigDict


class PaperSource(BaseModel):
    model_config = ConfigDict(extra="allow")

    id: str = ""
    title: str = ""
    authors: list[str] = []
    year: int = 0
    cited_by_count: int = 0
    doi: str | None = None


class BreakthroughPrediction(BaseModel):
    """``POST /discovery/predict`` — the Horizon foresight result."""

    model_config = ConfigDict(extra="allow")

    breakthrough_name: str = ""
    description: str = ""
    scientific_logic: str = ""
    business_application: str = ""
    time_horizon: str = ""
    feasibility: str = ""
    roadmap_steps: list[str] = []
    pioneering_papers: list[PaperSource] = []
    latest_papers: list[PaperSource] = []


class NexusChatResponse(BaseModel):
    """``POST /discovery/nexus-chat``."""

    model_config = ConfigDict(extra="allow")

    content: str | None = None
