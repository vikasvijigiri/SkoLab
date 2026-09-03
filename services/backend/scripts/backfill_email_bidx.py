"""Backfill ``users.email_bidx`` for rows that predate the blind-index column.

Run once as a release step, AFTER ``alembic upgrade head`` and with the same
``EMAIL_BLIND_INDEX_KEY`` / ``DATABASE_ENCRYPTION_KEY`` the app uses:

    cd services/backend
    EMAIL_BLIND_INDEX_KEY=... DATABASE_ENCRYPTION_KEY=... \
        python scripts/backfill_email_bidx.py

Idempotent: only touches rows where ``email_bidx IS NULL AND email IS NOT
NULL``. The ORM decrypts ``email`` on read; this recomputes the HMAC and
writes it back. Safe to re-run. Exits non-zero if the key is unset.
"""

from __future__ import annotations

import asyncio
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.core.config import settings  # noqa: E402
from app.db.blind_index import email_blind_index  # noqa: E402
from app.db.database import AsyncSessionLocal  # noqa: E402
from app.models.user_models import User  # noqa: E402
from sqlalchemy import select  # noqa: E402

_BATCH = 500


async def _run() -> int:
    if not settings.email_blind_index_key:
        print("EMAIL_BLIND_INDEX_KEY is not set — nothing to backfill.")
        return 1

    updated = 0
    async with AsyncSessionLocal() as session:
        result = await session.execute(
            select(User).where(User.email_bidx.is_(None), User.email.isnot(None))
        )
        rows = result.scalars().all()
        for i, user in enumerate(rows, 1):
            bidx = email_blind_index(user.email)
            if bidx is None:
                continue
            user.email_bidx = bidx
            updated += 1
            if i % _BATCH == 0:
                await session.commit()
        await session.commit()

    print(f"backfilled email_bidx for {updated} user row(s).")
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(_run()))
