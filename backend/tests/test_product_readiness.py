import os
import pytest
import httpx
from unittest.mock import patch, MagicMock
from app.main import app
from app.core.config import settings
from app.services.ai.llm_service import is_llm_working, set_llm_limit_exceeded

# Configure HTTPX AsyncClient transport compatibility
try:
    transport = httpx.ASGITransport(app=app)
    client_args = {"transport": transport}
except AttributeError:
    client_args = {"app": app}


def test_settings_read_defaults():
    """Verify that settings can resolve default and environment values correctly."""
    assert settings.port in [8000, 8001]
    assert "SkoLabBackend" in settings.mdns_service_name
    assert settings.http_timeout_seconds == 15.0


@pytest.mark.anyio
async def test_kill_switch_middleware_active():
    """Verify that kill switch middleware returns 503 for blocked endpoints."""
    # Temporarily set KILL_SWITCHES env var
    with patch.dict(os.environ, {"KILL_SWITCHES": "agent/chat,quests"}):
        async with httpx.AsyncClient(base_url="http://testserver", **client_args) as ac:
            response = await ac.get("/api/v1/quests/users/quests?user_id=test_uid")
            assert response.status_code == 503
            data = response.json()
            assert "temporarily disabled" in data["detail"]


@pytest.mark.anyio
async def test_kill_switch_middleware_bypass_health():
    """Verify that health and root paths bypass kill switches."""
    with patch.dict(os.environ, {"KILL_SWITCHES": "health,metrics"}):
        async with httpx.AsyncClient(base_url="http://testserver", **client_args) as ac:
            response = await ac.get("/health")
            assert response.status_code == 200


def test_llm_limit_exceeded_cooldown():
    """Verify that is_llm_working checks and respects the limit exceeded state."""
    # Ensure LLM key is configured for the test context using a mock settings object
    mock_settings = MagicMock()
    mock_settings.configure_mock(groq_api_key="dummy", openrouter_api_key="")
    
    with patch("app.services.ai.llm_service.settings", mock_settings):
        set_llm_limit_exceeded(False)
        assert is_llm_working() is True
        
        # Trip the limit switch
        set_llm_limit_exceeded(True)
        assert is_llm_working() is False
        
        # Reset the limit switch
        set_llm_limit_exceeded(False)
        assert is_llm_working() is True
