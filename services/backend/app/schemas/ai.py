"""Request and response shapes for the AI writing improver (gateway-only)."""

from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, Field

Goal = Literal["clarity", "concise", "academic", "grammar", "flow"]
Category = Literal["clarity", "concision", "tone", "grammar", "flow", "word_choice"]
CATEGORIES: tuple[str, ...] = (
    "clarity",
    "concision",
    "tone",
    "grammar",
    "flow",
    "word_choice",
)


class ImproveRequest(BaseModel):
    """Prose from a LaTeX document, with math, citations and commands already
    replaced by placeholders (⟦1⟧, ⟦2⟧, ...) by the gateway."""

    text: str = Field(..., min_length=1, max_length=6000)
    goal: Goal


class Edit(BaseModel):
    original: str = Field(..., min_length=1, max_length=2000)
    replacement: str = Field(..., max_length=4000)
    reason: str = Field(default="", max_length=300)
    category: Category = "clarity"


class ImproveResponse(BaseModel):
    edits: list[Edit] = Field(default_factory=list, max_length=20)
    model: str
    prompt_tokens: int = 0
    completion_tokens: int = 0
