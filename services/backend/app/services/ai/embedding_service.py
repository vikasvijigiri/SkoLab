"""
app/services/ai/embedding_service.py
=====================================
Self-hosted neural text embeddings for semantic similarity — replaces the
recommendation engine's earlier TF-IDF/bag-of-words scoring with real
transformer-based sentence embeddings.

Model: BAAI/bge-small-en-v1.5 (384-dim, ~130MB). Chosen over an API-based
embedding provider (OpenAI/Gemini/Cohere) because no such provider/key is
configured in this deployment (only Groq/OpenRouter for chat completions,
neither of which offers embeddings) — self-hosting avoids adding a new
external dependency, per-request network latency, and API cost, at the
price of a larger Docker image. Weights are pre-downloaded at image build
time (see Dockerfile) so runtime never depends on HuggingFace Hub being
reachable.

The model is loaded once as a module-level singleton — loading takes ~1-2s
and must not happen per-request.
"""

import asyncio
import hashlib
import os
from typing import List, Optional
import numpy as np

_MODEL_NAME = "BAAI/bge-small-en-v1.5"
_EMBED_DIM = 384
_model = None

# ── Content-hash vector cache ────────────────────────────────────────────────
# text -> vector is deterministic for a fixed model, and the same paper abstract
# is re-embedded across the daily feed, journal advisor and grant match. Cache
# each vector in L2 keyed by sha256(model + text) so a warm text costs a dict /
# Redis lookup instead of a CPU forward pass.
_vec_cache = None


def _get_vec_cache():
    global _vec_cache
    if _vec_cache is None:
        from app.core.config import settings
        from app.db.pg_cache import PgBackedCache

        _vec_cache = PgBackedCache(
            ttl_seconds=settings.embed_vector_cache_ttl_seconds, name="embed_vec"
        )
    return _vec_cache


def _vec_key(text: str) -> str:
    return hashlib.sha256(f"{_MODEL_NAME}\x00{text}".encode("utf-8")).hexdigest()


# ── Concurrency cap ──────────────────────────────────────────────────────────
# A forward pass is CPU-bound and single-machine. Without a cap, 100 concurrent
# callers each spawn a worker thread that fights over the same 1-2 cores, so the
# whole set runs slower than a small serialised queue would. This bounds the
# in-flight forward passes; callers past the limit await their turn (the "queue"
# the UI surfaces as a working... state). Size = EMBED_MAX_CONCURRENCY, or the
# container's visible core count when that is 0.
_embed_semaphore: Optional[asyncio.Semaphore] = None


def _embed_concurrency_limit() -> int:
    from app.core.config import settings

    if settings.embed_max_concurrency > 0:
        return settings.embed_max_concurrency
    try:
        return max(1, len(os.sched_getaffinity(0)))
    except AttributeError:
        return max(1, os.cpu_count() or 1)


def _get_embed_semaphore() -> asyncio.Semaphore:
    global _embed_semaphore
    if _embed_semaphore is None:
        _embed_semaphore = asyncio.Semaphore(_embed_concurrency_limit())
    return _embed_semaphore


# bge models are trained asymmetrically: prefixing the *query* side with this
# instruction measurably improves retrieval quality, while the *passage*
# (candidate document) side should stay unprefixed. See the model card.
QUERY_PREFIX = "Represent this sentence for searching relevant passages: "


def _configure_torch_threads() -> None:
    """
    torch defaults its intra-op thread pool to the HOST's visible CPU count
    (e.g. 12 on this machine), not the container's actual cgroup CPU quota
    (e.g. 2). That oversubscription — 6+ threads fighting over 2 real cores —
    caused a measured 87s to embed 144 short texts in-process, versus 3.7s for
    the identical call in an isolated script (which happened to run when
    nothing else in the container was contending for CPU). os.sched_getaffinity
    reports the cgroup-limited core set on Linux; fall back to os.cpu_count()
    where that's unavailable.
    """
    try:
        import torch

        n_cores = len(os.sched_getaffinity(0))
    except (AttributeError, ImportError):
        try:
            import torch

            n_cores = os.cpu_count() or 1
        except ImportError:
            return
    torch.set_num_threads(max(1, n_cores))


def _get_model():
    global _model
    if _model is None:
        _configure_torch_threads()
        from sentence_transformers import SentenceTransformer

        _model = SentenceTransformer(_MODEL_NAME)
    return _model


async def embed_texts(texts: List[str]) -> np.ndarray:
    """
    Batch-embeds texts in a single forward pass. Returns (N, 384) L2-normalized
    vectors. Runs in a worker thread — model.encode() is a long-running,
    CPU-bound synchronous call, and running it directly on the asyncio event
    loop blocks every other coroutine (including other in-flight requests) for
    its full duration.
    """
    if not texts:
        return np.zeros((0, _EMBED_DIM), dtype=np.float32)
    import time

    _t0 = time.monotonic()

    # 1. Serve what we can from the content-hash cache; collect unique misses.
    cache = _get_vec_cache()
    keys = [_vec_key(t) for t in texts]
    cached: dict[str, np.ndarray] = {}
    misses: dict[str, str] = {}  # key -> text (deduped)
    for key, text in zip(keys, texts):
        if key in cached or key in misses:
            continue
        hit = None
        try:
            hit = await cache.get(key)
        except Exception as exc:  # cache must never break embedding
            print(f"[EmbeddingService] vec cache GET failed: {exc}", flush=True)
        if hit is not None:
            cached[key] = np.asarray(hit, dtype=np.float32)
        else:
            misses[key] = text

    _t_cache = time.monotonic()
    _encode_secs = 0.0
    if misses:
        miss_keys = list(misses.keys())
        miss_texts = [misses[k] for k in miss_keys]
        model = _get_model()
        _t_model = time.monotonic()
        async with _get_embed_semaphore():
            fresh = await asyncio.to_thread(
                model.encode,
                miss_texts,
                normalize_embeddings=True,
                convert_to_numpy=True,
            )
        _encode_secs = time.monotonic() - _t_model
        fresh = fresh.astype(np.float32)
        for key, vec in zip(miss_keys, fresh):
            cached[key] = vec
            try:
                await cache.set(key, [round(float(x), 7) for x in vec])
            except Exception as exc:
                print(f"[EmbeddingService] vec cache SET failed: {exc}", flush=True)

    # 2. Reassemble in the caller's original order.
    out = np.stack([cached[k] for k in keys]).astype(np.float32)
    print(
        f"[EmbeddingService][TIMING] cache_lookup={_t_cache - _t0:.2f}s "
        f"encode={_encode_secs:.2f}s n={len(texts)} misses={len(misses)} "
        f"max_char_len={max(len(t) for t in texts)}",
        flush=True,
    )
    return out


async def embed_text(text: str) -> np.ndarray:
    result = await embed_texts([text])
    return result[0]


async def embed_query(text: str) -> np.ndarray:
    """Embeds a search/profile query with the bge instruction prefix."""
    return await embed_text(QUERY_PREFIX + text)


async def score_candidates_against_profile(
    profile_text: str, candidate_texts: List[str]
) -> List[int]:
    """
    Calibrated 40-98 relevance scores for candidate_texts against profile_text,
    via embedding cosine similarity. Used to ground LLM- or scraper-reported
    "match scores" (journal advisor, industry opportunities) in a real semantic
    signal instead of trusting a self-reported number — the same embedding
    model, mean-centering, and 40+((raw-0.45)/0.35*57) calibration already used
    for the daily feed's displayed match % (see
    pipeline_services.get_daily_feed's process_paper for the full rationale on
    why raw, not mean-centered, similarity is what a user-facing score should
    reflect).
    """
    if not candidate_texts:
        return []
    from app.domains.recommendation.engine import cosine_similarity

    query_vec = await embed_query(profile_text)
    candidate_vecs = await embed_texts(candidate_texts)

    scores = []
    for cvec in candidate_vecs:
        raw_sim = cosine_similarity(cvec, query_vec)
        calibrated = 40 + (raw_sim - 0.45) / 0.35 * 57
        scores.append(round(min(98, max(40, calibrated))))
    return scores
