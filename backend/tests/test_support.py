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
async def test_create_support_ticket_standard():
    """Verify that a standard support ticket is processed correctly."""
    async with httpx.AsyncClient(base_url="http://testserver", **client_args) as ac:
        payload = {
            "email": "test-user@skolab.open",
            "subject": "Unable to load career roadmap",
            "message": "When I click on build my index, nothing happens. Please help.",
            "priority": "standard",
            "name": "Jane Doe"
        }
        response = await ac.post("/api/v1/support/ticket", json=payload)
        assert response.status_code == 200
        data = response.json()
        assert "ticket_id" in data
        assert data["status"] == "Received"
        assert data["is_vip"] is False
        assert "Jane Doe" in data["auto_responder"]
        assert "4 hours" in data["auto_responder"]


@pytest.mark.anyio
async def test_create_support_ticket_vip():
    """Verify that a VIP support ticket is prioritized and routes to VIP queue."""
    async with httpx.AsyncClient(base_url="http://testserver", **client_args) as ac:
        payload = {
            "email": "vip-user@skolab.open",
            "subject": "Urgent API Access Failure",
            "message": "Our institutional crawler has run out of tokens. Re-allocate.",
            "priority": "vip",
            "name": "Prof. Smith"
        }
        response = await ac.post("/api/v1/support/ticket", json=payload)
        assert response.status_code == 200
        data = response.json()
        assert "ticket_id" in data
        assert data["status"] == "Priority Queued"
        assert data["is_vip"] is True
        assert "Prof. Smith" in data["auto_responder"]
        assert "15 minutes" in data["auto_responder"]


@pytest.mark.anyio
async def test_create_support_ticket_validation_failures():
    """Verify that invalid inputs are rejected with 422 Unprocessable Entity."""
    async with httpx.AsyncClient(base_url="http://testserver", **client_args) as ac:
        # Invalid email
        payload_invalid_email = {
            "email": "not-an-email",
            "subject": "Missing paper search results",
            "message": "Paper list is empty for my profile.",
            "priority": "standard"
        }
        response = await ac.post("/api/v1/support/ticket", json=payload_invalid_email)
        assert response.status_code == 422

        # Invalid priority value
        payload_invalid_priority = {
            "email": "user@skolab.open",
            "subject": "Missing paper search results",
            "message": "Paper list is empty for my profile.",
            "priority": "ultra-priority"
        }
        response = await ac.post("/api/v1/support/ticket", json=payload_invalid_priority)
        assert response.status_code == 422


@pytest.mark.anyio
async def test_get_support_metrics():
    """Verify support dashboard metrics return expected fields and ticket count updates."""
    async with httpx.AsyncClient(base_url="http://testserver", **client_args) as ac:
        response = await ac.get("/api/v1/support/metrics")
        assert response.status_code == 200
        data = response.json()
        assert "sla_targets" in data
        assert "performance_metrics" in data
        assert "queue_status" in data
        
        # Verify queue metrics reflect previous successful posts
        assert data["queue_status"]["total_open_tickets"] >= 2
        assert data["queue_status"]["vip_escalation_queue_size"] >= 1
