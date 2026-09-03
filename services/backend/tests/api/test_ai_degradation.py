"""LLM-backed endpoints degrade to 503, never 500, when the AI provider fails.

A provider outage (Groq 401/429, circuit open, deadline) is transient and
retryable — clients should see 503 + Retry-After, not a 500 that reads as
"the server is broken". Covers the regression from the 2026-09-03 audit where
``/api/v1/author_metrics`` returned 500.
"""

from __future__ import annotations

import pytest


@pytest.fixture
def _llm_down(monkeypatch):
    """OpenAlex returns works; the LLM analysis step raises."""
    from app.services.data import openalex_service
    from app.services.data import scraping_service

    async def _works(*_a, **_k):
        return [
            {"title": "A paper", "concepts": [{"display_name": "Topic X"}]},
            {"title": "Another paper", "concepts": [{"display_name": "Topic Y"}]},
        ]

    async def _boom(*_a, **_k):
        raise RuntimeError("LLM query failed across all attempted models (injected)")

    monkeypatch.setattr(
        openalex_service.OpenAlexService, "fetch_author_works", _works, raising=False
    )
    monkeypatch.setattr(
        scraping_service.ScrapingService,
        "parse_content_to_json",
        _boom,
        raising=False,
    )
    yield


async def test_author_metrics_returns_503_not_500_when_llm_down(client, _llm_down):
    r = await client.get("/api/v1/author_metrics?author_id=A5052223708")
    assert r.status_code == 503, r.text
    body = r.json()
    assert body["code"] == "ai_unavailable"
    assert "request_id" in body
    assert r.headers.get("Retry-After") == "30"


async def test_author_metrics_bad_id_is_still_422(client, monkeypatch):
    """The 4xx the route deliberately owns (unresolvable author) is unchanged."""
    from app.services.data import openalex_service

    async def _no_works(*_a, **_k):
        return []

    monkeypatch.setattr(
        openalex_service.OpenAlexService,
        "fetch_author_works",
        _no_works,
        raising=False,
    )
    r = await client.get("/api/v1/author_metrics?author_id=A0")
    assert r.status_code == 422, r.text
