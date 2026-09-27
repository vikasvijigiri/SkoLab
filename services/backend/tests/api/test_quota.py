"""Per-user cost quotas (app/core/quota.py + api.dependencies.require_quota)."""

from __future__ import annotations

import httpx
import pytest
from fastapi import Depends, FastAPI
from sqlalchemy import create_engine, text

from app.api.dependencies import get_verified_user, require_quota
from app.core import quota

_REAL_REDIS_INCR = quota._redis_incr  # captured before the fixture stubs it


@pytest.fixture(autouse=True)
def _quota_env(monkeypatch):
    monkeypatch.setenv("USER_QUOTA_ENABLED", "true")
    monkeypatch.setenv("USER_QUOTA_HOURLY_UNITS", "10")
    monkeypatch.setenv("USER_QUOTA_DAILY_UNITS", "25")
    quota._local.clear()

    async def _none(*_a, **_k):
        return None

    # Force the process-local backend so these tests need no Redis / Postgres.
    monkeypatch.setattr(quota, "_redis_incr", _none)
    monkeypatch.setattr(quota, "_pg_incr", _none)
    yield
    quota._local.clear()


async def test_hourly_budget_is_enforced_per_user():
    now = 1_000_000.0
    await quota.consume("alice", 4, now=now)
    await quota.consume("alice", 4, now=now)
    with pytest.raises(quota.QuotaExceeded) as err:
        await quota.consume("alice", 4, now=now)
    assert err.value.window == "hourly"
    assert 1 <= err.value.retry_after <= quota.HOUR
    # another account is unaffected
    assert (await quota.consume("bob", 4, now=now))["hourly"] == 6


async def test_window_rolls_over():
    now = 1_000_000.0
    await quota.consume("alice", 10, now=now)
    with pytest.raises(quota.QuotaExceeded):
        await quota.consume("alice", 1, now=now)
    later = now + quota.HOUR
    assert (await quota.consume("alice", 1, now=later))["hourly"] == 9


async def test_daily_budget_is_enforced_across_hours():
    base = 86_400.0 * 50  # start of a UTC day
    for hour in range(2):
        await quota.consume("carol", 10, now=base + hour * quota.HOUR)
    with pytest.raises(quota.QuotaExceeded) as err:
        await quota.consume("carol", 10, now=base + 2 * quota.HOUR)
    assert err.value.window == "daily"


async def test_disabled_is_a_noop(monkeypatch):
    monkeypatch.setenv("USER_QUOTA_ENABLED", "false")
    for _ in range(50):
        assert await quota.consume("dave", 100) == {}


def test_postgres_upsert_accumulates_atomically_per_key():
    engine = create_engine("sqlite://")
    with engine.begin() as conn:
        conn.execute(
            text(
                "CREATE TABLE usage_counters (bucket_key TEXT PRIMARY KEY, "
                "count INTEGER NOT NULL, expires_at TEXT NOT NULL)"
            )
        )
        args = {"exp": "2030-01-01"}
        seen = [
            conn.execute(quota._UPSERT, {"k": "a", "n": n, **args}).scalar_one()
            for n in (3, 4, 5)
        ]
        other = conn.execute(quota._UPSERT, {"k": "b", "n": 2, **args}).scalar_one()
    assert seen == [3, 7, 12]
    assert other == 2


async def test_redis_backend_is_used_when_available(monkeypatch):
    calls = []

    class _Pipe:
        def __init__(self):
            self.n = 0

        def incrby(self, key, cost):
            calls.append(("incrby", key, cost))
            self.n = cost

        def expireat(self, key, ts):
            calls.append(("expireat", key, ts))

        async def execute(self):
            return [self.n, True]

    class _Redis:
        def pipeline(self, transaction=True):
            assert transaction
            return _Pipe()

    from app.db import pg_cache

    monkeypatch.setattr(pg_cache, "_redis_client", _Redis())
    monkeypatch.setattr(pg_cache, "_redis_active", True)
    used = await _REAL_REDIS_INCR("q:x:h:1", 3, 7200.0)
    assert used == 3
    assert [c[0] for c in calls] == ["incrby", "expireat"]


def _probe_app() -> FastAPI:
    app = FastAPI()
    app.dependency_overrides[get_verified_user] = lambda: {"uid": "u1"}

    @app.get("/cheap")
    async def cheap(_u: dict = Depends(require_quota(1))):
        return {"ok": True}

    @app.get("/pricey")
    async def pricey(_u: dict = Depends(require_quota(6))):
        return {"ok": True}

    return app


async def test_dependency_returns_429_with_retry_after_and_headers():
    app = _probe_app()
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app), base_url="http://t"
    ) as c:
        first = await c.get("/pricey")
        assert first.status_code == 200
        assert first.headers["X-Quota-Remaining-Hour"] == "4"
        second = await c.get("/pricey")  # 12 > 10
        assert second.status_code == 429
        assert int(second.headers["Retry-After"]) >= 1
        # cost weighting: a cheap route was not yet charged, but the hour
        # bucket is already over budget for this account.
        third = await c.get("/cheap")
        assert third.status_code == 429


async def test_dependency_requires_authentication():
    app = FastAPI()

    @app.get("/x")
    async def x(_u: dict = Depends(require_quota(1))):
        return {}

    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app), base_url="http://t"
    ) as c:
        assert (await c.get("/x")).status_code == 401
