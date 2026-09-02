"""Response models for the Zotero integration routes.

These endpoints are OAuth stubs today — the models mirror the mock responses
they currently return, not a reworked flow.
"""

from __future__ import annotations

from pydantic import BaseModel


class ZoteroAuthResponse(BaseModel):
    """``GET /integrations/zotero/auth``."""

    authorization_url: str


class ZoteroCallbackResponse(BaseModel):
    """``GET /integrations/zotero/callback``."""

    status: str
    message: str
    zotero_user_id: str
    zotero_username: str


class ZoteroSyncResponse(BaseModel):
    """``POST /integrations/zotero/sync``."""

    status: str
    synced_count: int
    synced_papers: list[str]
    message: str
