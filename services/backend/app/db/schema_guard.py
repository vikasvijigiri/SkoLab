"""Startup schema-drift guard.

The 2026-09 Sentry audit found production serving against a DB whose
``researcher_metrics`` table pre-dated the ``openalex_id`` primary key the ORM
expects — ``alembic upgrade head`` had never run there. Every read is wrapped in
``try/except`` and logged, so the app kept returning 200s and the drift only
surfaced days later as a pile of ``Could not locate column in row`` issues.

``check_schema_current`` runs once at startup, is a pure read, never raises, and
logs ONE loud, actionable line when the DB is behind ``alembic head`` — so the
next drift is caught in seconds, in the deploy logs, not in Sentry a week on.

Applying migrations is a release step, not an in-process action:
``scripts/run_migrations.py`` (wire it as the platform's pre-deploy command).
"""

from __future__ import annotations

import logging
import os
from pathlib import Path

from sqlalchemy import text

from app.core.config import settings
from app.db.database import engine

logger = logging.getLogger("skolab")

_BACKEND_ROOT = Path(__file__).resolve().parents[2]
_ALEMBIC_INI = _BACKEND_ROOT / "alembic.ini"


def _alembic_head() -> str | None:
    """The latest revision the code ships, or None if alembic can't be read."""
    try:
        from alembic.config import Config
        from alembic.script import ScriptDirectory

        cfg = Config(str(_ALEMBIC_INI))
        cfg.set_main_option("script_location", str(_BACKEND_ROOT / "alembic"))
        cfg.set_main_option("sqlalchemy.url", os.environ.get("DATABASE_URL", ""))
        return ScriptDirectory.from_config(cfg).get_current_head()
    except Exception as exc:  # pragma: no cover - packaging/path issue
        logger.warning("schema_guard: could not read alembic head: %s", exc)
        return None


async def _current_db_revision() -> str | None:
    """The revision the live DB is stamped at, or None if never migrated."""
    try:
        async with engine.connect() as conn:
            row = await conn.execute(
                text("SELECT version_num FROM alembic_version LIMIT 1")
            )
            return row.scalar_one_or_none()
    except Exception:
        return None


async def check_schema_current() -> bool:
    """Warn loudly on drift. Returns True at head / when the check can't run."""
    # Local dev owns its schema via create_all; alembic drift is expected there.
    if settings.environment != "production" and settings.run_schema_create_all:
        return True

    head = _alembic_head()
    if head is None:
        return True

    current = await _current_db_revision()
    if current == head:
        logger.info("schema_guard: database is at alembic head (%s)", head)
        return True

    logger.error(
        "SCHEMA DRIFT — database is at revision %r but this build expects %r. "
        "Reads against migrated tables can fail with 'Could not locate column in "
        "row'. Fix: run `python scripts/run_migrations.py` (or `alembic upgrade "
        "head`) against this database as a release step.",
        current or "<none / never migrated>",
        head,
    )
    return False
