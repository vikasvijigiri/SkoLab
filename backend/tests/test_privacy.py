import time
import pytest
import httpx
from sqlalchemy import select, delete
from app.main import app
from app.db.database import AsyncSessionLocal, engine
from app.models.user_models import User, UserPreference, AgentChatHistory
from app.models.analytics_models import UserSettings, UserActivityLog, ApiRequestLog
from app.api.v1.endpoints.users import generate_signed_token

# Configure HTTPX AsyncClient transport compatibility
try:
    transport = httpx.ASGITransport(app=app)
    client_args = {"transport": transport}
except AttributeError:
    client_args = {"app": app}

@pytest.fixture(autouse=True)
async def cleanup_database_engine():
    """Dispose SQLAlchemy engine connection pool after each test to avoid event loop reuse errors."""
    yield
    await engine.dispose()

async def force_cleanup_user(db, user_id):
    """Clean up all tables containing user_id directly or indirectly."""
    await db.execute(delete(UserSettings).where(UserSettings.user_id == user_id))
    await db.execute(delete(AgentChatHistory).where(AgentChatHistory.user_id == user_id))
    await db.execute(delete(UserPreference).where(UserPreference.user_id == user_id))
    
    # Clean up logs
    await db.execute(delete(UserActivityLog).where(UserActivityLog.user_id == user_id))
    await db.execute(delete(ApiRequestLog).where(ApiRequestLog.user_id == user_id))
    
    res_u = await db.execute(select(User).where(User.id == user_id))
    user_obj = res_u.scalar_one_or_none()
    if user_obj:
        await db.delete(user_obj)
    await db.commit()

@pytest.mark.anyio
async def test_gdpr_account_deletion():
    """Verify account deletion deletes user PII/chats and anonymizes activity logs."""
    user_id = "test_user_delete_123"
    
    async with AsyncSessionLocal() as db:
        await force_cleanup_user(db, user_id)
            
        # Create User
        u = User(id=user_id, display_name="Test Deletion", email="delete@skolab.open")
        db.add(u)
        await db.flush()
        
        # Create Preference
        p = UserPreference(user_id=user_id, preference_key="test_pref", preference_value="val")
        db.add(p)
        
        # Create Settings
        s = UserSettings(user_id=user_id, theme="light")
        db.add(s)
        
        # Create Chat History
        c = AgentChatHistory(user_id=user_id, role="user", content="hello agent")
        db.add(c)
        
        # Create Logs to anonymize
        log1 = UserActivityLog(user_id=user_id, event_type="author_search", entity_id="A5")
        log2 = ApiRequestLog(user_id=user_id, endpoint="/api/v1/feed")
        db.add_all([log1, log2])
        
        await db.commit()

    # Trigger delete API via AsyncClient
    async with httpx.AsyncClient(base_url="http://testserver", **client_args) as ac:
        response = await ac.delete(f"/api/v1/users/{user_id}")
    assert response.status_code == 200
    assert response.json()["status"] == "success"

    async with AsyncSessionLocal() as db:
        # User must be deleted
        res_u = await db.execute(select(User).where(User.id == user_id))
        assert res_u.scalar_one_or_none() is None
        
        # Settings must be deleted
        res_s = await db.execute(select(UserSettings).where(UserSettings.user_id == user_id))
        assert res_s.scalar_one_or_none() is None
        
        # Chat history must be deleted
        res_c = await db.execute(select(AgentChatHistory).where(AgentChatHistory.user_id == user_id))
        assert res_c.scalars().all() == []
        
        # Logs must be anonymized (user_id is None)
        res_log1 = await db.execute(select(UserActivityLog).where(UserActivityLog.entity_id == "A5"))
        db_log1 = res_log1.scalar_one_or_none()
        assert db_log1 is not None
        assert db_log1.user_id is None
        
        res_log2 = await db.execute(select(ApiRequestLog).where(ApiRequestLog.endpoint == "/api/v1/feed"))
        db_log2 = res_log2.scalar_one_or_none()
        assert db_log2 is not None
        assert db_log2.user_id is None

        # Clean up log entries
        await db.delete(db_log1)
        await db.delete(db_log2)
        await db.commit()

@pytest.mark.anyio
async def test_ccpa_data_portability_export():
    """Verify CCPA data export produces signed tokens and enforces expiration."""
    user_id = "test_user_export_456"
    
    async with AsyncSessionLocal() as db:
        await force_cleanup_user(db, user_id)
            
        # Create User
        u = User(id=user_id, display_name="Test Export", email="export@skolab.open")
        db.add(u)
        await db.commit()

    async with httpx.AsyncClient(base_url="http://testserver", **client_args) as ac:
        # Request export link
        response = await ac.post(f"/api/v1/users/{user_id}/export")
        assert response.status_code == 200
        resp_data = response.json()
        assert resp_data["status"] == "success"
        assert "download_url" in resp_data
        
        # Extract token
        download_url = resp_data["download_url"]
        token = download_url.split("token=")[1]
        
        # Download using valid token
        dl_response = await ac.get(f"/api/v1/users/download-export?token={token}")
        assert dl_response.status_code == 200
        export_payload = dl_response.json()
        assert export_payload["user_id"] == user_id
        assert export_payload["display_name"] == "Test Export"

        # Download using altered token (invalid signature)
        dl_response_invalid = await ac.get("/api/v1/users/download-export?token=altered_token_xyz")
        assert dl_response_invalid.status_code == 403
        
        # Download using expired token
        expired_token = generate_signed_token(user_id, "mock_path", expires_at=int(time.time()) - 10)
        dl_response_expired = await ac.get(f"/api/v1/users/download-export?token={expired_token}")
        assert dl_response_expired.status_code == 403

    # Clean up user
    async with AsyncSessionLocal() as db:
        await force_cleanup_user(db, user_id)
