import httpx
import pytest

from app.main import app


@pytest.mark.anyio
async def test_status_endpoint_is_owned_by_the_go_gateway():
    """The FastAPI service must not expose the gateway status endpoint."""
    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(
        transport=transport, base_url="http://testserver"
    ) as client:
        response = await client.get("/api/v1/status")

    assert response.status_code == 404
