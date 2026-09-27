"""
app/db/pg_cache.py
===================
Shared Redis client bootstrap.

Historically this module also defined ``PgBackedCache``, a two-level
(in-memory + Postgres/Redis) cache used by the feed/papers/authors domains.
Those domains were removed (2026-09-27 scope cut to auth/authorization/CoLab
only) along with every ``PgBackedCache`` instance, but the Redis client
itself stays: ``app/core/quota.py``'s L1 tier and ``main.py``'s
``/health``/``/readyz`` cache probe both read ``_redis_client``/
``_redis_active`` directly.
"""

from __future__ import annotations

import os

import redis.asyncio as aioredis  # type: ignore

# Shared Redis client
_redis_client: aioredis.Redis | None = None
_redis_active = False


async def init_redis() -> None:
    """Initialize the shared Redis connection."""
    global _redis_client, _redis_active
    redis_url = os.environ.get("REDIS_URL")
    if not redis_url:
        print(
            "[Redis] REDIS_URL not configured. Quota falls back to Postgres.",
            flush=True,
        )
        return
    try:
        _redis_client = aioredis.from_url(redis_url, socket_timeout=1.0)
        await _redis_client.ping()
        _redis_active = True
        print("[Redis] Connected successfully.", flush=True)
    except Exception as e:
        _redis_client = None
        _redis_active = False
        print(f"[Redis] Connection failed (falling back to Postgres): {e}", flush=True)
