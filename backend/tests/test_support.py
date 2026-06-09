import pytest
import httpx
from app.main import app

# Configure HTTPX AsyncClient transport compatibility
try:
    transport = httpx.ASGITransport(app=app)
    client_args = {"transport": transport}
except AttributeError:
    client_args = {"app": app}


@pytest.mark.anyio
async def test_get_support_metrics():
    """Verify support dashboard metrics endpoint returns expected structure."""
    async with httpx.AsyncClient(base_url="http://testserver", **client_args) as ac:
        response = await ac.get("/api/v1/support/metrics")
        assert response.status_code == 200
        data = response.json()
        assert "sla_targets" in data
        assert "performance_metrics" in data
        assert "queue_status" in data
        assert "vip_first_response_minutes" in data["sla_targets"]
        assert "total_open_tickets" in data["queue_status"]
