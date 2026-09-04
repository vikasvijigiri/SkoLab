import pytest
import httpx
from httpx import AsyncClient
from app.main import app


def test_init_observability_is_inert_without_dsn(monkeypatch):
    """No SENTRY_DSN -> init_observability() is a no-op, Sentry stays inactive."""
    from types import SimpleNamespace

    import sentry_sdk

    from app.core import observability as obs_mod

    # `Settings` is a frozen dataclass — swap the module-level reference for a
    # stand-in with an empty DSN rather than mutating the frozen instance.
    monkeypatch.setattr(
        obs_mod,
        "settings",
        SimpleNamespace(sentry_dsn="", environment="test"),
    )

    assert obs_mod.init_observability() is None  # does not raise

    client = sentry_sdk.get_client()
    is_active = getattr(client, "is_active", None)
    if callable(is_active):
        assert is_active() is False
    else:  # pragma: no cover - older sentry_sdk API
        assert sentry_sdk.Hub.current.client is None


def test_init_observability_with_dsn_does_not_crash(monkeypatch):
    """With a DSN set, init_observability() must not raise.

    Regression test for a prod-only boot crash: sentry_sdk's
    StarletteIntegration unconditionally imports jinja2 during
    ``sentry_sdk.init()`` (to patch Jinja2Templates), even though this API
    never renders HTML templates. `jinja2` wasn't a direct dependency, so
    every deploy with SENTRY_DSN set crashed on import of app.main before
    the process could bind a port. Local dev and CI both blank SENTRY_DSN
    (see conftest.py), so this path only ran for the first time in a real
    deploy — this test exercises it locally instead.
    """
    from types import SimpleNamespace

    import sentry_sdk

    from app.core import observability as obs_mod

    monkeypatch.setattr(
        obs_mod,
        "settings",
        SimpleNamespace(
            sentry_dsn="https://public@sentry.example.invalid/1",
            environment="test",
        ),
    )

    try:
        obs_mod.init_observability()  # must not raise ImportError
        assert sentry_sdk.get_client().is_active() is True
    finally:
        # Don't leak an active client into other tests.
        sentry_sdk.init(dsn=None)


@pytest.mark.anyio
async def test_health_endpoint():
    transport = httpx.ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as ac:
        response = await ac.get("/health")
        assert response.status_code in [200, 503]
        data = response.json()
        assert "status" in data
        assert "database" in data
        assert "cache" in data


@pytest.mark.anyio
async def test_metrics_endpoint_removed():
    """Python no longer serves Prometheus metrics — the per-process MetricsStore
    and GET /metrics were retired; the Go gateway owns request metrics."""
    transport = httpx.ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as ac:
        response = await ac.get("/metrics")
        assert response.status_code == 404


@pytest.mark.anyio
async def test_kill_switch():
    import os

    # 1. Enable kill switch for "quests"
    os.environ["KILL_SWITCHES"] = "quests"
    transport = httpx.ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as ac:
        response = await ac.get("/api/v1/quests")
        assert response.status_code == 503
        data = response.json()
        assert "disabled via SRE kill switch" in data["detail"]

    # 2. Disable kill switch
    os.environ["KILL_SWITCHES"] = ""
    async with AsyncClient(transport=transport, base_url="http://test") as ac:
        response = await ac.get("/api/v1/quests")
        # Should bypass kill switch (returns 401 Unauthorized or 404 Not Found, but NOT 503)
        assert response.status_code != 503


@pytest.mark.anyio
async def test_traceparent_still_propagates():
    """The structured-log middleware keeps W3C trace context even though the
    metrics-store calls were removed from it."""
    transport = httpx.ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as ac:
        traceparent = "00-1234567890abcdef1234567890abcdef-1234567890abcdef-01"
        response = await ac.get("/health", headers={"traceparent": traceparent})
        assert "1234567890abcdef1234567890abcdef" in response.headers.get(
            "traceparent", ""
        )
