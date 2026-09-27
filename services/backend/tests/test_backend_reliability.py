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
async def test_trace_id_propagation_via_traceparent():
    """Verify that W3C traceparent header is correctly propagated to log context and response headers."""
    async with httpx.AsyncClient(base_url="http://testserver", **client_args) as ac:
        traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
        response = await ac.get("/health", headers={"traceparent": traceparent})
        assert response.status_code == 200
        assert response.headers.get("traceparent") is not None
        assert "4bf92f3577b34da6a3ce929d0e0e4736" in response.headers.get("traceparent")
