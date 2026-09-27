"""Task 6 — papers routes typed; helper recovery left intact."""

import pytest

from app.api.dependencies import (
    get_openalex_service,
    get_pipeline_services,
    get_summarization_service,
    get_verified_user,
)
from app.core.exceptions import AIUnavailable
from app.schemas.core import PaperIntelligenceResponse
from app.schemas.papers_extra import (
    PresentationOutlineResponse,
    SemanticTrendingResponse,
    SummarizeWorkResponse,
)


class _FakeSummarization:
    def __init__(self, analyze_error: Exception | None = None):
        self._analyze_error = analyze_error

    async def summarize_paper(self, title, doi=None):
        return {"bullets": ["a", "b"], "metrics": {}, "top_skills": [], "status": "ok"}

    async def analyze_paper(self, title, doi=None, openalex_id=None):
        if self._analyze_error:
            raise self._analyze_error
        return {
            "tldr": "A concise summary.",
            "key_findings": [],
            "techniques": [],
            "tools_and_software": [],
            "core_concepts": [],
            "formulas": [],
            "limitations": [],
            "real_world_impact": "",
            "future_directions": [],
            "confidence": "High",
            "text_source": "pdf",
        }

    async def generate_presentation(self, title, doi=None):
        return {"slides": [{"title": "Intro"}]}


class _FakePipeline:
    async def _load_from_postgres(self, key):
        return None

    async def _save_to_postgres(self, key, val):
        return None


class _FakeOpenAlex:
    async def fetch_author_by_id(self, clean_id):
        return None


@pytest.fixture(autouse=True)
def _overrides(app):
    app.dependency_overrides[get_summarization_service] = lambda: _FakeSummarization()
    app.dependency_overrides[get_pipeline_services] = lambda: _FakePipeline()
    app.dependency_overrides[get_openalex_service] = lambda: _FakeOpenAlex()
    app.dependency_overrides[get_verified_user] = lambda: {"uid": "u1"}
    yield
    app.dependency_overrides.clear()


async def test_summarize_work_parses(client):
    r = await client.get("/api/v1/papers/summarize", params={"title": "Attention"})
    assert r.status_code == 200, r.text
    SummarizeWorkResponse(**r.json())


async def test_summarize_work_requires_title(client):
    r = await client.get("/api/v1/papers/summarize")
    assert r.status_code == 422
    assert r.json()["code"] == "validation_error"


async def test_presentation_outline_parses(client):
    r = await client.get("/api/v1/papers/presentation-outline", params={"title": "Attention"})
    assert r.status_code == 200, r.text
    PresentationOutlineResponse(**r.json())


async def test_analyze_paper_parses(client):
    r = await client.get(
        "/api/v1/papers/analyze", params={"title": "Attention Is All You Need"}
    )
    assert r.status_code == 200, r.text
    PaperIntelligenceResponse(**r.json())


async def test_analyze_paper_ai_unavailable_is_503_not_500(client, app):
    # Regression test (2026-09-12 endpoint audit): a call-time LLM failure
    # inside analyze_paper() used to re-raise as a bare Exception, which had
    # no route-level handler and leaked as an opaque 500 -- this app's
    # convention for a transient AI-dependency failure is AIUnavailable/503.
    app.dependency_overrides[get_summarization_service] = lambda: _FakeSummarization(
        analyze_error=AIUnavailable("Paper analysis is temporarily unavailable.")
    )
    r = await client.get(
        "/api/v1/papers/analyze", params={"title": "A Different Paper Title"}
    )
    assert r.status_code == 503, r.text
    assert r.json()["code"] == "ai_unavailable"


async def test_semantic_trending_parses_and_bounds_limit(client):
    ok = await client.get(
        "/api/v1/papers/semantic-trending", params={"author_id": "A123", "limit": 5}
    )
    assert ok.status_code == 200, ok.text
    SemanticTrendingResponse(**ok.json())

    bad = await client.get(
        "/api/v1/papers/semantic-trending", params={"author_id": "A123", "limit": 999}
    )
    assert bad.status_code == 422


async def test_analyze_is_metered_per_user(client, monkeypatch):
    from app.core import quota

    async def _none(*_a, **_k):
        return None

    monkeypatch.setenv("USER_QUOTA_ENABLED", "true")
    monkeypatch.setenv("USER_QUOTA_HOURLY_UNITS", "10")  # analyze costs 10
    monkeypatch.setattr(quota, "_redis_incr", _none)
    monkeypatch.setattr(quota, "_pg_incr", _none)
    quota._local.clear()
    try:
        first = await client.get("/api/v1/papers/analyze", params={"title": "Q1"})
        second = await client.get("/api/v1/papers/analyze", params={"title": "Q2"})
    finally:
        quota._local.clear()
    assert first.status_code == 200, first.text
    assert second.status_code == 429
    assert int(second.headers["Retry-After"]) >= 1
