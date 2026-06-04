import os
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
async def test_metrics_endpoint_host_metrics():
    """Verify `/metrics` endpoint exports host CPU, memory, and disk metrics."""
    async with httpx.AsyncClient(base_url="http://testserver", **client_args) as ac:
        response = await ac.get("/metrics")
        assert response.status_code == 200
        content = response.text
        assert "host_cpu_usage_percent" in content
        assert "host_memory_used_percent" in content
        assert "host_disk_used_percent" in content

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

@pytest.mark.anyio
async def test_outbound_metrics_collection():
    """Verify outbound HTTP requests through `httpx` record latency and status code metrics."""
    from app.main import metrics_store
    
    # Reset outbound metrics before test
    with metrics_store.outbound_lock:
        metrics_store.outbound_request_counts.clear()
        metrics_store.outbound_request_latency.clear()

    # Trigger a mocked/real outbound HTTP call using httpx (which has been globally instrumented)
    async with httpx.AsyncClient() as client:
        try:
            # We query the root path to trigger interception without health/metrics keyword bypassing
            await client.get("http://localhost:8000/", timeout=1.0)
        except Exception:
            pass

    # Verify that the metrics_store recorded the host 'localhost' outbound call
    with metrics_store.outbound_lock:
        assert len(metrics_store.outbound_request_counts) > 0
        keys = list(metrics_store.outbound_request_counts.keys())
        assert any(k[0] == "localhost" for k in keys)
