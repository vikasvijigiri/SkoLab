import pytest
import httpx
from app.main import app

# Configure HTTPX AsyncClient transport compatibility
try:
    transport = httpx.ASGITransport(app=app)
    client_args = {"transport": transport}
except AttributeError:
    client_args = {"app": app}

# NOTE: test_metrics_endpoint_host_metrics and test_outbound_metrics_collection
# were removed — the per-process MetricsStore and GET /metrics were retired
# (docs/plans/2026-09-04-retire-python-infra.md). Request/outbound metrics are
# the Go gateway's concern now.


@pytest.mark.anyio
async def test_metrics_endpoint_removed():
    """GET /metrics is gone from the Python service."""
    async with httpx.AsyncClient(base_url="http://testserver", **client_args) as ac:
        response = await ac.get("/metrics")
        assert response.status_code == 404


@pytest.mark.anyio
async def test_status_endpoint():
    """Verify `/status` returns real-time availability states and incidents list."""
    async with httpx.AsyncClient(base_url="http://testserver", **client_args) as ac:
        response = await ac.get("/api/v1/status")  # router has /status endpoint
        assert response.status_code == 200
        data = response.json()
        assert "status" in data
        assert "services" in data
        assert "database" in data["services"]
        assert "cache_layer" in data["services"]
        assert "incidents" in data
        assert len(data["incidents"]) > 0
        assert data["incidents"][0]["title"] == "OpenAlex Upstream API Outage"
