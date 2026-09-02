"""
app/db/pg_cache.py
===================
PostgreSQL/Redis-backed cache that survives server restarts.

Architecture
------------
• L1  — tiny in-memory dict (30 s TTL) for deduplicating rapid repeated requests.
• L2  — Persistent Redis L2 Cache (if available) or `cache_entries` Database table.

Usage
-----
    cache = PgBackedCache(ttl_seconds=3600, name="profile")
    await cache.set("key", value)
    result = await cache.get("key")   # returns None on miss / expiry
    await cache.delete("key")
    await cache.clear()               # wipes all keys that share this cache's name prefix
"""

from __future__ import annotations

import os
import json
import asyncio
import datetime
import time
from typing import Any, Optional

import redis.asyncio as aioredis  # type: ignore
from sqlalchemy.future import select
from sqlalchemy import delete as sa_delete

from app.db.database import AsyncSessionLocal, engine
from app.models.user_models import CacheEntry

_IS_POSTGRES = engine.dialect.name == "postgresql"

# Shared Redis client
_redis_client: aioredis.Redis | None = None
_redis_active = False


async def init_redis() -> None:
    """Initialize Redis connection for distributed L2 cache."""
    global _redis_client, _redis_active
    redis_url = os.environ.get("REDIS_URL")
    if not redis_url:
        print(
            "[Redis] REDIS_URL not configured. Using database for L2 caching.",
            flush=True,
        )
        return
    try:
        _redis_client = aioredis.from_url(redis_url, socket_timeout=1.0)
        await _redis_client.ping()
        _redis_active = True
        print(
            f"[Redis] Connected successfully to L2 distributed cache: {redis_url}",
            flush=True,
        )
    except Exception as e:
        _redis_client = None
        _redis_active = False
        print(
            f"[Redis] Connection failed (falling back to database L2 cache): {e}",
            flush=True,
        )


class PgBackedCache:
    """
    Two-level cache: L1 in-memory (fast, 30 s) → L2 Redis/Database (persistent).
    """

    L1_DEFAULT_TTL = 30  # seconds

    def __init__(
        self, ttl_seconds: int, name: str, l1_ttl_seconds: int = L1_DEFAULT_TTL
    ):
        self.ttl = ttl_seconds
        self.name = name
        self.l1_ttl = l1_ttl_seconds
        self._l1: dict[str, tuple[Any, float]] = {}  # key -> (value, expiry_ts)
        self._lock = asyncio.Lock()
        # Single-flight: in-flight compute() calls keyed by cache key. N
        # concurrent misses on the same key await one shared task instead of
        # all N independently hitting the (embedding / LLM / OpenAlex) work —
        # the "cache stampede" when a hot key expires and every user misses.
        self._inflight: dict[str, "asyncio.Task[Any]"] = {}

    # ── helpers ───────────────────────────────────────────────────────────────

    def _prefixed(self, key: str) -> str:
        return f"{self.name}::{key}"

    def _l1_get(self, key: str) -> Optional[Any]:
        entry = self._l1.get(key)
        if entry is None:
            return None
        value, expiry = entry
        if time.monotonic() > expiry:
            del self._l1[key]
            return None
        return value

    def _l1_set(self, key: str, value: Any) -> None:
        # Evict keys older than l1_ttl to keep dict small
        now = time.monotonic()
        stale = [k for k, (_, exp) in self._l1.items() if now > exp]
        for k in stale:
            del self._l1[k]
        self._l1[key] = (value, now + self.l1_ttl)

    def _normalize_value(self, value: Any) -> Any:
        """Convert common rich Python objects into JSON-safe primitives."""
        if hasattr(value, "model_dump"):
            return self._normalize_value(value.model_dump())
        if isinstance(value, dict):
            return {
                str(key): self._normalize_value(item) for key, item in value.items()
            }
        if isinstance(value, (list, tuple, set)):
            return [self._normalize_value(item) for item in value]
        return value

    # ── public API ────────────────────────────────────────────────────────────

    async def get(self, key: str) -> Optional[Any]:
        """Return cached value or None if missing / expired."""
        # L1 fast path
        l1_val = self._l1_get(key)
        if l1_val is not None:
            return l1_val

        db_key = self._prefixed(key)

        # L2 — Redis
        if _redis_active and _redis_client:
            try:
                val = await _redis_client.get(db_key)
                if val is not None:
                    decoded = json.loads(val)
                    self._l1_set(key, decoded)  # warm L1
                    return decoded
            except Exception as exc:
                print(
                    f"[RedisCache:{self.name}] GET error for '{key}': {exc}", flush=True
                )

        # L2 — Database Fallback
        now = datetime.datetime.now(datetime.timezone.utc).replace(tzinfo=None)
        async with AsyncSessionLocal() as session:
            try:
                stmt = select(CacheEntry).where(
                    CacheEntry.cache_key == db_key,
                    CacheEntry.expires_at > now,
                )
                result = await session.execute(stmt)
                entry = result.scalars().first()
                if entry:
                    value = entry.data.get("v")  # unwrap envelope
                    self._l1_set(key, value)  # warm L1
                    return value
            except Exception as exc:
                print(
                    f"[DatabaseCache:{self.name}] GET error for '{key}': {exc}",
                    flush=True,
                )
        return None

    async def set(self, key: str, value: Any) -> None:
        """Persist value to L1 and L2 (Redis/Database)."""
        normalized_value = self._normalize_value(value)
        self._l1_set(key, normalized_value)

        db_key = self._prefixed(key)

        # L2 — Redis
        if _redis_active and _redis_client:
            try:
                await _redis_client.set(
                    db_key, json.dumps(normalized_value), ex=self.ttl
                )
                return
            except Exception as exc:
                print(
                    f"[RedisCache:{self.name}] SET error for '{key}': {exc}", flush=True
                )

        # L2 — Database Fallback
        now = datetime.datetime.now(datetime.timezone.utc).replace(tzinfo=None)
        expires_at = now + datetime.timedelta(seconds=self.ttl)
        payload = {"v": normalized_value}

        async with AsyncSessionLocal() as session:
            try:
                if _IS_POSTGRES:
                    # Atomic upsert — the previous SELECT-then-INSERT/UPDATE let
                    # two concurrent sets on the same key both miss the SELECT
                    # and then race on INSERT (unique-violation) or clobber each
                    # other. ON CONFLICT collapses it to one statement.
                    from sqlalchemy.dialects.postgresql import insert as pg_insert

                    stmt = pg_insert(CacheEntry).values(
                        cache_key=db_key,
                        data=payload,
                        last_synced=now,
                        expires_at=expires_at,
                    )
                    stmt = stmt.on_conflict_do_update(
                        index_elements=[CacheEntry.cache_key],
                        set_={
                            "data": stmt.excluded.data,
                            "last_synced": stmt.excluded.last_synced,
                            "expires_at": stmt.excluded.expires_at,
                        },
                    )
                    await session.execute(stmt)
                else:
                    existing = await session.execute(
                        select(CacheEntry).where(CacheEntry.cache_key == db_key)
                    )
                    entry = existing.scalars().first()
                    if entry:
                        entry.data = payload
                        entry.last_synced = now
                        entry.expires_at = expires_at
                    else:
                        session.add(
                            CacheEntry(
                                cache_key=db_key,
                                data=payload,
                                last_synced=now,
                                expires_at=expires_at,
                            )
                        )
                await session.commit()
            except Exception as exc:
                print(
                    f"[DatabaseCache:{self.name}] SET error for '{key}': {exc}",
                    flush=True,
                )
                await session.rollback()

    async def get_or_compute(self, key: str, compute):
        """Return the cached value for `key`, or run `compute()` (an async
        callable taking no args), cache the result, and return it.

        Concurrent callers that miss the same key share one `compute()` run
        (single-flight) — the rest await its result rather than each doing the
        expensive work. `compute()` raising propagates to every waiter and
        nothing is cached.
        """
        hit = await self.get(key)
        if hit is not None:
            return hit

        existing = self._inflight.get(key)
        if existing is not None:
            return await existing

        async def _run():
            value = await compute()
            if value is not None:
                await self.set(key, value)
            return value

        task = asyncio.ensure_future(_run())
        self._inflight[key] = task
        try:
            return await task
        finally:
            self._inflight.pop(key, None)

    async def delete(self, key: str) -> None:
        """Remove a single key from L1 and L2 (Redis/Database)."""
        self._l1.pop(key, None)
        db_key = self._prefixed(key)

        # L2 — Redis
        if _redis_active and _redis_client:
            try:
                await _redis_client.delete(db_key)
                return
            except Exception as exc:
                print(
                    f"[RedisCache:{self.name}] DELETE error for '{key}': {exc}",
                    flush=True,
                )

        # L2 — Database Fallback
        async with AsyncSessionLocal() as session:
            try:
                await session.execute(
                    sa_delete(CacheEntry).where(CacheEntry.cache_key == db_key)
                )
                await session.commit()
            except Exception as exc:
                print(
                    f"[DatabaseCache:{self.name}] DELETE error for '{key}': {exc}",
                    flush=True,
                )
                await session.rollback()

    async def clear(self) -> None:
        """Wipe all keys that belong to this cache namespace."""
        self._l1.clear()
        prefix = f"{self.name}::"

        # L2 — Redis
        if _redis_active and _redis_client:
            try:
                # Use scan_iter to safely search keys in production without blocking
                keys_to_del = []
                async for k in _redis_client.scan_iter(match=prefix + "*"):
                    keys_to_del.append(k)
                if keys_to_del:
                    await _redis_client.delete(*keys_to_del)
                return
            except Exception as exc:
                print(f"[RedisCache:{self.name}] CLEAR error: {exc}", flush=True)

        # L2 — Database Fallback
        async with AsyncSessionLocal() as session:
            try:
                from sqlalchemy import text

                await session.execute(
                    text("DELETE FROM cache_entries WHERE cache_key LIKE :prefix"),
                    {"prefix": prefix + "%"},
                )
                await session.commit()
            except Exception as exc:
                print(f"[DatabaseCache:{self.name}] CLEAR error: {exc}", flush=True)
                await session.rollback()
