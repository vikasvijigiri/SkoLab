import pytest
from httpx import AsyncClient, ASGITransport
from app.main import app

@pytest.mark.asyncio
async def test_zotero_auth_flow():
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        response = await client.get("/api/v1/integrations/zotero/auth?user_id=usr_123")
        assert response.status_code == 200
        body = response.json()
        assert "authorization_url" in body
        assert "oauth_token=mock_token_skolab_usr_123" in body["authorization_url"]

@pytest.mark.asyncio
async def test_zotero_callback_flow():
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        response = await client.get("/api/v1/integrations/zotero/callback?oauth_token=token&oauth_verifier=verifier")
        assert response.status_code == 200
        body = response.json()
        assert body["status"] == "success"
        assert "zotero_user_id" in body

@pytest.mark.asyncio
async def test_zotero_sync_flow():
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
        payload = {
            "user_id": "usr_123",
            "papers": [
                {"title": "Attention Is All You Need", "id": "W123"},
                {"title": "BERT: Pre-training of Deep Bidirectional Transformers", "id": "W456"}
            ]
        }
        response = await client.post("/api/v1/integrations/zotero/sync", json=payload)
        assert response.status_code == 200
        body = response.json()
        assert body["status"] == "success"
        assert body["synced_count"] == 2
        assert "Attention Is All You Need" in body["synced_papers"]
