"""Application exceptions that carry HTTP intent.

These let a service layer signal *how* a failure should surface at the API
boundary without importing FastAPI. ``app/api/errors.py`` registers the
handlers that turn them into the standard ``ErrorResponse`` envelope.
"""

from __future__ import annotations


class AIUnavailable(Exception):
    """An AI / LLM dependency failed (provider outage, circuit open, deadline).

    Handlers surface this as **HTTP 503** with ``code="ai_unavailable"`` and a
    ``Retry-After`` header — a transient, retryable condition — instead of a
    generic 500. Raise it from a service when the AI step cannot complete and
    there is no local fallback to fall back to.
    """

    def __init__(self, detail: str = "", retry_after: int = 30) -> None:
        self.detail = detail or (
            "AI analysis is temporarily unavailable. Please retry shortly."
        )
        self.retry_after = retry_after
        super().__init__(self.detail)
