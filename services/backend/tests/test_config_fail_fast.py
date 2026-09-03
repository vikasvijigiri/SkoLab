"""Boot-time configuration fail-fast (Stream B security hardening).

`Settings.__post_init__` must stop the process when the deployment environment
is misconfigured for a public Render deploy:

- `APP_ENV` set to anything outside {development, staging, production}
- a missing / shipped-default `DATABASE_ENCRYPTION_KEY` in staging or production

Local dev (`APP_ENV` unset) and CI (fake keys, `APP_ENV` unset) must still boot.
"""

import pytest

from app.core.config import Settings, _DEFAULT_DB_ENCRYPTION_KEY

_REAL_KEY = "a-real-deployment-provided-key"


def test_unset_app_env_boots_as_development(monkeypatch):
    monkeypatch.delenv("APP_ENV", raising=False)
    monkeypatch.setenv("DATABASE_ENCRYPTION_KEY", _DEFAULT_DB_ENCRYPTION_KEY)
    s = Settings()
    assert s.environment == "development"


def test_unknown_app_env_refuses_to_boot(monkeypatch):
    monkeypatch.setenv("DATABASE_ENCRYPTION_KEY", _REAL_KEY)
    for bad in ("prod", "dev", "test", "PRODUCTION ", "local"):
        monkeypatch.setenv("APP_ENV", bad)
        with pytest.raises(RuntimeError, match="APP_ENV"):
            Settings()


@pytest.mark.parametrize("env", ["staging", "production"])
def test_staging_and_production_reject_default_or_missing_key(monkeypatch, env):
    monkeypatch.setenv("APP_ENV", env)

    monkeypatch.delenv("DATABASE_ENCRYPTION_KEY", raising=False)
    with pytest.raises(RuntimeError, match="DATABASE_ENCRYPTION_KEY"):
        Settings()

    monkeypatch.setenv("DATABASE_ENCRYPTION_KEY", _DEFAULT_DB_ENCRYPTION_KEY)
    with pytest.raises(RuntimeError, match="DATABASE_ENCRYPTION_KEY"):
        Settings()

    monkeypatch.setenv("DATABASE_ENCRYPTION_KEY", _REAL_KEY)
    Settings()  # real key → boots


def test_development_tolerates_default_key(monkeypatch):
    monkeypatch.setenv("APP_ENV", "development")
    monkeypatch.setenv("DATABASE_ENCRYPTION_KEY", _DEFAULT_DB_ENCRYPTION_KEY)
    Settings()  # dev is never gated on the key
