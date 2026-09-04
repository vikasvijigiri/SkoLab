"""LLM-backed endpoints degrade to 503, never 500, when the AI provider fails.

A provider outage (Groq 401/429, circuit open, deadline) is transient and
retryable — clients should see 503 + Retry-After, not a 500 that reads as
"the server is broken". Covers the regression from the 2026-09-03 audit where
``/api/v1/author_metrics`` returned 500.

``GET /api/v1/author_metrics`` itself moved to the Go gateway (decisions/0010);
the LLM analysis step it used is now reached via
``POST /api/v1/internal/author_metrics_enrich``, and that is what must still
degrade to 503. The Go gateway degrades the 503 further to an empty bundle on
its own side — not exercised here.
"""

from __future__ import annotations

import pytest


@pytest.fixture
def _llm_down(monkeypatch):
    """The LLM analysis step raises."""
    from app.services.data import scraping_service

    async def _boom(*_a, **_k):
        raise RuntimeError("LLM query failed across all attempted models (injected)")

    monkeypatch.setattr(
        scraping_service.ScrapingService,
        "parse_content_to_json",
        _boom,
        raising=False,
    )
    yield


async def test_author_metrics_enrich_returns_503_not_500_when_llm_down(
    client, _llm_down
):
    r = await client.post(
        "/api/v1/internal/author_metrics_enrich",
        json={"context": "Title: A paper. Concepts: Topic X, Topic Y"},
    )
    assert r.status_code == 503, r.text
    body = r.json()
    assert body["code"] == "ai_unavailable"
    assert "request_id" in body
    assert r.headers.get("Retry-After") == "30"


async def test_author_metrics_enrich_requires_context(client):
    r = await client.post("/api/v1/internal/author_metrics_enrich", json={})
    assert r.status_code == 422
    assert r.json()["code"] == "validation_error"


async def test_old_author_metrics_get_route_is_gone_from_python(client):
    # Migrated to the Go gateway (internal/author/metrics.go) — decisions/0010.
    r = await client.get("/api/v1/author_metrics?author_id=A5052223708")
    assert r.status_code == 404
