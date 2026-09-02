"""Task 1 — app-level error envelope.

Builds a throwaway app with the real handlers attached so the contract is
tested in isolation from the full application import graph.
"""

import httpx
import pytest
from fastapi import FastAPI, Query

from app.api.errors import ErrorResponse, register_exception_handlers


def _app() -> FastAPI:
    app = FastAPI()
    register_exception_handlers(app)

    @app.get("/boom")
    async def boom():
        raise ValueError("secret internal detail: db password = hunter2")

    @app.get("/needs-param")
    async def needs_param(q: str = Query(...)):
        return {"q": q}

    return app


@pytest.mark.asyncio
async def test_unhandled_exception_returns_generic_envelope():
    transport = httpx.ASGITransport(app=_app(), raise_app_exceptions=False)
    async with httpx.AsyncClient(transport=transport, base_url="http://t") as c:
        r = await c.get("/boom")

    assert r.status_code == 500
    body = r.json()
    parsed = ErrorResponse(**body)
    assert parsed.code == "internal_error"
    assert parsed.detail == "Internal server error"
    assert "ValueError" not in r.text
    assert "hunter2" not in r.text


@pytest.mark.asyncio
async def test_missing_required_param_returns_validation_envelope():
    transport = httpx.ASGITransport(app=_app(), raise_app_exceptions=False)
    async with httpx.AsyncClient(transport=transport, base_url="http://t") as c:
        r = await c.get("/needs-param")

    assert r.status_code == 422
    body = r.json()
    parsed = ErrorResponse(**body)
    assert parsed.code == "validation_error"
    assert parsed.errors and any(e["type"] == "missing" for e in parsed.errors)
