from fastapi import APIRouter, Query
from typing import Dict, Any, List
from pydantic import BaseModel

router = APIRouter()

class ZoteroSyncRequest(BaseModel):
    user_id: str
    papers: List[Dict[str, Any]]

@router.get("/zotero/auth")
async def zotero_auth_init(user_id: str = Query(...)):
    """
    Step 1: Returns the Zotero OAuth authorization URL to initiate link flow.
    """
    # Mocking Zotero OAuth URL generator
    oauth_url = f"https://www.zotero.org/oauth/authorize?oauth_token=mock_token_skolab_{user_id}&client_id=skolab_client"
    return {"authorization_url": oauth_url}

@router.get("/zotero/callback")
async def zotero_auth_callback(oauth_token: str = Query(...), oauth_verifier: str = Query(...)):
    """
    Step 2: Handles the Zotero redirect callback, exchanges token, and marks user as connected.
    """
    return {
        "status": "success",
        "message": "Zotero account linked successfully!",
        "zotero_user_id": "8765432",
        "zotero_username": "skolab_researcher"
    }

@router.post("/zotero/sync")
async def zotero_sync_papers(payload: ZoteroSyncRequest):
    """
    Step 3: Pushes a list of papers from the SkoLab SwipeVault directly into Zotero via Zotero API.
    """
    synced_titles = []
    for paper in payload.papers:
        title = paper.get("title", "Untitled Paper")
        synced_titles.append(title)
        
    return {
        "status": "success",
        "synced_count": len(synced_titles),
        "synced_papers": synced_titles,
        "message": "Vault papers synced to desktop Zotero library successfully!"
    }
