"""Error aggregation (Sentry), initialised only when a DSN is configured.

Mirrors the web app's ``enabled: Boolean(dsn)`` pattern
(``apps/web/src/sentry.server.config.ts``): with no ``SENTRY_DSN`` set,
``init_observability()`` is a no-op and the SDK is never initialised.
"""

from __future__ import annotations

import logging

import sentry_sdk
from sentry_sdk.integrations.fastapi import FastApiIntegration
from sentry_sdk.integrations.starlette import StarletteIntegration

from app.core.config import settings

logger = logging.getLogger("skolab")


def init_observability() -> None:
    """Initialise Sentry iff ``settings.sentry_dsn`` is set. Idempotent-safe."""
    dsn = settings.sentry_dsn
    if not dsn:
        logger.info("Sentry disabled — no SENTRY_DSN set")
        return

    sentry_sdk.init(
        dsn=dsn,
        environment=settings.environment,
        # Env-configurable (SENTRY_TRACES_SAMPLE_RATE) — see config.py's
        # sentry_traces_sample_rate docstring for why this is no longer 0.0.
        traces_sample_rate=settings.sentry_traces_sample_rate,
        send_default_pii=False,
        integrations=[FastApiIntegration(), StarletteIntegration()],
    )
    logger.info(
        "Sentry enabled (environment=%s, traces_sample_rate=%s)",
        settings.environment,
        settings.sentry_traces_sample_rate,
    )
