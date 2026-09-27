"""Response models for main.py's own metadata/health routes.

Each mirrors the dict its handler returns today — no shape change.
"""

from __future__ import annotations

from pydantic import BaseModel


class LivenessResponse(BaseModel):
    """``GET /livez`` — dependency-free liveness probe."""

    status: str


class AppInfoResponse(BaseModel):
    """``GET /`` defined on the application in ``main.py``."""

    app: str
    status: str
    version: str
