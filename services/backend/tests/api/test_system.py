"""System + app-level routes.

``GET /`` (API-router root) and ``GET /status`` moved to the Go gateway
(``services/backend-go/internal/system``) — decisions/0010. This suite now
asserts they are gone from Python and that the routes that stayed
(``/ai_status``, the app-level ``/``, ``/health``) still serve.
"""

from app.schemas.system import AiStatusResponse, AppInfoResponse


async def test_api_router_root_is_gone_from_python(client):
    # Served by the Go gateway now (system.Root). Python no longer registers it.
    r = await client.get("/api/v1/")
    assert r.status_code == 404


async def test_system_status_is_gone_from_python(client):
    # Served by the Go gateway now (system.Status).
    r = await client.get("/api/v1/status")
    assert r.status_code == 404


async def test_app_root_parses(client):
    # main.py's own ``@app.get("/")`` (AppInfoResponse) is untouched — it is the
    # mobile-client discovery route, not an API-router route.
    r = await client.get("/")
    assert r.status_code == 200
    AppInfoResponse(**r.json())


async def test_ai_status_parses(client):
    # Stays in Python — it reports the LLM key/health.
    r = await client.get("/api/v1/ai_status")
    assert r.status_code == 200
    AiStatusResponse(**r.json())


async def test_health_still_serves(client):
    r = await client.get("/health")
    assert r.status_code in (200, 503)
    assert r.json()["status"] in ("healthy", "unhealthy")
