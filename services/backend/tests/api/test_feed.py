"""Task 7 — feed routes typed against the web client shapes."""

import pytest

from app.api.dependencies import get_openalex_service, get_pipeline_services
from app.schemas.feed_extra import DailyFeedItem, DismissResponse


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

    async def dismiss_recommendation(self, author_id, work_id):
        return None


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


async def test_dismiss_returns_success_envelope(client):
    r = await client.post(
        "/api/v1/daily_feed/dismiss", json={"author_id": "A1", "work_id": "W1"}
    )
    assert r.status_code == 200, r.text
    assert DismissResponse(**r.json()).success is True


async def test_dismiss_rejects_missing_field(client):
    r = await client.post("/api/v1/daily_feed/dismiss", json={"author_id": "A1"})
    assert r.status_code == 422
    assert r.json()["code"] == "validation_error"
