"""Response models for the agent routes — mirror each handler's current dict."""

from __future__ import annotations

from pydantic import BaseModel


class AgentChatResponse(BaseModel):
    """``POST /agent/chat`` — the research agent's reply."""

    reply: str


class UploadDocumentResponse(BaseModel):
    """``POST /agent/upload_document`` — stored-document acknowledgement."""

    id: int | str
    filename: str
    extracted_text: str


class ChatWithAuthorResponse(BaseModel):
    """``POST /chat_with_author``."""

    author_id: str
    author_name: str
    reply: str
