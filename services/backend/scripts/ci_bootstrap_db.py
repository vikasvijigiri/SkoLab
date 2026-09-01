"""Create the test schema against the CI Postgres, with readiness + retry.

`conftest.py` only bootstraps a schema on its SQLite fallback; against a real
Postgres it uses the DB as-is. The alembic scripts only patch an assumed
schema, so they cannot bootstrap either. This runs `init_db()`
(`Base.metadata.create_all` + column patches) the way `conftest` does on SQLite.

Retried because the GitHub service container can pass its health check a moment
before it accepts queries, which aborts `init_db()`'s single transaction
mid-way (`InFailedSQLTransactionError`) and makes the job flaky.
"""

import asyncio
import sys

from sqlalchemy import text

from app.db.database import engine, init_db


async def _wait_for_pg(attempts: int = 30) -> None:
    for i in range(attempts):
        try:
            async with engine.connect() as conn:
                await conn.execute(text("SELECT 1"))
            return
        except Exception as exc:
            print(f"[ci] postgres not ready ({i + 1}/{attempts}): {exc}", flush=True)
            await asyncio.sleep(2)
    sys.exit("[ci] postgres never became reachable")


async def _bootstrap(attempts: int = 5) -> None:
    last: Exception | None = None
    for i in range(attempts):
        try:
            await init_db()
            print("[ci] schema bootstrap ok", flush=True)
            return
        except Exception as exc:
            last = exc
            print(f"[ci] init_db attempt {i + 1}/{attempts} failed: {exc}", flush=True)
            await asyncio.sleep(3)
    sys.exit(f"[ci] init_db failed after {attempts} attempts: {last}")


async def main() -> None:
    await _wait_for_pg()
    await _bootstrap()


if __name__ == "__main__":
    asyncio.run(main())
