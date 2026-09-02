"""Liveness / readiness split (SP-2).

``/livez`` must answer 200 regardless of dependency health — a DB or cache
outage should drain traffic (``/readyz`` → 503), not restart the process.
"""

import pytest

from app.schemas.system import LivenessResponse


@pytest.fixture
def _db_down(monkeypatch):
    """Make any DB session use raise, so check_readiness() reports the DB down."""
    import app.db.database as dbmod

    def _boom(*_a, **_k):
        raise RuntimeError("db is down (injected)")

    monkeypatch.setattr(dbmod, "AsyncSessionLocal", _boom)
    yield


async def test_livez_is_200_even_when_db_is_down(client, _db_down):
    r = await client.get("/livez")
    assert r.status_code == 200, r.text
    assert LivenessResponse(**r.json()).status == "alive"

    ready = await client.get("/readyz")
    assert ready.status_code == 503
    assert ready.json()["database"] == "unhealthy"


async def test_readyz_ok_in_ci(client):
    r = await client.get("/readyz")
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["status"] == "ready"
    assert "database" in body and "cache" in body


async def test_health_body_unchanged(client):
    r = await client.get("/health")
    assert r.status_code in (200, 503)
    assert set(r.json().keys()) == {"status", "database", "cache"}
