"""
test_recommendation_system.py
==============================
Tests for the small shared cosine-similarity/MMR-diversification utility
module (app/domains/recommendation/engine.py) used by the live embeddings-
based daily-feed recommendation path (app/services/platform/pipeline_services.py).

Runs fully offline — no OpenAlex/arXiv network calls, no embedding model
load (uses hand-built vectors, not real embeddings).

Previously also covered a unified paper/grant/collaborator recommendation
endpoint and its 8-technique engine — removed along with that dead code.
See decisions/0007-retire-dormant-unified-recommendations.md.
"""
import numpy as np

from app.domains.recommendation.engine import cosine_similarity, mmr_diversify


# ──────────────────────────────────────────────────────────────────────────────
# Fixtures
# ──────────────────────────────────────────────────────────────────────────────

def _make_paper(
    title: str,
    abstract: str,
    concepts: list,
    cited_by_count: int = 50,
    pub_date: str = "2024-01-01",
    pid: str = None,
) -> dict:
    return {
        "id": pid or f"https://openalex.org/{title[:8].replace(' ', '_')}",
        "title": title,
        "_custom_abstract": abstract,
        "concepts": [{"display_name": c, "level": 1, "score": 0.8} for c in concepts],
        "cited_by_count": cited_by_count,
        "publication_date": pub_date,
        "publication_year": int(pub_date[:4]),
        "authorships": [],
        "primary_location": None,
    }


def _unit_vector(seed: int, dim: int = 8) -> np.ndarray:
    """Deterministic pseudo-random L2-normalized vector for a given seed."""
    rng = np.random.RandomState(seed)
    vec = rng.rand(dim).astype(np.float32)
    norm = np.linalg.norm(vec)
    return vec / norm if norm > 0 else vec


# ──────────────────────────────────────────────────────────────────────────────
# Cosine similarity
# ──────────────────────────────────────────────────────────────────────────────

def test_cosine_similarity_range():
    """Cosine similarity must always be in [0.0, 1.0]."""
    vecs = [_unit_vector(seed) for seed in range(5)]
    for i, va in enumerate(vecs):
        for j, vb in enumerate(vecs):
            score = cosine_similarity(va, vb)
            assert 0.0 <= score <= 1.0, (
                f"cosine_similarity({i},{j}) = {score} is out of [0,1]"
            )


def test_cosine_similarity_identical_texts():
    """Identical vectors should yield similarity close to 1.0."""
    vec = _unit_vector(42)
    score = cosine_similarity(vec, vec)
    assert score >= 0.99, f"Identical vector similarity should be ~1.0, got {score}"


# ──────────────────────────────────────────────────────────────────────────────
# MMR diversification
# ──────────────────────────────────────────────────────────────────────────────

def test_mmr_diversification_output_size():
    """MMR should return exactly k items from candidates."""
    papers = [
        _make_paper(f"Paper {i}", f"machine learning neural networks {i}", ["machine learning"], pid=f"P{i}")
        for i in range(8)
    ]
    candidates_with_vecs = [(p, _unit_vector(i)) for i, p in enumerate(papers)]
    query_vec = _unit_vector(99)
    result = mmr_diversify(candidates_with_vecs, query_vec, k=5)

    assert len(result) == 5, f"MMR should return exactly 5 papers, got {len(result)}"


def test_mmr_diversification_no_duplicates():
    """MMR output should not contain duplicate papers."""
    papers = [
        _make_paper(f"P{i}", f"machine learning {i}", ["machine learning"], pid=f"ID{i}")
        for i in range(6)
    ]
    candidates_with_vecs = [(p, _unit_vector(i)) for i, p in enumerate(papers)]
    query_vec = _unit_vector(7)
    result = mmr_diversify(candidates_with_vecs, query_vec, k=4)

    ids = [p.get("id") for p in result]
    assert len(ids) == len(set(ids)), "MMR output contains duplicate papers"
