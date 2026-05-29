"""
app/db/pg_cache.py
===================
PostgreSQL-backed cache that survives server restarts.

Architecture
------------
• L1  — tiny in-memory dict (30 s TTL) for deduplicating rapid repeated requests.
• L2  — `cache_entries` PostgreSQL table for persistent storage with a configurable TTL.

Usage
-----
    cache = PgBackedCache(ttl_seconds=3600, name="profile")
    await cache.set("key", value)
    result = await cache.get("key")   # returns None on miss / expiry
    await cache.delete("key")
    await cache.clear()               # wipes all keys that share this cache's name prefix

All JSON-serialisable Python objects (dicts, lists, strings, numbers) can be
stored as values.  Pydantic models should be serialised with .dict() first.
"""
from __future__ import annotations

import asyncio
import datetime
import json
import time
from typing import Any, Optional

from sqlalchemy.future import select
from sqlalchemy import delete as sa_delete

from app.db.database import AsyncSessionLocal
from app.models.user_models import CacheEntry


class PgBackedCache:
    """
    Two-level cache: L1 in-memory (fast, 30 s) → L2 PostgreSQL (persistent, configurable TTL).

    Parameters
    ----------
    ttl_seconds : int
        How long (in seconds) a PG entry is considered fresh.
    name : str
        A short prefix added to every cache key so multiple caches can share
        the same `cache_entries` table without key collisions.
    l1_ttl_seconds : int
        In-memory (L1) TTL.  Defaults to 30 s — just long enough to absorb
        burst duplicate requests without hitting the DB repeatedly.
    """

    L1_DEFAULT_TTL = 30  # seconds

    def __init__(self, ttl_seconds: int, name: str, l1_ttl_seconds: int = L1_DEFAULT_TTL):
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
            return {str(key): self._normalize_value(item) for key, item in value.items()}
        if isinstance(value, (list, tuple, set)):
            return [self._normalize_value(item) for item in value]
        return value

    # ── public API ────────────────────────────────────────────────────────────

    async def get(self, key: str) -> Optional[Any]:
        """Return cached value or None if missing / expired."""
        # L1 fast path (no lock needed — dict ops are thread-safe for CPython)
        l1_val = self._l1_get(key)
        if l1_val is not None:
            return l1_val

        # L2 — PostgreSQL
        db_key = self._prefixed(key)
        now = datetime.datetime.utcnow()
        async with AsyncSessionLocal() as session:
            try:
                stmt = select(CacheEntry).where(
                    CacheEntry.cache_key == db_key,
                    CacheEntry.expires_at > now,
                )
                result = await session.execute(stmt)
                entry = result.scalars().first()
                if entry:
                    value = entry.data.get("v")   # unwrap envelope
                    self._l1_set(key, value)       # warm L1
                    return value
            except Exception as exc:
                print(f"[PgCache:{self.name}] GET error for '{key}': {exc}", flush=True)
        return None

    async def set(self, key: str, value: Any) -> None:
        """Persist value to L1 and PG."""
        normalized_value = self._normalize_value(value)
        self._l1_set(key, normalized_value)

        db_key = self._prefixed(key)
        now = datetime.datetime.utcnow()
        expires_at = now + datetime.timedelta(seconds=self.ttl)

        # Wrap in envelope so any JSON-serialisable type is stored safely
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
                print(f"[PgCache:{self.name}] SET error for '{key}': {exc}", flush=True)
                await session.rollback()

    async def delete(self, key: str) -> None:
        """Remove a single key from L1 and PG."""
        self._l1.pop(key, None)

        db_key = self._prefixed(key)
        async with AsyncSessionLocal() as session:
            try:
                await session.execute(
                    sa_delete(CacheEntry).where(CacheEntry.cache_key == db_key)
                )
                await session.commit()
            except Exception as exc:
                print(f"[PgCache:{self.name}] DELETE error for '{key}': {exc}", flush=True)
                await session.rollback()

    async def clear(self) -> None:
        """Wipe all keys that belong to this cache (share this name prefix)."""
        self._l1.clear()

        prefix = f"{self.name}::"
        async with AsyncSessionLocal() as session:
            try:
                # Use LIKE for prefix match — safe because prefix contains no wildcards
                from sqlalchemy import text
                await session.execute(
                    text("DELETE FROM cache_entries WHERE cache_key LIKE :prefix"),
                    {"prefix": prefix + "%"},
                )
                await session.commit()
            except Exception as exc:
                print(f"[PgCache:{self.name}] CLEAR error: {exc}", flush=True)
                await session.rollback()
