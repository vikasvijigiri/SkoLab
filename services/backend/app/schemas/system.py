"""Response models for the system / infra routes.

Each mirrors the dict its handler returns today — no shape change.
"""

from __future__ import annotations

from typing import Any

from pydantic import BaseModel


class RootResponse(BaseModel):
    """``GET /`` on the API router."""

    message: str


class LivenessResponse(BaseModel):
    """``GET /livez`` — dependency-free liveness probe."""

    status: str


class AppInfoResponse(BaseModel):
    """``GET /`` defined on the application in ``main.py``."""

    app: str
    status: str
    version: str


class AiStatusResponse(BaseModel):
    """``GET /ai_status``."""

    groq_api_configured: bool
    llm_active: bool
    model: str
    key_prefix: str


class SystemServiceStatuses(BaseModel):
    api_gateway: str
    database: str
    cache_layer: str
    ai_inference: str


class SystemStatusResponse(BaseModel):
    """``GET /status``."""

    status: str
    services: SystemServiceStatuses
    incidents: list[dict[str, Any]]
