"""Response models for the system / infra routes.

Each mirrors the dict its handler returns today — no shape change.
"""

from __future__ import annotations

from typing import Any

from pydantic import BaseModel


class RootResponse(BaseModel):
    """``GET /`` on the API router.

    The route itself moved to the Go gateway (``internal/system``,
    decisions/0010); this model is retained as the byte-shape contract the Go
    handler must match.
    """

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
    """``GET /status``.

    The route moved to the Go gateway (``internal/system``, decisions/0010);
    this model is retained as the byte-shape contract the Go handler matches.
    """

    status: str
    services: SystemServiceStatuses
    incidents: list[dict[str, Any]]
