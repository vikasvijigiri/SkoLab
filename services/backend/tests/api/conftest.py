"""Fixtures for the API contract suite.

The parent ``tests/conftest.py`` already points ``DATABASE_URL`` at a live
Postgres or a throwaway SQLite file and runs ``init_db``. This conftest adds an
ASGI client bound to the real application plus opt-in fakes for the external
boundary (OpenAlex, the LLM services). Nothing here calls the network.
"""

from __future__ import annotations

from typing import AsyncIterator

import httpx
import pytest
import pytest_asyncio


@pytest.fixture(scope="session")
def app():
    from app.main import app as fastapi_app

    return fastapi_app


@pytest_asyncio.fixture
async def client(app) -> AsyncIterator[httpx.AsyncClient]:
    transport = httpx.ASGITransport(app=app, raise_app_exceptions=False)
    async with httpx.AsyncClient(
        transport=transport, base_url="http://testserver"
    ) as c:
        yield c


@pytest.fixture
def mock_openalex(monkeypatch):
    """Patch OpenAlexService network methods with deterministic stand-ins."""
    from app.services.data import openalex_service

    async def _fake_get_author(*_a, **_k):
        return {
            "id": "https://openalex.org/A123",
            "display_name": "Ada Lovelace",
            "works_count": 3,
            "cited_by_count": 42,
            "summary_stats": {"h_index": 2, "i10_index": 1},
        }

    async def _fake_search(*_a, **_k):
        return []

    for name, fn in (
        ("get_author_by_id", _fake_get_author),
        ("search_authors", _fake_search),
        ("search_works", _fake_search),
    ):
        if hasattr(openalex_service.OpenAlexService, name):
            monkeypatch.setattr(
                openalex_service.OpenAlexService, name, fn, raising=False
            )
    return openalex_service


@pytest.fixture
def mock_llm(monkeypatch):
    """Patch the LLM services so no model call leaves the process."""
    from app.services.ai import llm_service

    async def _fake_complete(*_a, **_k):
        return "deterministic test completion"

    for name in ("complete", "chat", "generate", "acomplete"):
        if hasattr(llm_service.LLMService, name):
            monkeypatch.setattr(
                llm_service.LLMService, name, _fake_complete, raising=False
            )
    return llm_service
