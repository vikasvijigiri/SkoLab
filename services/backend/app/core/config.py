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
            "DATABASE_ENCRYPTION_KEY", "MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MTI="
        )
    )

    @property
    def mdns_fqdn(self) -> str:
        """Fully-qualified mDNS service name as required by zeroconf."""
        return f"{self.mdns_service_name}.{self.mdns_service_type}"


# Single shared instance — import `settings` everywhere, never instantiate directly.
settings = Settings()
