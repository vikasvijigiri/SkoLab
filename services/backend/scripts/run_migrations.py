"""Apply Alembic migrations up to head. Wire this as the deploy's pre-deploy /
release command so a schema change ships with the code that needs it.

    python scripts/run_migrations.py

Reads DATABASE_URL from the environment (same as the app and alembic/env.py).
Exits non-zero if the upgrade fails, so a broken migration fails the deploy
instead of leaving a half-migrated schema serving traffic — the 2026-09
`researcher_metrics.openalex_id` drift is exactly what this prevents.
"""

from __future__ import annotations

import os
import sys
from pathlib import Path

_BACKEND_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(_BACKEND_ROOT))


def main() -> int:
    if not os.environ.get("DATABASE_URL"):
        print("[migrate] DATABASE_URL is not set", file=sys.stderr)
        return 2

    from alembic import command
    from alembic.config import Config

    cfg = Config(str(_BACKEND_ROOT / "alembic.ini"))
    cfg.set_main_option("script_location", str(_BACKEND_ROOT / "alembic"))
    cfg.set_main_option("sqlalchemy.url", os.environ["DATABASE_URL"])

    print("[migrate] upgrading to head ...", flush=True)
    try:
        command.upgrade(cfg, "head")
    except Exception as exc:  # noqa: BLE001 - top-level script boundary
        print(f"[migrate] FAILED: {exc}", file=sys.stderr)
        return 1
    print("[migrate] done", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
