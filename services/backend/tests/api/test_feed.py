"""Task 7 — feed routes typed against the web client shapes.

`GET /daily_feed` returns a bare typed array. `POST /daily_feed/dismiss` moved to
the Go gateway in Phase 2 (docs/plans/2026-09-04-phase2-feed-to-go.md), so its
owner-check tests moved with it (services/backend-go/internal/feed/feed_test.go).
"""

import asyncio

import pytest

from app.api.dependencies import (
    get_openalex_service,
    get_pipeline_services,
)
from app.api.v1.endpoints import feed as feed_module
from app.core import pending_compute
from app.schemas.feed_extra import DailyFeedItem
from app.services.platform.pipeline.text_utils import bare_openalex_id


class _FakeOpenAlexForConjecture:
    """Just enough of OpenAlexService for /daily_conjecture to resolve an
    author and its works without a network call."""

    async def fetch_author_by_id(self, author_id):
        return {"id": f"https://openalex.org/{author_id}", "display_name": "Ada Lovelace", "field_of_study": "Physics"}

    async def search_authors(self, name, per_page=1):
        return [{"id": "https://openalex.org/A1", "display_name": name, "field_of_study": "Physics"}]

    async def fetch_author_works(self, author_id, per_page=5):
        return [{"title": "On quantum decoherence", "abstract": "A study of qubit coherence."}]


class _FakePipeline:
    async def get_daily_feed(self, author_id, query_fallback=None):
        return [
            {
                "id": "W1",
                "title": "A paper",
                "authors": ["Ada Lovelace"],
                "journal": "Nature",
                "year": 2024,
                "relevance_score": 0.9,
                "recommendation_reason": "matches your topics",
            }
        ]


class _SlowPipeline:
    """Never finishes within a test-scale wait_timeout — exercises the
    202/Retry-After path in app/core/pending_compute.py's docstring."""

    async def get_daily_feed(self, author_id, query_fallback=None):
        # Comfortably above the test's 0.02s wait_timeout, short enough
        # that the background task finishes on its own well before the
        # test process exits (no orphaned-task teardown warning).
        await asyncio.sleep(1.0)
        return []  # pragma: no cover - the test asserts before this returns


@pytest.fixture(autouse=True)
def _overrides(app):
    app.dependency_overrides[get_pipeline_services] = lambda: _FakePipeline()
    app.dependency_overrides[get_openalex_service] = lambda: object()
    yield
    app.dependency_overrides.clear()
    pending_compute._inflight.clear()


@pytest.mark.parametrize(
    ("raw", "expected"),
    [
        ("https://openalex.org/W7206172422", "W7206172422"),
        ("https://api.openalex.org/works/W123", "W123"),
        ("https://openalex.org/W123/", "W123"),
        ("W7206172422", "W7206172422"),
        ("", ""),
        (None, None),
    ],
)
def test_bare_openalex_id_normalises_daily_feed_ids(raw, expected):
    # daily_feed items must carry the bare id — clients build `/paper/<id>` from
    # it and the canonical URL form 400s once URL-encoded.
    assert bare_openalex_id(raw) == expected


async def test_daily_feed_is_a_typed_array(client):
    r = await client.get("/api/v1/daily_feed", params={"author_id": "A1"})
    assert r.status_code == 200, r.text
    body = r.json()
    assert isinstance(body, list)
    [DailyFeedItem(**item) for item in body]


async def test_daily_feed_returns_202_retry_after_when_compute_is_slow(
    app, client, monkeypatch
):
    app.dependency_overrides[get_pipeline_services] = lambda: _SlowPipeline()
    monkeypatch.setattr(feed_module, "DAILY_FEED_WAIT_TIMEOUT_SECONDS", 0.02)

    r = await client.get("/api/v1/daily_feed", params={"author_id": "A_SLOW"})

    assert r.status_code == 202
    assert r.headers["Retry-After"] == str(feed_module.DAILY_FEED_RETRY_AFTER_SECONDS)
    assert r.json() == []
    # The background task is still registered -- a retry will join it
    # rather than starting the expensive pipeline over again.
    assert any(k.startswith("daily_feed:A_SLOW:") for k in pending_compute._inflight)


@pytest.mark.parametrize(
    ("field_of_study", "expected_id"),
    [
        ("Quantum Physics", "fallback_conjecture_phys"),
        ("Computer Science", "fallback_conjecture_cs"),
        ("Molecular Biology", "fallback_conjecture_bio"),
        ("History", "fallback_conjecture_gen"),
    ],
)
def test_generate_fallback_conjecture_is_flagged_and_field_matched(field_of_study, expected_id):
    """The one path in this file with no prior coverage at all (2026-09-11
    backend response audit): a canned puzzle keyed off the author's field,
    served with a normal 200 and, before this, no signal it wasn't actually
    generated from the author's own publications."""
    conjecture = feed_module.generate_fallback_conjecture({"field_of_study": field_of_study})

    assert conjecture.id == expected_id
    assert conjecture.is_fallback is True


async def test_daily_conjecture_flags_fallback_and_caches_it(app, client, monkeypatch):
    app.dependency_overrides[get_openalex_service] = lambda: _FakeOpenAlexForConjecture()
    # Forces the except-block fallback path deterministically, without
    # needing to fake an actual LLM failure.
    monkeypatch.setattr(feed_module, "is_llm_working", lambda: False)

    r = await client.get("/api/v1/daily_conjecture", params={"author_id": "A_FALLBACK"})

    assert r.status_code == 200, r.text
    body = r.json()
    assert body["is_fallback"] is True

    # Cached under the same key -- a second request must not need OpenAlex
    # again, and must still honestly report the flag from the cached copy.
    app.dependency_overrides[get_openalex_service] = lambda: object()
    r2 = await client.get("/api/v1/daily_conjecture", params={"author_id": "A_FALLBACK"})
    assert r2.status_code == 200, r2.text
    assert r2.json()["is_fallback"] is True
