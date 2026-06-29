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

from app.db.database import AsyncSessionLocal
from app.models.user_models import CacheEntry

# Shared Redis client
_redis_client: aioredis.Redis | None = None
_redis_active = False

async def init_redis() -> None:
    """Initialize Redis connection for distributed L2 cache."""
    global _redis_client, _redis_active
    redis_url = os.environ.get("REDIS_URL")
    if not redis_url:
        print("[Redis] REDIS_URL not configured. Using database for L2 caching.", flush=True)
        return
    try:
        _redis_client = aioredis.from_url(redis_url, socket_timeout=1.0)
        await _redis_client.ping()
        _redis_active = True
        print(f"[Redis] Connected successfully to L2 distributed cache: {redis_url}", flush=True)
    except Exception as e:
        _redis_client = None
        _redis_active = False
        print(f"[Redis] Connection failed (falling back to database L2 cache): {e}", flush=True)


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
                print(f"[RedisCache:{self.name}] GET error for '{key}': {exc}", flush=True)

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
                print(f"[DatabaseCache:{self.name}] GET error for '{key}': {exc}", flush=True)
        return None

    async def set(self, key: str, value: Any) -> None:
        """Persist value to L1 and L2 (Redis/Database)."""
        normalized_value = self._normalize_value(value)
        self._l1_set(key, normalized_value)

        db_key = self._prefixed(key)

        # L2 — Redis
        if _redis_active and _redis_client:
            try:
                await _redis_client.set(db_key, json.dumps(normalized_value), ex=self.ttl)
                return
            except Exception as exc:
                print(f"[RedisCache:{self.name}] SET error for '{key}': {exc}", flush=True)

        # L2 — Database Fallback
        now = datetime.datetime.now(datetime.timezone.utc).replace(tzinfo=None)
        expires_at = now + datetime.timedelta(seconds=self.ttl)
        payload = {"v": normalized_value}

        async with AsyncSessionLocal() as session:
            try:
                stmt = select(CacheEntry).where(CacheEntry.cache_key == db_key)
                result = await session.execute(stmt)
                entry = result.scalars().first()
                if entry:
                    entry.data = payload
                    entry.last_synced = now
                    entry.expires_at = expires_at
                else:
                    entry = CacheEntry(
                        cache_key=db_key,
                        data=payload,
                        last_synced=now,
                        expires_at=expires_at,
                    )
                    session.add(entry)
                await session.commit()
            except Exception as exc:
                print(f"[DatabaseCache:{self.name}] SET error for '{key}': {exc}", flush=True)
                await session.rollback()

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
                print(f"[RedisCache:{self.name}] DELETE error for '{key}': {exc}", flush=True)

        # L2 — Database Fallback
        async with AsyncSessionLocal() as session:
            try:
                await session.execute(
                    sa_delete(CacheEntry).where(CacheEntry.cache_key == db_key)
                )
                await session.commit()
            except Exception as exc:
                print(f"[DatabaseCache:{self.name}] DELETE error for '{key}': {exc}", flush=True)
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
