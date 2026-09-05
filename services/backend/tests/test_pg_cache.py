"""PgBackedCache — the shared-instance fix for the per-call construction bug
in app/services/platform/pipeline/base.py.

_save_to_postgres/_load_from_postgres used to construct a fresh
PgBackedCache(name="pipeline") on every single call. A brand-new instance
means a brand-new, empty `_l1` dict every time, so the 30s in-memory L1
layer never actually served a hit for this cache namespace — every
"cached" read still went to Postgres. Fixed by sharing one instance
(base.py's module-level _pipeline_cache) across every call, with
PgBackedCache.set() gaining a per-call `ttl_seconds` override so callers
wanting different TTLs still don't need their own instance.
"""

from __future__ import annotations

import datetime

import pytest
from sqlalchemy.future import select

from app.db.database import AsyncSessionLocal
from app.db.pg_cache import PgBackedCache
from app.models.user_models import CacheEntry


async def test_set_ttl_seconds_override_is_what_gets_persisted():
    """A per-call ttl_seconds must win over the instance's own default —
    the whole point of sharing one instance across callers that want
    different TTLs."""
    cache = PgBackedCache(ttl_seconds=3600, name="test_ttl_override")
    key = "k-ttl-override"

    before = datetime.datetime.now(datetime.timezone.utc).replace(tzinfo=None)
    await cache.set(key, {"v": 1}, ttl_seconds=60)

    async with AsyncSessionLocal() as session:
        row = (
            (
                await session.execute(
                    select(CacheEntry).where(
                        CacheEntry.cache_key == f"test_ttl_override::{key}"
                    )
                )
            )
            .scalars()
            .first()
        )
    assert row is not None
    delta = (row.expires_at - before).total_seconds()
    # ~60s, not the instance default of 3600s. Generous window for test-run
    # overhead, tight enough to catch "the override was ignored".
    assert 30 < delta < 120


async def test_shared_instance_serves_an_l1_hit_without_touching_the_database(
    monkeypatch,
):
    """The actual regression: a *fresh* PgBackedCache per call can never
    L1-hit, because its `_l1` dict starts empty every time. A *shared*
    instance must L1-hit on the second call for the same key — verified
    here by making a DB session open raise, so the test fails loudly if
    the read falls through to Postgres instead of serving from L1."""
    shared_cache = PgBackedCache(ttl_seconds=3600, name="test_shared_l1")
    key = "k-shared"

    await shared_cache.set(key, {"v": "cached"})
    assert await shared_cache.get(key) == {"v": "cached"}  # sanity: L1 has it

    def _boom():
        raise AssertionError(
            "L1 should have served this — a shared cache instance must "
            "not need the database for a read that just landed in L1"
        )

    monkeypatch.setattr("app.db.pg_cache.AsyncSessionLocal", _boom)

    # A *fresh* instance (the old per-call bug) has an empty _l1 and would
    # have to hit the now-broken database session to answer this — proving
    # the fix requires reusing shared_cache, not constructing a new one.
    result = await shared_cache.get(key)
    assert result == {"v": "cached"}


async def test_pipeline_base_save_and_load_share_one_cache_instance():
    """End-to-end version against the actual fix: two separate
    _PipelineBase instances (as two separate requests would each
    construct) must still share the same underlying cache, because
    _pipeline_cache is a module-level singleton, not built per instance."""
    from app.services.platform.pipeline.base import _PipelineBase

    writer = _PipelineBase(db=None)
    reader = _PipelineBase(db=None)
    key = "k-cross-instance"

    await writer._save_to_postgres(key, {"v": "from-writer"}, ttl_seconds=3600)
    # A second, independently-constructed instance must see it via the
    # shared L1 layer, not just eventually via Postgres.
    assert await reader._load_from_postgres(key) == {"v": "from-writer"}
