"""Response model for the recommendation routes not in ``domains.recommendation.schemas``."""

from __future__ import annotations

from pydantic import BaseModel


class LogPeerInviteResponse(BaseModel):
    """``POST /recommendations/peers/invite`` — invite logged for score updates."""

    success: bool
