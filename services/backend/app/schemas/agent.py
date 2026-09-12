"""Response models for the agent routes — mirror each handler's current dict.

Fields are optional with extras allowed: the job here is a typed route with no
leaked internals, not rejecting a live payload whose shape varies with the LLM
path taken.
"""

from __future__ import annotations

from pydantic import BaseModel, ConfigDict


class AgentChatResponse(BaseModel):
    """``POST /agent/chat`` — the research agent's reply."""

    model_config = ConfigDict(extra="allow")

    reply: str | None = None
    # True when `reply` is a hand-written apology/unavailability sentence
    # (LLM not configured, all turns exhausted, or a genuine error) rather
    # than a model-generated answer -- without this a client can't tell
    # the two apart (2026-09-12 endpoint audit).
    is_fallback: bool = False


class UploadDocumentResponse(BaseModel):
    """``POST /agent/upload_document`` — stored-document acknowledgement."""

    model_config = ConfigDict(extra="allow")

    id: int | str | None = None
    filename: str | None = None
    extracted_text: str | None = None


class ChatWithAuthorResponse(BaseModel):
    """``POST /chat_with_author``."""

    model_config = ConfigDict(extra="allow")

    author_id: str | None = None
    author_name: str | None = None
    reply: str | None = None
    # True when `reply` is the canned "thank you for your question" stand-in
    # (LLM unavailable/failed) rather than a model-generated answer
    # (2026-09-12 endpoint audit).
    is_fallback: bool = False
