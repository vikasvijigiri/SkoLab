"""Per-user cost quotas for paid (LLM / paid-scrape) routes.

The gateway's per-IP limiter bounds request *rate*; it says nothing about how
much one account spends. Each expensive route declares a cost in units
(``require_quota`` in app/api/dependencies.py) and every verified uid gets an
hourly and a daily unit budget, counted in a store shared by all replicas:

1. Redis (``REDIS_URL``) — ``INCRBY`` + ``EXPIREAT`` in one transaction.
2. PostgreSQL ``usage_counters`` — one atomic ``INSERT .. ON CONFLICT`` upsert.
3. Process-local dict — only if both shared stores are down. Availability wins
   over strict accounting: an infrastructure fault must not lock every user
   out, and the fallback still bounds each process.

Fixed windows (UTC hour / UTC day). Budgets are env-tunable:
``USER_QUOTA_ENABLED`` (default true), ``USER_QUOTA_HOURLY_UNITS`` (default
150), ``USER_QUOTA_DAILY_UNITS`` (default 600).
"""

from __future__ import annotations

import datetime
import logging
import os
import random
import time

from sqlalchemy import text

logger = logging.getLogger("skolab.quota")

HOUR = 3600
DAY = 86400

_UPSERT = text(
    "INSERT INTO usage_counters (bucket_key, count, expires_at) "
    "VALUES (:k, :n, :exp) "
    "ON CONFLICT (bucket_key) DO UPDATE "
    "SET count = usage_counters.count + EXCLUDED.count "
    "RETURNING count"
)
_SWEEP = text("DELETE FROM usage_counters WHERE expires_at < :now")

_local: dict[str, tuple[int, float]] = {}
_LOCAL_MAX_KEYS = 20_000


def limits() -> tuple[bool, int, int]:
    """(enabled, hourly_units, daily_units) — read per call so ops can retune."""
    enabled = os.environ.get("USER_QUOTA_ENABLED", "true").lower() in ("1", "true")
    hourly = int(os.environ.get("USER_QUOTA_HOURLY_UNITS", "150"))
    daily = int(os.environ.get("USER_QUOTA_DAILY_UNITS", "600"))
    return enabled, hourly, daily


def _window(now: float, size: int) -> tuple[int, float]:
    bucket = int(now // size)
    return bucket, (bucket + 1) * size


async def _redis_incr(key: str, cost: int, window_end: float) -> int | None:
    from app.db import pg_cache

    client = pg_cache._redis_client
    if not (pg_cache._redis_active and client):
        return None
    try:
        pipe = client.pipeline(transaction=True)
        pipe.incrby(key, cost)
        pipe.expireat(key, int(window_end) + 60)
        value, _ = await pipe.execute()
        return int(value)
    except Exception as exc:
        logger.warning("quota: redis unavailable (%s) — falling back", exc)
        return None


async def _pg_incr(key: str, cost: int, window_end: float) -> int | None:
    from app.db.database import AsyncSessionLocal

    expires = datetime.datetime.fromtimestamp(
        window_end + 60, datetime.timezone.utc
    ).replace(tzinfo=None)
    try:
        async with AsyncSessionLocal() as session:
            value = (
                await session.execute(_UPSERT, {"k": key, "n": cost, "exp": expires})
            ).scalar_one()
            if random.random() < 0.02:
                now = datetime.datetime.now(datetime.timezone.utc).replace(tzinfo=None)
                await session.execute(_SWEEP, {"now": now})
            await session.commit()
            return int(value)
    except Exception as exc:
        logger.warning("quota: postgres unavailable (%s) — falling back", exc)
        return None


def _local_incr(key: str, cost: int, window_end: float, now: float) -> int:
    if len(_local) > _LOCAL_MAX_KEYS:
        for k in [k for k, (_, exp) in _local.items() if exp < now]:
            del _local[k]
    count, exp = _local.get(key, (0, window_end))
    if exp < now:
        count, exp = 0, window_end
    count += cost
    _local[key] = (count, exp)
    return count


async def _incr(key: str, cost: int, window_end: float, now: float) -> int:
    value = await _redis_incr(key, cost, window_end)
    if value is None:
        value = await _pg_incr(key, cost, window_end)
    if value is None:
        value = _local_incr(key, cost, window_end, now)
    return value


class QuotaExceeded(Exception):
    def __init__(self, window: str, limit: int, retry_after: int):
        super().__init__(f"{window} quota of {limit} units exceeded")
        self.window = window
        self.limit = limit
        self.retry_after = retry_after


async def consume(uid: str, cost: int, *, now: float | None = None) -> dict[str, int]:
    """Charge ``cost`` units to ``uid``; raise QuotaExceeded when over budget.

    Returns remaining units per window. A no-op when quotas are disabled.
    """
    enabled, hourly, daily = limits()
    if not enabled:
        return {}
    now = time.time() if now is None else now

    h_bucket, h_end = _window(now, HOUR)
    used_h = await _incr(f"q:{uid}:h:{h_bucket}", cost, h_end, now)
    if used_h > hourly:
        raise QuotaExceeded("hourly", hourly, max(1, int(h_end - now)))

    d_bucket, d_end = _window(now, DAY)
    used_d = await _incr(f"q:{uid}:d:{d_bucket}", cost, d_end, now)
    if used_d > daily:
        raise QuotaExceeded("daily", daily, max(1, int(d_end - now)))

    return {"hourly": hourly - used_h, "daily": daily - used_d}
