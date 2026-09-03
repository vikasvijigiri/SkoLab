"""Application-wide error envelope and exception handlers.

Every error response that leaves the API has the same JSON shape
(:class:`ErrorResponse`). Unhandled exceptions are logged server-side with the
request id and returned to the client as a generic 500 — the internal message
never crosses the boundary.
"""

from __future__ import annotations

import logging
from typing import Any

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import BaseModel
from starlette.exceptions import HTTPException as StarletteHTTPException

from app.core.exceptions import AIUnavailable

logger = logging.getLogger("skolab")


class ErrorResponse(BaseModel):
    """The single error shape returned by every handled failure."""

    detail: str
    code: str
    request_id: str
    errors: list[dict[str, Any]] | None = None


def _request_id() -> str:
    """Best-effort current request id from the logging contextvar."""
    try:
        from app.main import request_id_var

        return request_id_var.get() or ""
    except Exception:  # pragma: no cover - contextvar always importable in app
        return ""


def register_exception_handlers(app: FastAPI) -> None:
    """Register the three app-level handlers on ``app``.

    Covers routes mounted at both ``/api/v1`` and the bare prefix, since the
    handlers live on the application rather than a router.
    """

    @app.exception_handler(RequestValidationError)
    async def _validation_handler(
        request: Request, exc: RequestValidationError
    ) -> JSONResponse:
        errors = exc.errors()
        body = ErrorResponse(
            detail="Request validation failed",
            code="validation_error",
            request_id=_request_id(),
            errors=[
                {
                    "loc": list(e.get("loc", [])),
                    "msg": e.get("msg", ""),
                    "type": e.get("type", ""),
                }
                for e in errors
            ],
        )
        return JSONResponse(status_code=422, content=body.model_dump())

    @app.exception_handler(AIUnavailable)
    async def _ai_unavailable_handler(
        request: Request, exc: AIUnavailable
    ) -> JSONResponse:
        # A dependency (LLM provider) is down and the route has no local
        # fallback. 503 + Retry-After tells the client this is transient — never
        # a 500, which reads as "the server is broken".
        logger.warning(
            "AI unavailable on %s %s: %s",
            request.method,
            request.url.path,
            exc.detail,
        )
        body = ErrorResponse(
            detail=exc.detail, code="ai_unavailable", request_id=_request_id()
        )
        return JSONResponse(
            status_code=503,
            content=body.model_dump(),
            headers={"Retry-After": str(exc.retry_after)},
        )

    @app.exception_handler(StarletteHTTPException)
    async def _http_handler(
        request: Request, exc: StarletteHTTPException
    ) -> JSONResponse:
        detail = exc.detail if isinstance(exc.detail, str) else "Request failed"
        code = f"http_{exc.status_code}"
        body = ErrorResponse(detail=detail, code=code, request_id=_request_id())
        return JSONResponse(
            status_code=exc.status_code,
            content=body.model_dump(),
            headers=getattr(exc, "headers", None),
        )

    @app.exception_handler(Exception)
    async def _unhandled_handler(request: Request, exc: Exception) -> JSONResponse:
        logger.error(
            "Unhandled exception on %s %s: %s",
            request.method,
            request.url.path,
            exc,
            exc_info=exc,
        )
        body = ErrorResponse(
            detail="Internal server error",
            code="internal_error",
            request_id=_request_id(),
        )
        return JSONResponse(status_code=500, content=body.model_dump())
