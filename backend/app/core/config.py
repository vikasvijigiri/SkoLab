"""
app/config.py — centralised configuration for the Skolab backend.

All values are read from environment variables (populated via .env in dev,
real env vars in production).  No magic literals anywhere else in the codebase.
"""
import os
import socket
from dataclasses import dataclass, field


def _lan_ip() -> str:
    """Resolve the machine's outbound LAN IP (never 127.0.0.1)."""
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        s.connect(("8.8.8.8", 80))
        return s.getsockname()[0]


@dataclass(frozen=True)
class Settings:
    # ── Server ──────────────────────────────────────────────────────────────
    host: str = field(default_factory=lambda: os.environ["HOST"] if "HOST" in os.environ else "0.0.0.0")
    port: int = field(default_factory=lambda: int(os.environ.get("PORT", "8000")))
    lan_ip: str = field(default_factory=_lan_ip)

    # ── mDNS service advertisement (must match Android AppConfig.MDNS_SERVICE_NAME) ──
    mdns_service_type: str = field(
        default_factory=lambda: os.environ.get("MDNS_SERVICE_TYPE", "_http._tcp.local.")
    )
    mdns_service_name: str = field(
        default_factory=lambda: os.environ.get("MDNS_SERVICE_NAME", "SkoLabBackend")
    )

    # ── External API keys ────────────────────────────────────────────────────
    groq_api_key: str = field(default_factory=lambda: os.environ.get("GROQ_API", ""))
    openalex_email: str = field(
        default_factory=lambda: os.environ.get("OPENALEX_EMAIL", "")
    )
    openalex_api_key: str = field(
        default_factory=lambda: os.environ.get("openalex_api", "")
    )

    # ── Firebase ─────────────────────────────────────────────────────────────
    google_credentials_path: str = field(
        default_factory=lambda: os.environ.get("GOOGLE_APPLICATION_CREDENTIALS", "")
    )

    @property
    def mdns_fqdn(self) -> str:
        """Fully-qualified mDNS service name as required by zeroconf."""
        return f"{self.mdns_service_name}.{self.mdns_service_type}"


# Single shared instance — import `settings` everywhere, never instantiate directly.
settings = Settings()
