"""Security decisions in one shape, mirroring the Go gateway's internal/security.

Each event is a structured log line (message ``security_event``) and a count
on the OpenTelemetry counter ``skolab.security.events{event,outcome}``, the
same metric the gateway emits, so one dashboard covers both services.
"""

from __future__ import annotations

import logging

logger = logging.getLogger("skolab")

ALLOWED, DENIED, THROTTLED, FAILED = "allowed", "denied", "throttled", "failed"

AUTH_TOKEN_INVALID = "auth.token_invalid"
AUTH_SESSION_REVOKED = "auth.session_revoked"
AUTH_EMAIL_UNVERIFIED = "auth.email_unverified"
AUTH_ANONYMOUS_REFUSED = "auth.anonymous_refused"
AUTH_UNAVAILABLE = "auth.unavailable"

_counter = None


def use_meter(meter) -> None:
    global _counter
    _counter = meter.create_counter("skolab.security.events", unit="{event}")


def record(
    event: str, outcome: str, *, reason: str | None = None, actor: str | None = None
) -> None:
    extra = {"event": event, "outcome": outcome}
    if reason:
        extra["reason"] = reason
    if actor:
        extra["actor"] = actor
    level = logging.INFO if outcome == ALLOWED else logging.WARNING
    logger.log(level, "security_event", extra=extra)
    if _counter is not None:
        _counter.add(1, {"event": event, "outcome": outcome})
