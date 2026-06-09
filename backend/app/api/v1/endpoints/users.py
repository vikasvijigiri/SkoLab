import time
import json
import hmac
import hashlib
import base64
import logging
from fastapi import APIRouter, Depends, HTTPException, Query, BackgroundTasks
from fastapi.responses import FileResponse
from pydantic import BaseModel
from sqlalchemy import delete, update, select
from sqlalchemy.ext.asyncio import AsyncSession
from pathlib import Path
from typing import Optional

from app.api.dependencies import get_db, get_verified_user
from app.core.config import settings
from app.core.cache import _user_memory_cache, history_summary_cache
from app.models.user_models import User, UserPreference, Connection, AgentChatHistory
from app.models.analytics_models import UserSettings, UserActivityLog, ApiRequestLog

logger = logging.getLogger("skolab.users")

router = APIRouter()

# Directory for secure exports
_BACKEND_ROOT = Path(__file__).resolve().parents[4]
EXPORTS_DIR = _BACKEND_ROOT / "downloads" / "exports"

# Ensure the exports directory exists
EXPORTS_DIR.mkdir(parents=True, exist_ok=True)

# Secret for signing download links
SIGNING_SECRET = str(settings.database_encryption_key).encode("utf-8")

def generate_signed_token(user_id: str, file_path: str, expires_at: int) -> str:
    payload = f"{user_id}:{file_path}:{expires_at}"
    signature = hmac.new(SIGNING_SECRET, payload.encode("utf-8"), hashlib.sha256).hexdigest()
    token_data = {"payload": payload, "signature": signature}
    return base64.urlsafe_b64encode(json.dumps(token_data).encode("utf-8")).decode("utf-8")

def verify_signed_token(token: str) -> tuple[str, str] | None:
    try:
        decoded_bytes = base64.urlsafe_b64decode(token.encode("utf-8"))
        data = json.loads(decoded_bytes.decode("utf-8"))
        payload = data["payload"]
        signature = data["signature"]
        expected_signature = hmac.new(SIGNING_SECRET, payload.encode("utf-8"), hashlib.sha256).hexdigest()
        if not hmac.compare_digest(signature, expected_signature):
            return None
        parts = payload.split(":")
        if len(parts) < 3:
            return None
        user_id = parts[0]
        file_path = ":".join(parts[1:-1])  # Handles windows drive colon
        expires_at = int(parts[-1])
        if time.time() > expires_at:
            return None
        return user_id, file_path
    except Exception:
        return None

class UserProfileSyncRequest(BaseModel):
    uid: str
    name: str
    discipline: Optional[str] = ""


async def _enrich_user_profile_bg(uid: str, name: str, discipline: str) -> None:
    """
    Background task: resolve OpenAlex author ID from name, store it, then run
    the teleport worker to pre-fetch and cache papers/metrics/co-authors.
    Exits early when the user already has an openalex_id — no redundant API calls.
    """
    from app.db.database import AsyncSessionLocal
    from app.services.openalex_service import OpenAlexService
    from app.api.v1.endpoints.authors import track_teleport_researcher

    async with AsyncSessionLocal() as session:
        user = await session.get(User, uid)
        if user and user.openalex_id:
            logger.info(f"[profile_sync] {uid} already has openalex_id={user.openalex_id}, skipping.")
            return

    svc = OpenAlexService()
    # Including discipline narrows the match when multiple authors share the same name
    search_query = f"{name} {discipline}".strip() if discipline else name
    try:
        results = await svc.search_authors(search_query, per_page=1)
    except Exception as exc:
        logger.warning(f"[profile_sync] OpenAlex lookup failed for '{name}': {exc}")
        return

    if not results:
        logger.warning(f"[profile_sync] No OpenAlex match for '{name}'")
        return

    openalex_id = results[0].get("id", "").split("/")[-1]
    if not openalex_id:
        return

    async with AsyncSessionLocal() as session:
        user = await session.get(User, uid)
        if user:
            user.openalex_id = openalex_id
            await session.commit()
    logger.info(f"[profile_sync] Saved openalex_id={openalex_id} for uid={uid} ('{name}')")

    await track_teleport_researcher(openalex_id)
    logger.info(f"[profile_sync] Teleport enrichment complete for {openalex_id}")


@router.post("/users/profile/sync")
async def sync_user_profile(
    req: UserProfileSyncRequest,
    background_tasks: BackgroundTasks,
    db: AsyncSession = Depends(get_db),
):
    """
    Called once after the user provides their name and discipline during
    profile setup. Upserts the User record, then fires a background job that:
      1. Resolves their OpenAlex author ID by name (skipped if already stored)
      2. Pre-warms all research data caches via the teleport worker
    Returns immediately — enrichment happens asynchronously.
    """
    user = await db.get(User, req.uid)
    if user:
        user.display_name = req.name
    else:
        user = User(id=req.uid, display_name=req.name)
        db.add(user)
    await db.commit()
    await db.refresh(user)

    background_tasks.add_task(_enrich_user_profile_bg, req.uid, req.name, req.discipline or "")

    return {
        "status": "syncing",
        "uid": user.id,
        "openalex_id": user.openalex_id,
    }


@router.delete("/users/{userId}")
async def delete_user(
    userId: str,
    db: AsyncSession = Depends(get_db),
    verified_user: dict = Depends(get_verified_user),
):
    """
    GDPR Right to be Forgotten: Permanently deletes the user and associated PII,
    and anonymizes telemetry log records.
    """
    if verified_user.get("uid") != userId:
        raise HTTPException(status_code=403, detail="Forbidden: you may only delete your own account.")

    user_stmt = select(User).where(User.id == userId)
    user_result = await db.execute(user_stmt)
    user = user_result.scalar_one_or_none()
    if not user:
        raise HTTPException(status_code=404, detail="User not found")

    try:
        # Delete UserSettings
        await db.execute(delete(UserSettings).where(UserSettings.user_id == userId))

        # Delete AgentChatHistory
        await db.execute(delete(AgentChatHistory).where(AgentChatHistory.user_id == userId))

        # Anonymize UserActivityLog
        await db.execute(
            update(UserActivityLog)
            .where(UserActivityLog.user_id == userId)
            .values(user_id=None)
        )

        # Anonymize ApiRequestLog
        await db.execute(
            update(ApiRequestLog)
            .where(ApiRequestLog.user_id == userId)
            .values(user_id=None)
        )

        # Delete User preference and User record (which cascade deletes connections and messages)
        await db.execute(delete(UserPreference).where(UserPreference.user_id == userId))
        await db.execute(delete(User).where(User.id == userId))

        # Commit all DB operations
        await db.commit()

        # Invalidate caches
        await _user_memory_cache.delete(userId)
        await history_summary_cache.delete(userId)

        # Mask user ID in logs to protect privacy
        masked_id = f"usr_***{userId[-4:]}" if len(userId) > 4 else "usr_***"
        logger.info(f"GDPR: Account deletion completed for {masked_id}")

        return {"status": "success", "detail": "Account deleted successfully and telemetry anonymized."}
    except Exception as e:
        await db.rollback()
        logger.error(f"Failed to delete user account: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=f"Failed to delete account: {str(e)}")

@router.post("/users/{userId}/export")
async def export_user_data(
    userId: str,
    db: AsyncSession = Depends(get_db),
    verified_user: dict = Depends(get_verified_user),
):
    if verified_user.get("uid") != userId:
        raise HTTPException(status_code=403, detail="Forbidden: you may only export your own data.")
    """
    CCPA Data Portability: Packages all user records into a structured JSON file,
    saving it securely with a signed expiring download link.
    """
    user_stmt = select(User).where(User.id == userId)
    user_result = await db.execute(user_stmt)
    user = user_result.scalar_one_or_none()
    if not user:
        raise HTTPException(status_code=404, detail="User not found")

    try:
        # Query UserPreference
        pref_stmt = select(UserPreference).where(UserPreference.user_id == userId)
        pref_res = await db.execute(pref_stmt)
        preferences = [{"key": p.preference_key, "value": p.preference_value} for p in pref_res.scalars().all()]

        # Query UserSettings
        settings_stmt = select(UserSettings).where(UserSettings.user_id == userId)
        settings_res = await db.execute(settings_stmt)
        settings_val = settings_res.scalar_one_or_none()
        user_settings = {
            "theme": settings_val.theme,
            "accent_color": settings_val.accent_color,
            "notify_connections": settings_val.notify_connections,
            "notify_daily_feed": settings_val.notify_daily_feed,
            "notify_paper_alerts": settings_val.notify_paper_alerts,
            "notify_messages": settings_val.notify_messages,
            "profile_visibility": settings_val.profile_visibility,
            "show_email": settings_val.show_email,
            "show_institution": settings_val.show_institution,
            "feed_topics": settings_val.feed_topics,
            "feed_refresh_hours": settings_val.feed_refresh_hours,
            "preferred_journals": settings_val.preferred_journals,
            "preferred_fields": settings_val.preferred_fields,
        } if settings_val else {}

        # Query UserActivityLog
        log_stmt = select(UserActivityLog).where(UserActivityLog.user_id == userId)
        log_res = await db.execute(log_stmt)
        activity_logs = [{
            "event_type": l.event_type,
            "entity_id": l.entity_id,
            "entity_name": l.entity_name,
            "event_metadata": l.event_metadata,
            "created_at": l.created_at.isoformat() if l.created_at else None
        } for l in log_res.scalars().all()]

        # Query AgentChatHistory
        chat_stmt = select(AgentChatHistory).where(AgentChatHistory.user_id == userId)
        chat_res = await db.execute(chat_stmt)
        chat_history = [{
            "context_id": c.context_id,
            "role": c.role,
            "content": c.content,
            "timestamp": c.timestamp.isoformat() if c.timestamp else None
        } for c in chat_res.scalars().all()]

        # Query Connections
        conn_stmt = select(Connection).where((Connection.user_id == userId) | (Connection.connected_user_id == userId))
        conn_res = await db.execute(conn_stmt)
        connections = [{
            "user_id": cn.user_id,
            "connected_user_id": cn.connected_user_id,
            "status": cn.status,
            "created_at": cn.created_at.isoformat() if cn.created_at else None
        } for cn in conn_res.scalars().all()]

        # Package data
        data_payload = {
            "user_id": user.id,
            "display_name": user.display_name,
            "email": user.email,
            "openalex_id": user.openalex_id,
            "created_at": user.created_at.isoformat() if user.created_at else None,
            "settings": user_settings,
            "preferences": preferences,
            "activity_logs": activity_logs,
            "chat_history": chat_history,
            "connections": connections
        }

        # Write to secure temporary export file
        file_name = f"export_{userId}_{int(time.time())}.json"
        file_path = EXPORTS_DIR / file_name
        with open(file_path, "w", encoding="utf-8") as f:
            json.dump(data_payload, f, indent=2)

        # Generate a signed timed token expiring in 24 hours (86400 seconds)
        expires_at = int(time.time()) + 86400
        token = generate_signed_token(userId, str(file_path), expires_at)

        # Build download link
        download_url = f"{settings.app_base_url}/api/v1/users/download-export?token={token}"

        return {
            "status": "success",
            "download_url": download_url,
            "expires_at": expires_at
        }
    except Exception as e:
        logger.error(f"Failed to export user data: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=f"Failed to export data: {str(e)}")

@router.get("/users/download-export")
async def download_user_export(
    background_tasks: BackgroundTasks,
    token: str = Query(..., description="Signed expiring download token")
):
    """
    Serves the exported CCPA JSON data portability file over HTTPS and immediately
    deletes it from disk.
    """
    token_val = verify_signed_token(token)
    if not token_val:
        raise HTTPException(status_code=403, detail="Invalid, altered, or expired download link.")

    user_id, file_path_str = token_val
    file_path = Path(file_path_str).resolve()

    # Ensure the resolved path is within the allowed exports directory
    if not file_path.is_relative_to(EXPORTS_DIR.resolve()):
        raise HTTPException(status_code=403, detail="Invalid download token.")

    if not file_path.exists():
        raise HTTPException(status_code=404, detail="Export file not found or already downloaded.")

    def cleanup_file(path: Path):
        try:
            if path.exists():
                path.unlink()
                logger.info(f"Cleaned up temporary export file: {path.name}")
        except Exception as e:
            logger.error(f"Failed to delete temporary export file: {e}")

    background_tasks.add_task(cleanup_file, file_path)

    return FileResponse(
        path=str(file_path),
        media_type="application/json",
        filename=f"skolab_data_export_{user_id}.json"
    )
