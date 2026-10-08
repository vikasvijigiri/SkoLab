"""Apply Alembic migrations up to head. Wire this as the deploy's pre-deploy /
release command so a schema change ships with the code that needs it.

    python scripts/run_migrations.py

Reads MIGRATION_DATABASE_URL, else DATABASE_URL (same rule as
alembic/env.py). Set MIGRATION_DATABASE_URL to the schema owner once the
running service connects as the least-privilege skolab_app role, which may
not change the schema.
A completely empty database (a new environment: CI staging, a fresh
project) is created from the ORM models and stamped at head, because the
migrations only patch an existing schema. Exits non-zero if the upgrade fails, so a broken migration fails the deploy
instead of leaving a half-migrated schema serving traffic — the 2026-09
`researcher_metrics.openalex_id` drift is exactly what this prevents.
"""

from __future__ import annotations

import asyncio
import os
import sys
from pathlib import Path

_BACKEND_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(_BACKEND_ROOT))


async def _bootstrap_if_empty(url: str) -> bool:
    """Create the schema from the models when the database has neither the
    app's tables nor Alembic's version table. Returns whether it did."""
    from sqlalchemy import text
    from sqlalchemy.ext.asyncio import create_async_engine

    from app.db.database import Base
    import app.models.user_models  # noqa: F401 - registers every table on Base

    engine = create_async_engine(url)
    try:
        async with engine.begin() as conn:
            found = (await conn.execute(text(
                "SELECT to_regclass('public.alembic_version') IS NOT NULL"
                " OR to_regclass('public.users') IS NOT NULL"
            ))).scalar()
            if found:
                return False
            await conn.run_sync(Base.metadata.create_all)
            return True
    finally:
        await engine.dispose()


def migration_url() -> str:
    """The schema owner's URL: MIGRATION_DATABASE_URL, else DATABASE_URL."""
    return os.environ.get("MIGRATION_DATABASE_URL") or os.environ.get("DATABASE_URL", "")


def main() -> int:
    if not migration_url():
        print("[migrate] neither MIGRATION_DATABASE_URL nor DATABASE_URL is set", file=sys.stderr)
        return 2

    from alembic import command
    from alembic.config import Config
    from app.db.url import as_asyncpg_url

    cfg = Config(str(_BACKEND_ROOT / "alembic.ini"))
    cfg.set_main_option("script_location", str(_BACKEND_ROOT / "alembic"))
    cfg.set_main_option("sqlalchemy.url", as_asyncpg_url(migration_url()))

    try:
        if asyncio.run(_bootstrap_if_empty(as_asyncpg_url(migration_url()))):
            # Models reproduce the schema through this revision. Later
            # security/data migrations must run, rather than be stamped away.
            command.stamp(cfg, "b8c9d0e1f2a3")
            print("[migrate] empty database: created schema; applying security migrations", flush=True)
        print("[migrate] upgrading to head ...", flush=True)
        command.upgrade(cfg, "head")
    except Exception as exc:  # noqa: BLE001 - top-level script boundary
        # Several networking exceptions stringify to an empty value. Keep the
        # exception class in deploy logs, but never log the database URL.
        print(f"[migrate] FAILED: {type(exc).__name__}: {exc}", file=sys.stderr)
        return 1
    print("[migrate] done", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
