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

import os
from typing import List
import numpy as np

_MODEL_NAME = "BAAI/bge-small-en-v1.5"
_model = None

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
        return np.zeros((0, 384), dtype=np.float32)
    import asyncio
    import time

    _t0 = time.monotonic()
    model = _get_model()
    _t_model = time.monotonic()
    max_len = max(len(t) for t in texts)
    vecs = await asyncio.to_thread(
        model.encode, texts, normalize_embeddings=True, convert_to_numpy=True
    )
    _t_encode = time.monotonic()
    print(
        f"[EmbeddingService][TIMING] get_model={_t_model - _t0:.2f}s "
        f"to_thread_encode={_t_encode - _t_model:.2f}s n={len(texts)} max_char_len={max_len}",
        flush=True,
    )
    return vecs.astype(np.float32)


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
