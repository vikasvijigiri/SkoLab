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
