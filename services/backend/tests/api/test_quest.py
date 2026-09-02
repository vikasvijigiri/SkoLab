"""Task 11 — quest routes: already typed, now free of the str(e) 500 wrapper."""

import pytest

from app.api.dependencies import get_openalex_service, get_quests_service


class _FakeQuests:
    async def get_leaderboard(self, field):
        if field == "__unknown__":
            raise ValueError("no such field")
        return []

    async def get_user_quests(self, user_id, openalex_service):
        return []


@pytest.fixture(autouse=True)
def _overrides(app):
    app.dependency_overrides[get_quests_service] = lambda: _FakeQuests()
    app.dependency_overrides[get_openalex_service] = lambda: object()
    yield
    app.dependency_overrides.clear()


async def test_leaderboard_returns_array(client):
    r = await client.get("/api/v1/leaderboard/physics")
    assert r.status_code == 200, r.text
    assert isinstance(r.json(), list)


async def test_leaderboard_unknown_field_is_empty_not_500(client):
    r = await client.get("/api/v1/leaderboard/__unknown__")
    assert r.status_code == 200
    assert r.json() == []


async def test_user_quests_requires_user_id(client):
    r = await client.get("/api/v1/users/quests")
    assert r.status_code == 422
    assert r.json()["code"] == "validation_error"
