"""Task 7 — feed routes typed against the web client shapes.

`GET /daily_feed` returns a bare typed array. `POST /daily_feed/dismiss` moved to
the Go gateway in Phase 2 (docs/plans/2026-09-04-phase2-feed-to-go.md), so its
owner-check tests moved with it (services/backend-go/internal/feed/feed_test.go).
"""

import pytest

from app.api.dependencies import (
    get_openalex_service,
    get_pipeline_services,
)
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


@pytest.fixture(autouse=True)
def _overrides(app):
    app.dependency_overrides[get_pipeline_services] = lambda: _FakePipeline()
    app.dependency_overrides[get_openalex_service] = lambda: object()
    yield
    app.dependency_overrides.clear()


async def test_daily_feed_is_a_typed_array(client):
    r = await client.get("/api/v1/daily_feed", params={"author_id": "A1"})
    assert r.status_code == 200, r.text
    body = r.json()
    assert isinstance(body, list)
    [DailyFeedItem(**item) for item in body]
