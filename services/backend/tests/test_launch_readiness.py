import os
import sys
import pytest
from unittest.mock import patch, MagicMock
import httpx
from app.main import app

# Ensure scripts folder is importable
sys.path.append(
    os.path.join(os.path.dirname(__file__), "..", "..", "..", "scripts", "ops")
)

# Import pre_shift_check functions
import pre_shift_check  # type: ignore

# Configure HTTPX AsyncClient transport compatibility
try:
    transport = httpx.ASGITransport(app=app)
    client_args = {"transport": transport}
except AttributeError:
    client_args = {"app": app}


def test_pre_shift_check_env_vars_success():
    """Verify that check_env_variables returns True when all env vars are valid."""
    mock_env = {
        "DATABASE_URL": "postgresql+asyncpg://postgres:pass@127.0.0.1:5432/skolab",
        "DATABASE_ENCRYPTION_KEY": "dGVzdF9kYXRhYmFzZV9lbmNyeXB0aW9uX2tleV8xMjM=",
        "GROQ_API": "gsk_somekeyvaluelargerthan10chars",
        "OPENROUTER_API_KEY": "sk-or-v1-somekeyvalue",
        "GOOGLE_APPLICATION_CREDENTIALS": "service-account.json",
        "PAGERDUTY_PRIMARY_ONCALL_KEY": "pd_key_primary_active",
        "PAGERDUTY_DB_SRE_KEY": "pd_key_db_sre_active",
    }
    with patch.dict(os.environ, mock_env, clear=True):
        ok, msg = pre_shift_check.check_env_variables()
        assert ok is True
        assert "All required environment variables are set" in msg


def test_pre_shift_check_env_vars_missing():
    """Verify check_env_variables fails when required variables are missing."""
    mock_env = {
        "DATABASE_URL": "postgresql+asyncpg://postgres:pass@127.0.0.1:5432/skolab"
    }
    with patch.dict(os.environ, mock_env, clear=True):
        ok, msg = pre_shift_check.check_env_variables()
        assert ok is False
        assert "Missing required env vars" in msg


def test_pre_shift_check_env_vars_weak_key():
    """Verify check_env_variables fails if database key is the insecure default."""
    mock_env = {
        "DATABASE_URL": "postgresql+asyncpg://postgres:pass@127.0.0.1:5432/skolab",
        "DATABASE_ENCRYPTION_KEY": "MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MTI=",  # weak default
        "GROQ_API": "gsk_somekeyvaluelargerthan10chars",
        "OPENROUTER_API_KEY": "sk-or-v1-somekeyvalue",
        "GOOGLE_APPLICATION_CREDENTIALS": "service-account.json",
        "PAGERDUTY_PRIMARY_ONCALL_KEY": "pd_key_primary_active",
        "PAGERDUTY_DB_SRE_KEY": "pd_key_db_sre_active",
    }
    with patch.dict(os.environ, mock_env, clear=True):
        ok, msg = pre_shift_check.check_env_variables()
        assert ok is False
        assert "Rotate it" in msg


def test_pre_shift_check_pagerduty_keys_success():
    """Verify check_pagerduty_keys passes with valid keys."""
    mock_env = {
        "PAGERDUTY_PRIMARY_ONCALL_KEY": "pd_key_primary_active",
        "PAGERDUTY_DB_SRE_KEY": "pd_key_db_sre_active",
    }
    with patch.dict(os.environ, mock_env, clear=True):
        ok, msg = pre_shift_check.check_pagerduty_keys()
        assert ok is True


def test_pre_shift_check_pagerduty_keys_placeholder():
    """Verify check_pagerduty_keys fails when values are placeholders."""
    mock_env = {
        "PAGERDUTY_PRIMARY_ONCALL_KEY": "${PAGERDUTY_PRIMARY_ONCALL_KEY}",
        "PAGERDUTY_DB_SRE_KEY": "pd_key_db_sre_active",
    }
    with patch.dict(os.environ, mock_env, clear=True):
        ok, msg = pre_shift_check.check_pagerduty_keys()
        assert ok is False
        assert "placeholder" in msg


@patch("urllib.request.urlopen")
def test_pre_shift_check_http_helpers_success(mock_urlopen):
    """Verify backend, prometheus, and alertmanager HTTP checks pass on 200 OK."""
    # Set up mock response
    mock_resp = MagicMock()
    mock_resp.status = 200
    mock_resp.read.return_value = b"OK"
    mock_urlopen.return_value.__enter__.return_value = mock_resp

    ok_be, msg_be = pre_shift_check.check_backend_health("http://localhost:8000")
    assert ok_be is True
    assert "backend /health OK" in msg_be

    ok_prom, msg_prom = pre_shift_check.check_prometheus("http://localhost:9090")
    assert ok_prom is True
    assert "Prometheus healthy" in msg_prom

    ok_am, msg_am = pre_shift_check.check_alertmanager("http://localhost:9093")
    assert ok_am is True
    assert "Alertmanager healthy" in msg_am


@patch("urllib.request.urlopen")
def test_pre_shift_check_http_helpers_fail(mock_urlopen):
    """Verify HTTP checks fail on non-200 responses."""
    mock_resp = MagicMock()
    mock_resp.status = 500
    mock_resp.read.return_value = b"Internal Error"
    mock_urlopen.return_value.__enter__.return_value = mock_resp

    ok_be, msg_be = pre_shift_check.check_backend_health("http://localhost:8000")
    assert ok_be is False


@pytest.mark.anyio
async def test_status_endpoint_degraded_db():
    """Verify `/status` handles database health failure gracefully."""
    with patch("app.db.database.AsyncSessionLocal") as mock_session_class:
        # Cause context manager to throw error
        mock_session_class.side_effect = Exception("DB Connection Refused")

        async with httpx.AsyncClient(base_url="http://testserver", **client_args) as ac:
            response = await ac.get("/api/v1/status")
            assert response.status_code == 200
            data = response.json()
            assert data["services"]["database"] == "degraded"
            assert data["status"] in ["degraded", "outage"]


@pytest.mark.anyio
async def test_status_endpoint_degraded_cache():
    """Verify `/status` handles cache health failure gracefully."""
    with patch("app.core.cache.suggestions_cache.set") as mock_set:
        mock_set.side_effect = Exception("Cache down")

        async with httpx.AsyncClient(base_url="http://testserver", **client_args) as ac:
            response = await ac.get("/api/v1/status")
            assert response.status_code == 200
            data = response.json()
            assert data["services"]["cache_layer"] == "degraded"
            assert data["status"] in ["degraded", "outage"]
