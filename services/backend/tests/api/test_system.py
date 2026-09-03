"""Task 4 — system + app-level routes are typed."""

from app.schemas.system import (
    AiStatusResponse,
    AppInfoResponse,
    RootResponse,
    SystemStatusResponse,
)


async def test_root_router_route_parses(client):
    r = await client.get("/api/v1/")
    assert r.status_code == 200
    RootResponse(**r.json())


async def test_app_root_parses(client):
    # The bare-prefix router mount was removed (Stream B security hardening),
    # so `/` is now served by main.py's own `@app.get("/")` handler
    # (AppInfoResponse), not the API router's read_root. The router's own
    # root is still reachable at `/api/v1/` — see test_root_router_route_parses.
    r = await client.get("/")
    assert r.status_code == 200
    AppInfoResponse(**r.json())


async def test_ai_status_parses(client):
    r = await client.get("/api/v1/ai_status")
    assert r.status_code == 200
    AiStatusResponse(**r.json())


async def test_system_status_parses(client):
    r = await client.get("/api/v1/status")
    assert r.status_code == 200
    SystemStatusResponse(**r.json())


async def test_health_still_serves(client):
    r = await client.get("/health")
    assert r.status_code in (200, 503)
    assert r.json()["status"] in ("healthy", "unhealthy")
