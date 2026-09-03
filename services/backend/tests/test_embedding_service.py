"""embedding_service — API/local/degrade orchestration and the L2-norm helper.

No network, no model. The two backends are monkeypatched; what's under test is
that embed_texts picks API → local → zero-vector degradation in that order,
never raises, honours the vector cache, and returns (N, 384) float32 in the
caller's order.
"""

from __future__ import annotations

import numpy as np
import pytest

from app.services.ai import embedding_service as es


class _FakeCache:
    """In-memory stand-in for PgBackedCache — no DB."""

    def __init__(self) -> None:
        self.store: dict[str, object] = {}
        self.set_calls = 0

    async def get(self, key: str):
        return self.store.get(key)

    async def set(self, key: str, value) -> None:
        self.set_calls += 1
        self.store[key] = value


@pytest.fixture
def fake_cache(monkeypatch):
    c = _FakeCache()
    monkeypatch.setattr(es, "_get_vec_cache", lambda: c)
    return c


def _unit_rows(n: int) -> np.ndarray:
    a = np.ones((n, es._EMBED_DIM), dtype=np.float32)
    return a / np.sqrt(es._EMBED_DIM)


async def test_empty_input_returns_empty(fake_cache):
    out = await es.embed_texts([])
    assert out.shape == (0, es._EMBED_DIM)


async def test_uses_api_when_available(monkeypatch, fake_cache):
    calls: dict[str, int] = {"api": 0, "local": 0}

    async def _api(texts):
        calls["api"] += 1
        return _unit_rows(len(texts))

    async def _local(texts):
        calls["local"] += 1
        return _unit_rows(len(texts))

    monkeypatch.setattr(es, "_embed_via_api", _api)
    monkeypatch.setattr(es, "_embed_via_local", _local)

    out = await es.embed_texts(["a", "b", "c"])
    assert out.shape == (3, es._EMBED_DIM)
    assert out.dtype == np.float32
    assert calls == {"api": 1, "local": 0}
    assert fake_cache.set_calls == 3  # every fresh vector cached


async def test_falls_back_to_local_when_api_returns_none(monkeypatch, fake_cache):
    called = {"local": 0}

    async def _api(_texts):
        return None

    async def _local(texts):
        called["local"] += 1
        return _unit_rows(len(texts))

    monkeypatch.setattr(es, "_embed_via_api", _api)
    monkeypatch.setattr(es, "_embed_via_local", _local)

    out = await es.embed_texts(["x"])
    assert out.shape == (1, es._EMBED_DIM)
    assert called["local"] == 1


async def test_degrades_to_zero_vectors_when_no_backend(monkeypatch, fake_cache):
    async def _none(_texts):
        return None

    monkeypatch.setattr(es, "_embed_via_api", _none)
    monkeypatch.setattr(es, "_embed_via_local", _none)

    out = await es.embed_texts(["p", "q"])
    assert out.shape == (2, es._EMBED_DIM)
    assert np.array_equal(out, np.zeros((2, es._EMBED_DIM), dtype=np.float32))
    assert fake_cache.set_calls == 0  # degraded zeros are never cached


async def test_cache_hit_skips_backend(monkeypatch, fake_cache):
    n = {"api": 0}

    async def _api(texts):
        n["api"] += 1
        return _unit_rows(len(texts))

    monkeypatch.setattr(es, "_embed_via_api", _api)
    monkeypatch.setattr(es, "_embed_via_local", lambda _t: None)

    await es.embed_texts(["same"])
    await es.embed_texts(["same"])
    assert n["api"] == 1  # second call served entirely from cache


async def test_normalize_rows_unit_length_and_zero_safe():
    arr = np.array([[3.0, 4.0], [0.0, 0.0]], dtype=np.float32)
    out = es._normalize_rows(arr)
    assert np.isclose(np.linalg.norm(out[0]), 1.0)
    assert np.array_equal(out[1], np.zeros(2, dtype=np.float32))
