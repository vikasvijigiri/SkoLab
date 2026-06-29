import os
import time
import hmac
import hashlib
import pytest
import httpx
from pydantic import ValidationError
from sqlalchemy import select

from app.main import app
from app.api.dependencies import get_verified_user
from app.db.database import AsyncSessionLocal, engine, generate_record_signature
from app.models.user_models import User, UserPreference
from app.schemas.core import AgentChatRequest
from app.domains.quest.service import QuestsService

# Configure HTTPX AsyncClient transport compatibility
try:
    transport = httpx.ASGITransport(app=app)
    client_args = {"transport": transport}
except AttributeError:
    client_args = {"app": app}

from app.main import rate_limiter

@pytest.fixture(autouse=True)
async def cleanup_test_context():
    """Reset rate limiter and dispose engine after each test."""
    rate_limiter.buckets.clear()
    yield
    rate_limiter.buckets.clear()
    await engine.dispose()

async def force_cleanup_user(db, user_id):
    """Clean up all tables containing user_id directly or indirectly."""
    await db.execute(
        UserPreference.__table__.delete().where(UserPreference.user_id == user_id)
    )
    res_u = await db.execute(select(User).where(User.id == user_id))
    user_obj = res_u.scalar_one_or_none()
    if user_obj:
        await db.delete(user_obj)
    await db.commit()

@pytest.mark.anyio
async def test_scraper_user_agent_blocking():
    """Verify that requests from common scraper User-Agents are blocked with 403."""
    async with httpx.AsyncClient(base_url="http://testserver", **client_args) as ac:
        headers = {"user-agent": "python-requests/2.28.1"}
        response = await ac.get("/health", headers=headers)
        assert response.status_code == 403
        assert "Automated scraping signatures detected" in response.json()["detail"]

        headers_valid = {"user-agent": "SkoLabMobileApp/1.0"}
        response_valid = await ac.get("/", headers=headers_valid)
        assert response_valid.status_code == 200

@pytest.mark.anyio
async def test_admin_access_guard():
    """Verify SRE/admin endpoints (/metrics, /ai_status) require local IP or SRE token."""
    async with httpx.AsyncClient(base_url="http://testserver", **client_args) as ac:
        # Request with default client host (which defaults to 'testserver' in AsyncClient, hence private/local)
        response_local = await ac.get("/metrics")
        assert response_local.status_code == 200

        # Spoofing header bypass using SRE token
        headers_token = {"X-SRE-Token": "sre_bypass_secret_2026"}
        response_token = await ac.get("/metrics", headers=headers_token)
        assert response_token.status_code == 200

@pytest.mark.anyio
async def test_device_signature_validation():
    """Verify X-Device-Signature checks for authenticated state-changing requests."""
    user_id = "test_device_sig_user"
    async with AsyncSessionLocal() as db:
        await force_cleanup_user(db, user_id)

    async with httpx.AsyncClient(base_url="http://testserver", **client_args) as ac:
        # POST request without signature
        response_missing = await ac.post(f"/api/v1/users/{user_id}/export")
        # Since it has no user_id query/header (or X-User-Id), it is bypassable (doesn't trigger sig middleware)
        # But if we provide X-User-Id, it must have signature
        headers_missing = {"X-User-Id": user_id}
        response_missing = await ac.post(f"/api/v1/users/{user_id}/export", headers=headers_missing)
        assert response_missing.status_code == 401
        assert "Missing device signature headers" in response_missing.json()["detail"]

        # POST request with expired signature
        expired_ts = str(int(time.time()) - 400) # expired (>300s window)
        headers_expired = {
            "X-User-Id": user_id,
            "X-Device-Timestamp": expired_ts,
            "X-Device-Signature": "dummy_sig"
        }
        response_expired = await ac.post(f"/api/v1/users/{user_id}/export", headers=headers_expired)
        assert response_expired.status_code == 401
        assert "Device signature timestamp expired" in response_expired.json()["detail"]

        # POST request with invalid signature
        valid_ts = str(int(time.time()))
        headers_invalid = {
            "X-User-Id": user_id,
            "X-Device-Timestamp": valid_ts,
            "X-Device-Signature": "invalid_hmac_hash"
        }
        response_invalid = await ac.post(f"/api/v1/users/{user_id}/export", headers=headers_invalid)
        assert response_invalid.status_code == 401
        assert "Invalid device signature" in response_invalid.json()["detail"]

        # POST request with VALID signature — device sig middleware passes, endpoint logic runs → 404 no user
        from app.core.config import settings
        device_secret = str(settings.database_encryption_key).encode("utf-8")
        payload = f"{user_id}:{valid_ts}"
        valid_sig = hmac.new(device_secret, payload.encode("utf-8"), hashlib.sha256).hexdigest()

        headers_valid = {
            "X-User-Id": user_id,
            "X-Device-Timestamp": valid_ts,
            "X-Device-Signature": valid_sig
        }

        async def _mock_verified_user():
            return {"uid": user_id}

        app.dependency_overrides[get_verified_user] = _mock_verified_user
        try:
            response_valid = await ac.post(f"/api/v1/users/{user_id}/export", headers=headers_valid)
            assert response_valid.status_code == 404  # Reached endpoint logic, user not found
        finally:
            app.dependency_overrides.pop(get_verified_user, None)

@pytest.mark.anyio
async def test_path_specific_rate_limiting():
    """Verify strict rate limits (capacity=5) on critical paths."""
    async with httpx.AsyncClient(base_url="http://testserver", **client_args) as ac:
        # Trigger 6 rapid requests on strict path "/api/v1/papers/search"
        # The 6th request should return 429
        responses = []
        for _ in range(6):
            try:
                res = await ac.get("/api/v1/papers/search")
                responses.append(res)
            except Exception:
                pass
        
        # Verify at least one 429 was returned
        status_codes = [r.status_code for r in responses]
        assert 429 in status_codes

@pytest.mark.anyio
async def test_input_length_validation():
    """Verify that chat messages > 2000 characters fail Pydantic model validation."""
    long_msg = "A" * 2001
    with pytest.raises(ValidationError):
        AgentChatRequest(message=long_msg, history=[])

@pytest.mark.anyio
async def test_quest_database_tampering_check():
    """Verify database integrity checks detect quest record modifications."""
    user_id = "test_tampering_user"
    async with AsyncSessionLocal() as db:
        await force_cleanup_user(db, user_id)
        
        # Insert a user
        u = User(id=user_id, display_name="Test Tamper")
        db.add(u)
        await db.commit()

        # Initialize quests using QuestsService (which auto-signs it)
        # We will mock openalex and generate mock quests instead of LLM to keep test fast
        service = QuestsService(db)
        mock_quests = [
            {"id": "q1", "title": "Read 5 papers", "reward_entropy": 25, "is_completed": False}
        ]
        
        sig = generate_record_signature(user_id, mock_quests)
        
        pref = UserPreference(user_id=user_id, preference_key="quests", preference_value=mock_quests)
        sig_pref = UserPreference(user_id=user_id, preference_key="quests_signature", preference_value=sig)
        db.add_all([pref, sig_pref])
        await db.commit()

    # Now load it, should verify successfully
    async with AsyncSessionLocal() as db:
        service = QuestsService(db)
        quests = await service.get_user_quests(user_id, None)
        assert len(quests) == 1
        assert quests[0].id == "q1"

        # Tamper the quest record directly in database (e.g. mark it complete unauthorized)
        stmt = select(UserPreference).where(
            UserPreference.user_id == user_id, UserPreference.preference_key == "quests"
        )
        res = await db.execute(stmt)
        pref_obj = res.scalars().first()
        pref_obj.preference_value = [
            {"id": "q1", "title": "Read 5 papers", "reward_entropy": 1000, "is_completed": True} # Tampered reward/status
        ]
        from sqlalchemy.orm.attributes import flag_modified
        flag_modified(pref_obj, "preference_value")
        await db.commit()

    # Reading should now throw ValueError due to signature mismatch
    async with AsyncSessionLocal() as db:
        service = QuestsService(db)
        with pytest.raises(ValueError) as excinfo:
            await service.get_user_quests(user_id, None)
        assert "tampered with" in str(excinfo.value)

    # Clean up user
    async with AsyncSessionLocal() as db:
        await force_cleanup_user(db, user_id)
