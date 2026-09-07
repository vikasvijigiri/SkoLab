"""Error aggregation (Sentry) plus the shared helpers that keep it signal.

``init_observability()`` is a no-op unless ``settings.sentry_dsn`` is set, and a
hard no-op under pytest — a developer's local ``.env`` DSN must never ship test
exceptions to the production project (that is how ``/boom`` and ``/readyz``
"db is down (injected)" ended up as prod issues).

The rest of this module is the anti-noise toolkit the 2026-09 Sentry audit
asked for:

- ``before_send`` drops events that are expected, self-healing degradation
  (LLM rate-limits, an open circuit breaker) and events from a non-production
  environment, and scrubs obvious ``password = …`` secrets from the title.
- ``is_transient_ai_error`` / ``log_ai_degradation`` — call sites that fall
  back to a template when the LLM is unavailable log at WARNING, not ERROR, so
  a working degradation path stops creating issues.
- ``DiskUsageAlerter`` — alert on *crossing* an escalating band, at most once
  per band per hour, with the percentage rounded so Sentry groups it into one
  issue instead of one per 0.1 %.
- ``prune_downloads_dir`` — TTL sweep of generated download artefacts, the most
  likely cause of the slow disk climb.
"""

from __future__ import annotations

import logging
import os
import re
import sys
import time
from pathlib import Path

import sentry_sdk
from sentry_sdk.integrations.fastapi import FastApiIntegration
from sentry_sdk.integrations.starlette import StarletteIntegration

from app.core.config import settings

logger = logging.getLogger("skolab")

# Environments whose events are worth keeping in Sentry. Anything else
# (development, a local run, an ad-hoc script) is dropped by before_send.
_REPORTABLE_ENVIRONMENTS = {"production", "staging"}

# Substrings that mark an exception as expected AI degradation rather than a
# defect. Kept lowercase; matched against ``str(exc)``.
_TRANSIENT_AI_MARKERS = (
    "llm services are currently unavailable",
    "llm query failed across all attempted models",
    "rate limit reached",
    "rate-limited",
    "circuit breaker",
    "circuit open",
    "circuit_open",
    "429",
    "quota",
    "temporarily unavailable",
)

_SECRET_RE = re.compile(
    r"(password|passwd|secret|api[_-]?key|token)\s*[=:]\s*\S+", re.IGNORECASE
)


def _under_pytest() -> bool:
    return "PYTEST_CURRENT_TEST" in os.environ or "pytest" in sys.modules


def is_transient_ai_error(exc: BaseException | str) -> bool:
    """True when ``exc`` is expected AI-provider degradation, not a bug.

    Used both by ``before_send`` (drop the event) and by ``log_ai_degradation``
    (log WARNING, no stack trace).
    """
    from app.core.circuit_breaker import CircuitBreakerOpenError

    if isinstance(exc, CircuitBreakerOpenError):
        return True
    text = (exc if isinstance(exc, str) else str(exc)).lower()
    return any(marker in text for marker in _TRANSIENT_AI_MARKERS)


def log_ai_degradation(
    log: logging.Logger, context: str, exc: BaseException, *, level: int = logging.ERROR
) -> None:
    """Log an AI failure at the right severity.

    Expected degradation (provider down, rate-limited, circuit open) → WARNING
    with no traceback; anything else → the caller's ``level`` (ERROR by default)
    with ``exc_info`` so Sentry still sees real defects.
    """
    if is_transient_ai_error(exc):
        log.warning("%s — AI degraded, using fallback: %s", context, exc)
    else:
        log.log(level, "%s: %s", context, exc, exc_info=level >= logging.ERROR)


def _scrub_secrets(value: object) -> object:
    if isinstance(value, str):
        return _SECRET_RE.sub(r"\1=[REDACTED]", value)
    return value


def _before_send(event: dict, hint: dict) -> dict | None:
    """Sentry ``before_send`` — drop noise, scrub secrets. Returns None to drop."""
    # 1. Only production / staging events are worth keeping.
    env = (event.get("environment") or "").lower()
    if env and env not in _REPORTABLE_ENVIRONMENTS:
        return None

    # 2. Expected AI degradation is not an issue.
    exc = hint.get("exc_info", (None, None, None))[1] if hint else None
    if exc is not None and is_transient_ai_error(exc):
        return None
    message = event.get("logentry", {}).get("message") or event.get("message") or ""
    if message and is_transient_ai_error(message):
        return None

    # 3. Scrub obvious secrets from the human-facing fields.
    if isinstance(event.get("message"), str):
        event["message"] = _scrub_secrets(event["message"])
    le = event.get("logentry")
    if isinstance(le, dict) and isinstance(le.get("message"), str):
        le["message"] = _scrub_secrets(le["message"])
    for exc_entry in event.get("exception", {}).get("values", []):
        if isinstance(exc_entry.get("value"), str):
            exc_entry["value"] = _scrub_secrets(exc_entry["value"])
    return event


def init_observability(*, force: bool = False) -> None:
    """Initialise Sentry iff a DSN is set and we are not under pytest.

    ``force=True`` bypasses only the pytest guard — the tests in
    ``test_observability.py`` that assert the SDK actually wires up pass it.
    """
    if _under_pytest() and not force:
        logger.info("Sentry disabled — running under pytest")
        return

    dsn = settings.sentry_dsn
    if not dsn:
        logger.info("Sentry disabled — no SENTRY_DSN set")
        return

    sentry_sdk.init(
        dsn=dsn,
        environment=settings.environment,
        traces_sample_rate=settings.sentry_traces_sample_rate,
        send_default_pii=False,
        before_send=_before_send,
        integrations=[FastApiIntegration(), StarletteIntegration()],
    )
    logger.info(
        "Sentry enabled (environment=%s, traces_sample_rate=%s)",
        settings.environment,
        settings.sentry_traces_sample_rate,
    )


# ── Disk capacity alerting ──────────────────────────────────────────────────
# The old monitor logged CRITICAL every 60 s for as long as the disk stayed
# above 80 % (1 300+ identical events in three days) and embedded a one-decimal
# percentage, so Sentry filed a fresh issue for every 83.1 → 83.2 wobble. This
# alerts only when usage *crosses up* into a higher band, re-alerts a standing
# condition at most once an hour, and rounds the percentage.

_DISK_BANDS = (80, 85, 90, 95)
_DISK_REALERT_SECONDS = 3600.0


class DiskUsageAlerter:
    """Stateful decision: given the current disk %, should we alert now?"""

    def __init__(self, realert_seconds: float = _DISK_REALERT_SECONDS) -> None:
        self._realert_seconds = realert_seconds
        self._last_band = 0
        self._last_alert_at = 0.0

    @staticmethod
    def _band(pct: float) -> int:
        band = 0
        for edge in _DISK_BANDS:
            if pct >= edge:
                band = edge
        return band

    def evaluate(self, pct: float, *, now: float | None = None) -> tuple[bool, int]:
        """Return ``(should_alert, band)`` for the current usage percentage."""
        now = time.monotonic() if now is None else now
        band = self._band(pct)

        if band == 0:
            self._last_band = 0
            return False, 0

        crossed_up = band > self._last_band
        stale = (now - self._last_alert_at) >= self._realert_seconds
        should = crossed_up or stale
        if should:
            self._last_alert_at = now
        self._last_band = band
        return should, band


def check_disk_usage(alerter: DiskUsageAlerter, *, path: str = "/") -> None:
    """One poll of the disk monitor. Never raises."""
    import shutil

    try:
        usage = shutil.disk_usage(path)
        pct = (usage.used / usage.total) * 100 if usage.total else 0.0
    except Exception as exc:  # pragma: no cover - platform dependent
        logger.warning("Disk space monitor failed: %s", exc)
        return

    should_alert, band = alerter.evaluate(pct)
    if not should_alert:
        return

    msg = (
        f"[DISK] Usage {pct:.0f}% — crossed the {band}% threshold "
        f"(free {usage.free / 1_073_741_824:.1f} GiB)"
    )
    # >=90 % is page-worthy; 80-90 % is a heads-up, not a 3 a.m. alert.
    if band >= 90:
        logger.critical(msg, extra={"disk_usage_percent": round(pct, 1)})
    else:
        logger.warning(msg, extra={"disk_usage_percent": round(pct, 1)})


# ── Download-artefact TTL sweep ─────────────────────────────────────────────
# app/services/platform/connectors.py writes generated CVs / statements into
# settings.downloads_dir and nothing ever deletes them — the most plausible
# cause of the disk creeping from 81 % to 84 % over a few days. Keep the
# committed *_template.* files; expire everything else.

_DOWNLOADS_KEEP_SUFFIXES = ("_template.md", "_template.pdf", "_template.docx")


def prune_downloads_dir(
    directory: Path | str | None = None, *, max_age_seconds: float = 86_400.0
) -> int:
    """Delete generated files older than ``max_age_seconds``. Returns the count.

    Never raises — a cleanup failure must not take the process down.
    """
    root = Path(directory) if directory is not None else settings.downloads_dir
    removed = 0
    try:
        if not root.is_dir():
            return 0
        cutoff = time.time() - max_age_seconds
        for entry in root.iterdir():
            if not entry.is_file():
                continue
            if entry.name.endswith(_DOWNLOADS_KEEP_SUFFIXES):
                continue
            try:
                if entry.stat().st_mtime < cutoff:
                    entry.unlink()
                    removed += 1
            except OSError as exc:  # pragma: no cover - fs race
                logger.warning(
                    "prune_downloads_dir: could not remove %s: %s", entry, exc
                )
    except Exception as exc:  # pragma: no cover - defensive
        logger.warning("prune_downloads_dir failed for %s: %s", root, exc)
    if removed:
        logger.info(
            "prune_downloads_dir: removed %d stale artefact(s) from %s", removed, root
        )
    return removed
