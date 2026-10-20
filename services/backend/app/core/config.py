"""
app/config.py — centralised configuration for the Skolab backend.

All values are read from environment variables (populated via .env in dev,
real env vars in production).  No magic literals anywhere else in the codebase.
"""

import os
from dataclasses import dataclass, field
from cryptography.fernet import Fernet

# Publicly-known default shipped in this file's history. A production process must
# never run with it (or with an empty key) — see Settings.__post_init__.
_DEFAULT_DB_ENCRYPTION_KEY = "MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MTI="


@dataclass(frozen=True)
class Settings:
    # ── Server ──────────────────────────────────────────────────────────────
    host: str = field(default_factory=lambda: os.environ.get("HOST", "0.0.0.0"))  # nosec B104
    port: int = field(default_factory=lambda: int(os.environ.get("PORT", "8000")))
    force_https: bool = field(
        default_factory=lambda: (
            os.environ.get("FORCE_HTTPS", "False").lower() in ("true", "1")
        )
    )

    # ── Environment (development | staging | production) ──────────────────────
    # Set APP_ENV=production in your deployment environment.
    # Controls stack trace visibility in structured logs.
    environment: str = field(
        default_factory=lambda: os.environ.get("APP_ENV", "development").lower()
    )

    # ── Public base URL (used for CORS and app metadata) ──────────────────────
    # Set APP_BASE_URL in production to your real domain, e.g. https://api.resqit.app
    app_base_url: str = field(
        default_factory=lambda: os.environ.get("APP_BASE_URL", "http://localhost:8000")
    )

    # ── Shared secret for internal/service-to-service calls ──────────────────
    # Checked by the Go gateway's colab-sandbox call and (while
    # COLAB_SANDBOX_URL is unset) by Python's own /colab/compile fallback
    # path. Unset ⇒ the check is skipped, matching this repo's "local dev
    # needs no configuration" convention for internal auth.
    internal_api_token: str = field(
        default_factory=lambda: os.environ.get("INTERNAL_API_TOKEN", "")
    )

    # ── Connection pool & concurrency (env-driven — no rebuild required) ──────
    # The DB pool is sized for the Supabase free-tier *transaction pooler*
    # (pgBouncer, ~60 shared server connections). With N uvicorn workers the
    # effective ceiling is N * (pool_size + max_overflow), so keep these small.
    db_pool_size: int = field(
        default_factory=lambda: int(os.environ.get("DB_POOL_SIZE", "5"))
    )
    db_max_overflow: int = field(
        default_factory=lambda: int(os.environ.get("DB_MAX_OVERFLOW", "10"))
    )
    db_pool_timeout_seconds: float = field(
        default_factory=lambda: float(os.environ.get("DB_POOL_TIMEOUT_SECONDS", "10.0"))
    )
    # Run Base.metadata.create_all + ad-hoc ALTERs on startup. Correct for local
    # dev; in production the schema is owned by Alembic (`alembic upgrade head`
    # as a release step) and running DDL on every deploy is drift + slow starts.
    # Default: on everywhere except APP_ENV=production. Override with
    # RUN_DB_CREATE_ALL=1/0.
    run_schema_create_all: bool = field(
        default_factory=lambda: (
            os.environ.get(
                "RUN_DB_CREATE_ALL",
                "0" if os.environ.get("APP_ENV", "").lower() == "production" else "1",
            ).lower()
            in ("1", "true", "yes")
        )
    )
    # ── Observability ────────────────────────────────────────────────────────
    # Sentry DSN. Empty (the default) leaves Sentry inert — the SDK is never
    # initialised. Set SENTRY_DSN in the deployment environment to enable error
    # aggregation. Never committed to the repo.
    sentry_dsn: str = field(default_factory=lambda: os.environ.get("SENTRY_DSN", ""))
    # Fraction of requests that get a full performance trace (spans for the
    # DB/HTTP/LLM calls inside them), not just error events. Was hardcoded to
    # 0.0 with a comment reading "raise once a real DSN and traffic baseline
    # exist" — that's now true. 0.2 is a conventional production default; the
    # free Sentry plan's 5M-spans/month budget has enormous headroom at this
    # app's traffic today, so this errs toward more signal rather than
    # rationing a quota nowhere near being tested. Env-configurable so it can
    # be dialed down as real traffic grows, with no code change.
    sentry_traces_sample_rate: float = field(
        default_factory=lambda: float(
            os.environ.get("SENTRY_TRACES_SAMPLE_RATE", "0.2")
        )
    )

    # ── Firebase ─────────────────────────────────────────────────────────────
    google_credentials_path: str = field(
        default_factory=lambda: os.environ.get("GOOGLE_APPLICATION_CREDENTIALS", "")
    )
    database_encryption_key: str = field(
        default_factory=lambda: os.environ.get(
            "DATABASE_ENCRYPTION_KEY", _DEFAULT_DB_ENCRYPTION_KEY
        )
    )
    # Separate key for the deterministic email blind index (HMAC-SHA256 over a
    # normalised address, stored in users.email_bidx). Kept distinct from
    # database_encryption_key: that one is for at-rest confidentiality (Fernet,
    # non-deterministic), this one is for equality lookups on an encrypted
    # column. Empty ⇒ blind-index writes are skipped and email-equality lookups
    # in the Go gateway degrade to "no match" (see app/db/blind_index.py).
    email_blind_index_key: str = field(
        default_factory=lambda: os.environ.get("EMAIL_BLIND_INDEX_KEY", "")
    )

    def __post_init__(self) -> None:
        # Fail fast: APP_ENV must be an explicit, known value. Unset resolves to
        # "development" (local dev + CI keep booting); anything else that is not
        # one of the three recognised names is a typo or a stale deploy config
        # (`prod`, `dev`, `test`, …) and must stop the boot rather than silently
        # behave like development on a public URL.
        _VALID_ENVIRONMENTS = ("development", "staging", "production")
        if self.environment not in _VALID_ENVIRONMENTS:
            raise RuntimeError(
                f"APP_ENV={os.environ.get('APP_ENV')!r} is not one of "
                f"{list(_VALID_ENVIRONMENTS)}. Set APP_ENV explicitly, or leave "
                f"it unset for local development."
            )

        # Fail fast: a staging/production deploy must supply a real
        # DATABASE_ENCRYPTION_KEY. Booting with the shipped default (or none)
        # would encrypt user records under a publicly-known key. Development is
        # unaffected — APP_ENV unset resolves `environment` to "development".
        if self.environment in (
            "staging",
            "production",
        ) and self.database_encryption_key in (
            "",
            _DEFAULT_DB_ENCRYPTION_KEY,
        ):
            raise RuntimeError(
                "DATABASE_ENCRYPTION_KEY is unset or still the shipped default while "
                f"APP_ENV={self.environment}. Set a real key before starting the backend."
            )

        if self.environment in ("staging", "production"):
            try:
                Fernet(self.database_encryption_key.encode("utf-8"))
            except (ValueError, TypeError) as e:
                raise RuntimeError(
                    "DATABASE_ENCRYPTION_KEY must be a valid Fernet key"
                ) from e

        # Fail fast: a staging/production deploy must supply a real
        # INTERNAL_API_TOKEN. The Go gateway's colab-sandbox call and this
        # service's own /colab/compile fallback both treat an empty token as
        # "skip the check" — a deliberate local-dev convenience that becomes
        # "compile runs with no authentication" if left unset in a real
        # deployment. Development is unaffected.
        if self.environment in ("staging", "production") and not self.internal_api_token:
            raise RuntimeError(
                "INTERNAL_API_TOKEN is unset while "
                f"APP_ENV={self.environment}. Set it (and the matching value on "
                "the Go gateway / colab-sandbox worker) before starting the backend."
            )


# Single shared instance — import `settings` everywhere, never instantiate directly.
settings = Settings()
