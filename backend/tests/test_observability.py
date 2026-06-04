import pytest
import httpx
from httpx import AsyncClient
from app.main import app, metrics_store

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
async def test_metrics_endpoint():
    await metrics_store.record_request("GET", "/test-endpoint", 200, 15.0)
    
    transport = httpx.ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as ac:
        response = await ac.get("/metrics")
        assert response.status_code == 200
        content = response.text
        
        assert "# HELP http_requests_total" in content
        assert "# TYPE http_requests_total counter" in content
        assert 'http_requests_total{method="GET",endpoint="/test-endpoint",status="200"}' in content
        
        assert "# HELP http_active_requests" in content
        assert "# TYPE http_active_requests gauge" in content
        assert "http_active_requests" in content
        
        assert "# HELP http_request_duration_seconds" in content
        assert "# TYPE http_request_duration_seconds histogram" in content
        assert 'http_request_duration_seconds_bucket{method="GET",endpoint="/test-endpoint",le="0.100"}' in content
        assert 'http_request_duration_seconds_sum{method="GET",endpoint="/test-endpoint"}' in content
        assert 'http_request_duration_seconds_count{method="GET",endpoint="/test-endpoint"}' in content


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
async def test_openalex_api_requests_metric_increment():
    from app.main import metrics_store
    initial_count = metrics_store.openalex_api_requests_total
    
    # Trigger an async HTTP request to openalex.org
    try:
        async with httpx.AsyncClient() as client:
            await client.get("https://api.openalex.org/mock-test-endpoint")
    except Exception:
        # Catch connection errors safely since daemon is offline
        pass
        
    assert metrics_store.openalex_api_requests_total == initial_count + 1


@pytest.mark.anyio
async def test_background_tasks_metrics_tracking():
    from app.main import metrics_store
    
    initial_active = metrics_store.background_tasks_active
    
    await metrics_store.increment_background_tasks()
    assert metrics_store.background_tasks_active == initial_active + 1
    
    await metrics_store.decrement_background_tasks()
    assert metrics_store.background_tasks_active == initial_active


