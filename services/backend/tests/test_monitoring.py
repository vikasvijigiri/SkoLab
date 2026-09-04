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
async def test_status_endpoint_moved_to_go_gateway():
    """`GET /api/v1/status` moved to the Go gateway (decisions/0010).

    The status-shape + incidents-list assertions now live in
    ``services/backend-go/internal/system/system_test.go``. Python no longer
    serves the route.
    """
    async with httpx.AsyncClient(base_url="http://testserver", **client_args) as ac:
        response = await ac.get("/api/v1/status")
        assert response.status_code == 404
