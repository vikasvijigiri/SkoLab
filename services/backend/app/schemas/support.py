"""Response model for the support routes."""

from __future__ import annotations

from typing import Any

from pydantic import BaseModel


class SupportMetricsResponse(BaseModel):
    """``GET /support/metrics`` — SLA targets, performance, and queue status."""

    sla_targets: dict[str, Any]
    performance_metrics: dict[str, Any]
    queue_status: dict[str, Any]
