"""
app/config.py — centralised configuration for the Skolab backend.

All values are read from environment variables (populated via .env in dev,
real env vars in production).  No magic literals anywhere else in the codebase.
"""

import os
import socket
from pathlib import Path
from dataclasses import dataclass, field

# Absolute path to the backend/ root — resolved from this file's location so it
# works regardless of the current working directory when uvicorn is launched.
_BACKEND_ROOT = Path(__file__).resolve().parents[1]


def _lan_ip() -> str:
    """Resolve the machine's outbound LAN IP (never 127.0.0.1)."""
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.settimeout(1.0)
            s.connect(("8.8.8.8", 80))
            return s.getsockname()[0]
    except Exception:
        try:
            return socket.gethostbyname(socket.gethostname())
        except Exception:
            return "127.0.0.1"


def _downloads_dir() -> Path:
    """
    Absolute path to the downloads/ directory.
    Override with DOWNLOADS_DIR env var if you want it elsewhere.
    Falls back to <backend_root>/downloads — always absolute, always portable.
    """
    raw = os.environ.get("DOWNLOADS_DIR", "")
    if raw:
        p = Path(raw).expanduser().resolve()
    else:
        p = _BACKEND_ROOT / "downloads"
    p.mkdir(parents=True, exist_ok=True)
    return p


# Publicly-known default shipped in this file's history. A production process must
# never run with it (or with an empty key) — see Settings.__post_init__.
_DEFAULT_DB_ENCRYPTION_KEY = "MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MTI="


@dataclass(frozen=True)
class Settings:
    # ── Server ──────────────────────────────────────────────────────────────
    host: str = field(default_factory=lambda: os.environ.get("HOST", "0.0.0.0"))  # nosec B104
    port: int = field(default_factory=lambda: int(os.environ.get("PORT", "8000")))
    lan_ip: str = field(default_factory=_lan_ip)
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

    # ── Public base URL (used to build download links, OpenRouter HTTP-Referer, etc.) ──
    # Set APP_BASE_URL in production to your real domain, e.g. https://api.resqit.app
    app_base_url: str = field(
        default_factory=lambda: os.environ.get("APP_BASE_URL", "http://localhost:8000")
    )

    # ── Downloads directory (absolute path, portable across OSes) ────────────
    downloads_dir: Path = field(default_factory=_downloads_dir)

    # ── mDNS service advertisement (must match Android AppConfig.MDNS_SERVICE_NAME) ──
    mdns_service_type: str = field(
        default_factory=lambda: os.environ.get("MDNS_SERVICE_TYPE", "_http._tcp.local.")
    )
    mdns_service_name: str = field(
        default_factory=lambda: os.environ.get("MDNS_SERVICE_NAME", "SkoLabBackend")
    )
    mdns_port: int = field(
        default_factory=lambda: int(os.environ.get("MDNS_PORT", "8080"))
    )

    # ── External API keys ────────────────────────────────────────────────────
    groq_api_key: str = field(default_factory=lambda: os.environ.get("GROQ_API", ""))
    openrouter_api_key: str = field(
        default_factory=lambda: os.environ.get("OPENROUTER_API_KEY", "")
    )
    openalex_email: str = field(
        default_factory=lambda: os.environ.get("OPENALEX_EMAIL", "")
    )
    openalex_api_key: str = field(
        default_factory=lambda: os.environ.get("openalex_api", "")
    )

    # ── Runtime Timeout Controls (env-driven — no rebuild required) ──────────
    # Set HTTP_TIMEOUT_SECONDS in production to adjust all external API timeouts.
    # Set LLM_TIMEOUT_SECONDS to tune LLM endpoint response patience.
    # Defaults match the values previously hardcoded throughout the services.
    http_timeout_seconds: float = field(
        default_factory=lambda: float(os.environ.get("HTTP_TIMEOUT_SECONDS", "15.0"))
    )
    llm_timeout_seconds: float = field(
        default_factory=lambda: float(os.environ.get("LLM_TIMEOUT_SECONDS", "30.0"))
    )

    # ── Cache TTL Controls (env-driven — no rebuild required) ────────────────
    # Set CACHE_TTL_PROFILE_SECONDS, CACHE_TTL_FEED_SECONDS, etc. to override
    # the default cache TTLs without redeploying source code.
    cache_ttl_profile_seconds: int = field(
        default_factory=lambda: int(os.environ.get("CACHE_TTL_PROFILE_SECONDS", "3600"))
    )
    cache_ttl_feed_seconds: int = field(
        default_factory=lambda: int(os.environ.get("CACHE_TTL_FEED_SECONDS", "3600"))
    )
    cache_ttl_analysis_seconds: int = field(
        default_factory=lambda: int(
            os.environ.get("CACHE_TTL_ANALYSIS_SECONDS", "21600")
        )
    )
    cache_ttl_agent_history_seconds: int = field(
        default_factory=lambda: int(
            os.environ.get("CACHE_TTL_AGENT_HISTORY_SECONDS", "43200")
        )
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
    # Upper bound on concurrent embedding forward passes. The model is CPU-bound
    # and single-machine; unbounded callers thrash a shared-CPU host. 0 = resolve
    # to the container's visible core count at runtime.
    embed_max_concurrency: int = field(
        default_factory=lambda: int(os.environ.get("EMBED_MAX_CONCURRENCY", "0"))
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
    # How long a content-hashed embedding vector stays cached in L2. Text→vector
    # is deterministic for a fixed model, so this can be long; the same paper
    # abstract is re-embedded across the feed, journal advisor and grant match.
    embed_vector_cache_ttl_seconds: int = field(
        default_factory=lambda: int(
            os.environ.get("EMBED_VECTOR_CACHE_TTL_SECONDS", str(30 * 24 * 3600))
        )
    )

    # ── LLM fallback bounds ─────────────────────────────────────────────────
    # The fallback loop used to try up to 16 models serially, each with a full
    # llm_timeout_seconds budget — one bad provider window could burn minutes on
    # a single user request. Cap the attempts and the total wall-clock.
    llm_max_fallback_models: int = field(
        default_factory=lambda: int(os.environ.get("LLM_MAX_FALLBACK_MODELS", "4"))
    )
    llm_total_deadline_seconds: float = field(
        default_factory=lambda: float(
            os.environ.get("LLM_TOTAL_DEADLINE_SECONDS", "90.0")
        )
    )

    # ── Observability ────────────────────────────────────────────────────────
    # Sentry DSN. Empty (the default) leaves Sentry inert — the SDK is never
    # initialised. Set SENTRY_DSN in the deployment environment to enable error
    # aggregation. Never committed to the repo.
    sentry_dsn: str = field(default_factory=lambda: os.environ.get("SENTRY_DSN", ""))

    # ── Monitoring ───────────────────────────────────────────────────────────
    # Full name of the primary researcher. Used by add_monitors.py to resolve
    # the OpenAlex author ID at runtime — never hardcoded in source.
    monitor_author_name: str = field(
        default_factory=lambda: os.environ.get("MONITOR_AUTHOR_NAME", "")
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
        # Fail fast: a production deploy must supply a real DATABASE_ENCRYPTION_KEY.
        # Booting with the shipped default (or none) would encrypt user records
        # under a publicly-known key. Dev/staging are unaffected — APP_ENV unset
        # resolves `environment` to "development".
        if self.environment == "production" and self.database_encryption_key in (
            "",
            _DEFAULT_DB_ENCRYPTION_KEY,
        ):
            raise RuntimeError(
                "DATABASE_ENCRYPTION_KEY is unset or still the shipped default while "
                "APP_ENV=production. Set a real key before starting the backend."
            )

    @property
    def mdns_fqdn(self) -> str:
        """Fully-qualified mDNS service name as required by zeroconf."""
        return f"{self.mdns_service_name}.{self.mdns_service_type}"


# Single shared instance — import `settings` everywhere, never instantiate directly.
settings = Settings()
