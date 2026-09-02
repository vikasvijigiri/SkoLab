"""Task 9 — Zotero integration routes are typed."""

from app.schemas.integrations import (
    ZoteroAuthResponse,
    ZoteroCallbackResponse,
    ZoteroSyncResponse,
)


async def test_zotero_auth_parses(client):
    r = await client.get("/api/v1/integrations/zotero/auth", params={"user_id": "u1"})
    assert r.status_code == 200, r.text
    ZoteroAuthResponse(**r.json())


async def test_zotero_callback_parses(client):
    r = await client.get(
        "/api/v1/integrations/zotero/callback",
        params={"oauth_token": "t", "oauth_verifier": "v"},
    )
    assert r.status_code == 200, r.text
    ZoteroCallbackResponse(**r.json())


async def test_zotero_callback_missing_token_is_422(client):
    r = await client.get("/api/v1/integrations/zotero/callback")
    assert r.status_code == 422
    assert r.json()["code"] == "validation_error"


async def test_zotero_sync_parses(client):
    r = await client.post(
        "/api/v1/integrations/zotero/sync",
        json={"user_id": "u1", "papers": [{"title": "A paper"}, {}]},
    )
    assert r.status_code == 200, r.text
    model = ZoteroSyncResponse(**r.json())
    assert model.synced_count == 2
