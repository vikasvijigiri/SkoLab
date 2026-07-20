"""
SkoLab Recommendation Engine — engine.py
=========================================
Small shared numpy utility module: cosine similarity + MMR diversification.

Used by the live daily-feed path (`app/services/platform/pipeline_services.py`)
on top of self-hosted sentence-transformer embeddings. Previously a larger
"8 scoring techniques" module backing a separate, now-retired unified
recommendation endpoint — see `decisions/0007-retire-dormant-unified-recommendations.md`
for what was removed and why.
"""

from typing import Any, Dict, List, Tuple
import numpy as np


MMR_LAMBDA = 0.6  # Trade-off: 0=max diversity, 1=max relevance


def cosine_similarity(vec_a: np.ndarray, vec_b: np.ndarray) -> float:
    """
    Compute cosine similarity between two L2-normalized vectors.
    Returns value in [0.0, 1.0].
    """
    if vec_a.shape != vec_b.shape or np.linalg.norm(vec_a) == 0 or np.linalg.norm(vec_b) == 0:
        return 0.0
    return float(np.clip(np.dot(vec_a, vec_b), 0.0, 1.0))


def mmr_diversify(
    candidates: List[Tuple[Dict[str, Any], np.ndarray]],
    query_vector: np.ndarray,
    k: int = 10,
    lambda_param: float = MMR_LAMBDA,
) -> List[Dict[str, Any]]:
    """
    Maximal Marginal Relevance re-ranking for diversity.
    Balances relevance to the user query vs. dissimilarity to already-selected papers.

    Args:
        candidates: List of (paper_dict, embedding_vector) tuples
        query_vector: User profile embedding vector
        k: Number of papers to select
        lambda_param: 0=max diversity, 1=max relevance

    Returns: Re-ranked list of k diverse, relevant papers
    """
    if not candidates:
        return []

    selected: List[int] = []
    remaining = list(range(len(candidates)))

    while remaining and len(selected) < k:
        scores = []
        for idx in remaining:
            paper, vec = candidates[idx]
            relevance = cosine_similarity(vec, query_vector)
            if not selected:
                redundancy = 0.0
            else:
                sim_to_selected = [
                    cosine_similarity(vec, candidates[s][1]) for s in selected
                ]
                redundancy = max(sim_to_selected)
            mmr_score = lambda_param * relevance - (1 - lambda_param) * redundancy
            scores.append((mmr_score, idx))

        scores.sort(key=lambda x: x[0], reverse=True)
        best_idx = scores[0][1]
        selected.append(best_idx)
        remaining.remove(best_idx)

    return [candidates[i][0] for i in selected]
