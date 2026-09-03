"""Task 11 — quest routes: already typed, now free of the str(e) 500 wrapper."""

import pytest

from app.api.dependencies import (
    get_openalex_service,
    get_quests_service,
    get_verified_user,
)


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
    # /users/quests is owner-scoped (require_owner("user_id")); default the
    # verified caller to "u1" and let individual tests vary the query param.
    app.dependency_overrides[get_verified_user] = lambda: {"uid": "u1"}
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


async def test_user_quests_owner_match_is_200(client):
    r = await client.get("/api/v1/users/quests", params={"user_id": "u1"})
    assert r.status_code == 200, r.text
    assert isinstance(r.json(), list)


async def test_user_quests_uid_mismatch_is_403(client):
    r = await client.get("/api/v1/users/quests", params={"user_id": "someone-else"})
    assert r.status_code == 403


async def test_user_quests_missing_token_is_401(client, app):
    app.dependency_overrides.pop(get_verified_user, None)
    r = await client.get("/api/v1/users/quests", params={"user_id": "u1"})
    assert r.status_code == 401


async def test_user_quests_requires_user_id(client):
    # Auth passes (override), but no user_id for require_owner to bind to → 400.
    r = await client.get("/api/v1/users/quests")
    assert r.status_code == 400
