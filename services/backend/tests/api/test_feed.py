"""Task 7 — feed routes typed against the web client shapes.

`/daily_feed/dismiss` is owner-scoped (see docs/backend-auth-posture.md): it
verifies the Firebase token and requires the caller's linked
`users.openalex_id` to equal the body `author_id`.
"""

import pytest

from app.api.dependencies import (
    get_db,
    get_openalex_service,
    get_pipeline_services,
    get_verified_user,
)
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


class _FakeResult:
    def __init__(self, val):
        self._val = val

    def scalar_one_or_none(self):
        return self._val


class _FakeSession:
    """Yields a fixed `users.openalex_id` for the dismiss ownership lookup."""

    def __init__(self, linked_openalex_id):
        self._oid = linked_openalex_id

    async def execute(self, *args, **kwargs):
        return _FakeResult(self._oid)


def _db_yielding(linked_openalex_id):
    async def _gen():
        yield _FakeSession(linked_openalex_id)

    return _gen


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


async def test_dismiss_owner_match_returns_success_envelope(app, client):
    app.dependency_overrides[get_verified_user] = lambda: {"uid": "u1"}
    app.dependency_overrides[get_db] = _db_yielding("A1")
    r = await client.post(
        "/api/v1/daily_feed/dismiss", json={"author_id": "A1", "work_id": "W1"}
    )
    assert r.status_code == 200, r.text
    assert DismissResponse(**r.json()).success is True


async def test_dismiss_wrong_owner_is_403(app, client):
    app.dependency_overrides[get_verified_user] = lambda: {"uid": "u1"}
    app.dependency_overrides[get_db] = _db_yielding("A_SOMEONE_ELSE")
    r = await client.post(
        "/api/v1/daily_feed/dismiss", json={"author_id": "A1", "work_id": "W1"}
    )
    assert r.status_code == 403, r.text


async def test_dismiss_unlinked_user_is_403(app, client):
    app.dependency_overrides[get_verified_user] = lambda: {"uid": "u1"}
    app.dependency_overrides[get_db] = _db_yielding(None)
    r = await client.post(
        "/api/v1/daily_feed/dismiss", json={"author_id": "A1", "work_id": "W1"}
    )
    assert r.status_code == 403, r.text


async def test_dismiss_without_token_is_401(client):
    r = await client.post(
        "/api/v1/daily_feed/dismiss", json={"author_id": "A1", "work_id": "W1"}
    )
    assert r.status_code == 401, r.text


async def test_dismiss_rejects_missing_field(app, client):
    app.dependency_overrides[get_verified_user] = lambda: {"uid": "u1"}
    app.dependency_overrides[get_db] = _db_yielding("A1")
    r = await client.post("/api/v1/daily_feed/dismiss", json={"author_id": "A1"})
    assert r.status_code == 422
    assert r.json()["code"] == "validation_error"
