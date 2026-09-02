"""Task 11 — /recommendations/peers/invite is typed."""

import pytest

from app.domains.recommendation import router as rec_router
from app.schemas.recommendation_extra import LogPeerInviteResponse


class _FakeService:
    def __init__(self, *a, **k):
        pass

    async def log_peer_invite(self, request):
        return True


@pytest.fixture(autouse=True)
def _patch_service(monkeypatch):
    monkeypatch.setattr(rec_router, "RecommendationService", _FakeService)
    yield


async def test_log_peer_invite_parses(client):
    r = await client.post(
        "/api/v1/recommendations/peers/invite",
        json={"user_id": "u1", "peer_email": "a@b.com"},
    )
    assert r.status_code == 200, r.text
    assert LogPeerInviteResponse(**r.json()).success is True


async def test_log_peer_invite_requires_user_id(client):
    r = await client.post(
        "/api/v1/recommendations/peers/invite", json={"peer_email": "a@b.com"}
    )
    assert r.status_code == 422
    assert r.json()["code"] == "validation_error"
