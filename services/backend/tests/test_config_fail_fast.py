"""Boot-time configuration fail-fast (Stream B security hardening).

`Settings.__post_init__` must stop the process when the deployment environment
is misconfigured for a public Render deploy:

- `APP_ENV` set to anything outside {development, staging, production}
- a missing / shipped-default `DATABASE_ENCRYPTION_KEY` in staging or production
- a missing `INTERNAL_API_TOKEN` in staging or production (2026-09-26 security
  audit: unset means every /internal/* route runs with no auth check at all,
  silently, with no prior boot-time warning)

Local dev (`APP_ENV` unset) and CI (fake keys, `APP_ENV` unset) must still boot.
"""

import pytest
from cryptography.fernet import Fernet

from app.core.config import Settings, _DEFAULT_DB_ENCRYPTION_KEY

_REAL_KEY = Fernet.generate_key().decode()
_REAL_TOKEN = "a-real-internal-api-token"


def test_unset_app_env_boots_as_development(monkeypatch):
    monkeypatch.delenv("APP_ENV", raising=False)
    monkeypatch.setenv("DATABASE_ENCRYPTION_KEY", _DEFAULT_DB_ENCRYPTION_KEY)
    s = Settings()
    assert s.environment == "development"


def test_unknown_app_env_refuses_to_boot(monkeypatch):
    monkeypatch.setenv("DATABASE_ENCRYPTION_KEY", _REAL_KEY)
    monkeypatch.setenv("INTERNAL_API_TOKEN", _REAL_TOKEN)
    for bad in ("prod", "dev", "test", "PRODUCTION ", "local"):
        monkeypatch.setenv("APP_ENV", bad)
        with pytest.raises(RuntimeError, match="APP_ENV"):
            Settings()


@pytest.mark.parametrize("env", ["staging", "production"])
def test_staging_and_production_reject_default_or_missing_key(monkeypatch, env):
    monkeypatch.setenv("APP_ENV", env)
    monkeypatch.setenv("INTERNAL_API_TOKEN", _REAL_TOKEN)

    monkeypatch.delenv("DATABASE_ENCRYPTION_KEY", raising=False)
    with pytest.raises(RuntimeError, match="DATABASE_ENCRYPTION_KEY"):
        Settings()

    monkeypatch.setenv("DATABASE_ENCRYPTION_KEY", _DEFAULT_DB_ENCRYPTION_KEY)
    with pytest.raises(RuntimeError, match="DATABASE_ENCRYPTION_KEY"):
        Settings()

    monkeypatch.setenv("DATABASE_ENCRYPTION_KEY", _REAL_KEY)
    Settings()  # real key + real token → boots


@pytest.mark.parametrize("env", ["staging", "production"])
def test_staging_and_production_reject_missing_internal_token(monkeypatch, env):
    monkeypatch.setenv("APP_ENV", env)
    monkeypatch.setenv("DATABASE_ENCRYPTION_KEY", _REAL_KEY)

    monkeypatch.delenv("INTERNAL_API_TOKEN", raising=False)
    with pytest.raises(RuntimeError, match="INTERNAL_API_TOKEN"):
        Settings()

    monkeypatch.setenv("INTERNAL_API_TOKEN", "")
    with pytest.raises(RuntimeError, match="INTERNAL_API_TOKEN"):
        Settings()

    monkeypatch.setenv("INTERNAL_API_TOKEN", _REAL_TOKEN)
    Settings()  # real token → boots


def test_development_tolerates_default_key(monkeypatch):
    monkeypatch.setenv("APP_ENV", "development")
    monkeypatch.setenv("DATABASE_ENCRYPTION_KEY", _DEFAULT_DB_ENCRYPTION_KEY)
    monkeypatch.delenv("INTERNAL_API_TOKEN", raising=False)
    Settings()  # dev is never gated on either key


@pytest.mark.parametrize("key", ["weak", "x" * 44, "!!!!"])
@pytest.mark.parametrize("env", ["staging", "production"])
def test_deployed_settings_reject_malformed_encryption_key(monkeypatch, env, key):
    monkeypatch.setenv("APP_ENV", env)
    monkeypatch.setenv("INTERNAL_API_TOKEN", _REAL_TOKEN)
    monkeypatch.setenv("DATABASE_ENCRYPTION_KEY", key)
    with pytest.raises(RuntimeError, match="valid Fernet key"):
        Settings()
