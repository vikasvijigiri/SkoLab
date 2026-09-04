"""Tests for app/core/pending_compute.py — see that module's docstring for
why it exists (the daily_feed 202/Retry-After prod incident)."""

import asyncio

import pytest

from app.core import pending_compute
from app.core.pending_compute import PENDING, run_bounded


@pytest.fixture(autouse=True)
def _clean_registry():
    """`_inflight` is module-global process state — never let one test's
    leftovers (a still-registered key, or a task from a prior failure)
    bleed into the next."""
    pending_compute._inflight.clear()
    yield
    pending_compute._inflight.clear()


async def test_fast_compute_returns_result_inline():
    async def compute():
        return {"v": 1}

    result = await run_bounded("k1", compute, wait_timeout=1.0)
    assert result == {"v": 1}
    # The task finished and cleaned itself up.
    assert "k1" not in pending_compute._inflight


async def test_slow_compute_returns_pending_then_the_real_result():
    calls = 0
    started = asyncio.Event()

    async def compute():
        nonlocal calls
        calls += 1
        started.set()
        await asyncio.sleep(0.2)
        return "done"

    # First caller times out waiting -- gets PENDING, not an exception, not
    # a truncated/garbage result.
    result = await run_bounded("k2", compute, wait_timeout=0.02)
    assert result is PENDING
    await started.wait()

    # The task must still be running (not cancelled) after the first
    # caller's bounded wait gave up.
    assert "k2" in pending_compute._inflight

    # A second caller for the same key, waiting long enough, gets the real
    # result -- and compute() was never invoked a second time (single-flight).
    result2 = await run_bounded("k2", compute, wait_timeout=1.0)
    assert result2 == "done"
    assert calls == 1
    assert "k2" not in pending_compute._inflight


async def test_concurrent_callers_share_one_compute_run():
    calls = 0

    async def compute():
        nonlocal calls
        calls += 1
        await asyncio.sleep(0.05)
        return "shared"

    results = await asyncio.gather(
        run_bounded("k3", compute, wait_timeout=1.0),
        run_bounded("k3", compute, wait_timeout=1.0),
        run_bounded("k3", compute, wait_timeout=1.0),
    )
    assert results == ["shared", "shared", "shared"]
    assert calls == 1


async def test_a_second_key_after_completion_reruns_compute():
    """Not a cache -- once the in-flight task finishes and is popped, a
    fresh call for the same key runs compute() again. Caching the result
    is the caller's job (the route's own data cache), not this module's."""
    calls = 0

    async def compute():
        nonlocal calls
        calls += 1
        return calls

    first = await run_bounded("k4", compute, wait_timeout=1.0)
    second = await run_bounded("k4", compute, wait_timeout=1.0)
    assert (first, second) == (1, 2)


async def test_compute_exception_propagates_to_a_waiting_caller():
    async def compute():
        raise ValueError("boom")

    with pytest.raises(ValueError, match="boom"):
        await run_bounded("k5", compute, wait_timeout=1.0)
    assert "k5" not in pending_compute._inflight
