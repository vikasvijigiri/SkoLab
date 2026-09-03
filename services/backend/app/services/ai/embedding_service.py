"""
app/services/ai/embedding_service.py
=====================================
Neural text embeddings for semantic similarity — powers the recommendation
engine's match scores, replacing the earlier TF-IDF/bag-of-words scoring.

Model: BAAI/bge-small-en-v1.5 (384-dim). Two backends, tried in order:

1. **Hugging Face Inference API** when ``HF_INFERENCE_TOKEN`` is set — no local
   model, no PyTorch in the image (~2 GB saved), so the service fits a
   512 MB free-tier box next to the Go gateway.
2. **Local ``sentence-transformers``** when that package is importable — used
   in dev/CI and any self-hosted deploy that still bundles it.

If neither is available (or the API is rate-limited / down), ``embed_texts``
degrades to zero vectors and logs a warning rather than raising: callers
(daily feed, journal advisor, grant match) still return results, with match
scores floored to the minimum, instead of a 500.

Text→vector is deterministic for a fixed model, so results are cached in L2
keyed by ``sha256(model + text)``; a warm text costs a dict/Redis lookup.
"""

import asyncio
import hashlib
import logging
import os
from typing import List, Optional

import numpy as np

logger = logging.getLogger("skolab.embedding")

_MODEL_NAME = "BAAI/bge-small-en-v1.5"
_EMBED_DIM = 384
# Batch size for the Inference API — keeps a cold-start request (whole daily
# feed uncached) under the payload/time limits of the free tier.
_API_BATCH = 64
_local_model = None

# ── Content-hash vector cache ────────────────────────────────────────────────
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


# ── Concurrency cap (local backend only) ─────────────────────────────────────
# A local forward pass is CPU-bound and single-machine. Without a cap, N
# concurrent callers each spawn a worker thread fighting over the same 1-2
# cores. Bounds the in-flight local passes; the API backend is network-bound
# and does not use this.
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


def _normalize_rows(arr: np.ndarray) -> np.ndarray:
    """L2-normalize each row; a zero row stays zero (no divide-by-zero)."""
    norms = np.linalg.norm(arr, axis=1, keepdims=True)
    norms[norms == 0.0] = 1.0
    return (arr / norms).astype(np.float32)


# ── Backend 1: Hugging Face Inference API ────────────────────────────────────


async def _embed_via_api(texts: List[str]) -> Optional[np.ndarray]:
    """Feature-extraction via the HF Inference API. Returns an L2-normalized
    ``(N, 384)`` array, or ``None`` on any failure (caller degrades)."""
    from app.core.config import settings

    token = settings.hf_inference_token
    if not token:
        return None

    import httpx

    url = f"{settings.hf_inference_base_url.rstrip('/')}/{_MODEL_NAME}"
    headers = {"Authorization": f"Bearer {token}"}
    out: List[np.ndarray] = []
    try:
        async with httpx.AsyncClient(timeout=settings.http_timeout_seconds) as client:
            for start in range(0, len(texts), _API_BATCH):
                batch = texts[start : start + _API_BATCH]
                payload = {"inputs": batch, "options": {"wait_for_model": False}}
                resp = None
                for attempt in (1, 2):
                    resp = await client.post(url, headers=headers, json=payload)
                    if resp.status_code == 503 and attempt == 1:
                        # model cold — give it a moment, then try once more
                        await asyncio.sleep(3.0)
                        continue
                    break
                resp.raise_for_status()
                arr = np.asarray(resp.json(), dtype=np.float32)
                if arr.ndim == 3:  # token-level output — mean-pool to sentence
                    arr = arr.mean(axis=1)
                if arr.ndim != 2 or arr.shape[1] != _EMBED_DIM:
                    raise ValueError(f"unexpected HF embedding shape {arr.shape}")
                out.append(_normalize_rows(arr))
        return np.vstack(out) if out else np.zeros((0, _EMBED_DIM), dtype=np.float32)
    except Exception as exc:
        logger.warning("HF Inference API embedding failed: %s", exc)
        return None


# ── Backend 2: local sentence-transformers ───────────────────────────────────


def _configure_torch_threads() -> None:
    """Pin torch's intra-op pool to the container's cgroup CPU quota, not the
    host's visible core count — oversubscription made a 144-text batch take
    ~87 s instead of ~4 s. No-op when torch is absent."""
    try:
        import torch

        try:
            n_cores = len(os.sched_getaffinity(0))
        except AttributeError:
            n_cores = os.cpu_count() or 1
        torch.set_num_threads(max(1, n_cores))
    except ImportError:
        return


def _get_local_model():
    """The ``sentence-transformers`` model, or raises ``ImportError`` if the
    package is not installed (the API backend is then the only option)."""
    global _local_model
    if _local_model is None:
        _configure_torch_threads()
        from sentence_transformers import SentenceTransformer

        _local_model = SentenceTransformer(_MODEL_NAME)
    return _local_model


async def _embed_via_local(texts: List[str]) -> Optional[np.ndarray]:
    try:
        model = _get_local_model()
    except ImportError:
        return None
    async with _get_embed_semaphore():
        arr = await asyncio.to_thread(
            model.encode, texts, normalize_embeddings=True, convert_to_numpy=True
        )
    return arr.astype(np.float32)


async def _embed_backend(texts: List[str]) -> Optional[np.ndarray]:
    """API first, local fallback, else ``None``."""
    via_api = await _embed_via_api(texts)
    if via_api is not None:
        return via_api
    return await _embed_via_local(texts)


# ── Public API ──────────────────────────────────────────────────────────────


async def embed_texts(texts: List[str]) -> np.ndarray:
    """Batch-embeds texts. Returns ``(N, 384)`` L2-normalized float32 vectors,
    in the caller's original order. Cached texts are served from L2; misses go
    to the backend. If no backend is available the misses come back as zero
    vectors (logged) so callers still return a result."""
    if not texts:
        return np.zeros((0, _EMBED_DIM), dtype=np.float32)

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
            logger.warning("vec cache GET failed: %s", exc)
        if hit is not None:
            cached[key] = np.asarray(hit, dtype=np.float32)
        else:
            misses[key] = text

    if misses:
        miss_keys = list(misses.keys())
        miss_texts = [misses[k] for k in miss_keys]
        fresh = await _embed_backend(miss_texts)
        if fresh is None:
            logger.warning(
                "no embedding backend available — returning zero vectors for "
                "%d text(s); recommendation scores will floor.",
                len(miss_texts),
            )
            for key in miss_keys:
                cached[key] = np.zeros(_EMBED_DIM, dtype=np.float32)
        else:
            fresh = fresh.astype(np.float32)
            for key, vec in zip(miss_keys, fresh):
                cached[key] = vec
                try:
                    await cache.set(key, [round(float(x), 7) for x in vec])
                except Exception as exc:
                    logger.warning("vec cache SET failed: %s", exc)

    return np.stack([cached[k] for k in keys]).astype(np.float32)


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
