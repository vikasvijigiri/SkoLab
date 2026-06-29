"""
SkoLab Hybrid Recommendation Engine — engine.py
================================================
Implements 8 scoring techniques in pure Python + numpy.
No external ML infrastructure required.

Techniques:
  1. Time-weighted exponential decay profile builder
  2. Concept expansion via OpenAlex taxonomy adjacency
  3. TF-IDF cosine similarity (numpy)
  4. Domain-scoped citation PageRank
  5. Serendipity injection (15% adjacent-field discovery)
  6. MMR diversification (Maximal Marginal Relevance)
  7. Team composition optimizer (greedy set-cover)
  8. Bayesian grant success probability
"""

import math
import datetime
from typing import Any, Dict, List, Optional, Set, Tuple
import numpy as np


# ─── Constants ────────────────────────────────────────────────────────────────

DECAY_HALF_LIFE_YEARS = 3.0       # Publication age where weight = 0.5x
RECENT_BOOST_THRESHOLD_DAYS = 365 # Publications within 1 year get 3x weight
OLD_DECAY_THRESHOLD_YEARS = 5.0   # Publications older than 5 years decay to 0.5x
SERENDIPITY_RATIO = 0.15          # 15% of feed = adjacent-field discovery
MMR_LAMBDA = 0.6                  # Trade-off: 0=max diversity, 1=max relevance
NOVELTY_RECENCY_HOURS = 48        # Papers within 48h get +0.25 novelty bonus


# ─── Technique 1: Time-Weighted Exponential Decay Profile ────────────────────

def build_time_weighted_profile(works: List[Dict[str, Any]]) -> Dict[str, float]:
    """
    Build a weighted concept frequency map from a researcher's publications.
    Recent papers (< 1yr) weight = 3x. Old papers (> 5yr) weight = 0.5x.
    All others decay exponentially with half-life = DECAY_HALF_LIFE_YEARS.

    Returns: concept_name -> accumulated weight score
    """
    concept_weights: Dict[str, float] = {}
    today = datetime.date.today()

    for work in works:
        pub_date_str = work.get("publication_date") or ""
        pub_year = work.get("publication_year")
        age_years = 0.0

        try:
            if pub_date_str:
                pub_date = datetime.date.fromisoformat(pub_date_str)
                age_years = (today - pub_date).days / 365.25
            elif pub_year:
                age_years = today.year - int(pub_year)
        except (ValueError, TypeError):
            age_years = 2.0  # safe default

        # Time weight: recent 3x, old 0.5x, middle exponential decay
        age_days = age_years * 365.25
        if age_days <= RECENT_BOOST_THRESHOLD_DAYS:
            time_weight = 3.0
        elif age_years >= OLD_DECAY_THRESHOLD_YEARS:
            time_weight = 0.5
        else:
            time_weight = math.exp(-math.log(2) / DECAY_HALF_LIFE_YEARS * age_years)

        # Accumulate concept weights
        concepts = work.get("concepts") or work.get("topics") or []
        for concept in concepts:
            name = concept.get("display_name") or ""
            level = concept.get("level", 3)
            score = concept.get("score", 0.5)
            if not name:
                continue
            # Level 1/2 concepts are more foundational — weight more
            level_multiplier = max(0.3, 1.0 - (level - 1) * 0.15)
            contribution = time_weight * float(score) * level_multiplier
            concept_weights[name] = concept_weights.get(name, 0.0) + contribution

    return concept_weights


# ─── Technique 2: Concept Expansion via OpenAlex Taxonomy ────────────────────

# Lightweight adjacency map — representative cross-discipline bridges
# In production this would be populated from the full OpenAlex concept tree
_CONCEPT_ADJACENCY: Dict[str, List[str]] = {
    "machine learning": ["deep learning", "neural networks", "reinforcement learning", "computer vision", "natural language processing"],
    "deep learning": ["machine learning", "computer vision", "natural language processing", "neural networks"],
    "natural language processing": ["machine learning", "computational linguistics", "information retrieval", "text mining"],
    "computer vision": ["deep learning", "image processing", "object detection", "pattern recognition"],
    "quantum computing": ["quantum information", "quantum cryptography", "quantum mechanics", "superconductivity", "topological insulators"],
    "bioinformatics": ["genomics", "computational biology", "proteomics", "systems biology", "machine learning"],
    "genomics": ["bioinformatics", "molecular biology", "dna sequencing", "transcriptomics", "epigenetics"],
    "materials science": ["condensed matter physics", "nanotechnology", "quantum mechanics", "chemistry"],
    "climate science": ["atmospheric science", "oceanography", "environmental science", "remote sensing"],
    "neuroscience": ["cognitive science", "machine learning", "molecular biology", "medicine"],
    "economics": ["game theory", "political science", "social science", "statistics"],
    "medicine": ["clinical trials", "genomics", "pharmacology", "public health", "neuroscience"],
}


def expand_concepts(concepts: List[str], depth: int = 1) -> Set[str]:
    """
    Expand a list of researcher concepts to include adjacent disciplines
    from the OpenAlex taxonomy adjacency graph.

    Args:
        concepts: Primary concept names (lowercased)
        depth: How many hops to traverse (1=direct adjacency)

    Returns: Expanded set of concept names including adjacent fields
    """
    expanded: Set[str] = set(c.lower() for c in concepts)
    frontier = set(expanded)

    for _ in range(depth):
        next_frontier: Set[str] = set()
        for concept in frontier:
            for adj_concept in _CONCEPT_ADJACENCY.get(concept, []):
                if adj_concept not in expanded:
                    expanded.add(adj_concept)
                    next_frontier.add(adj_concept)
        frontier = next_frontier
        if not frontier:
            break

    return expanded


# ─── Technique 3: TF-IDF Cosine Similarity ───────────────────────────────────

def _tokenize(text: str) -> List[str]:
    """Lowercase tokenizer removing stopwords."""
    import re
    stopwords = {
        "the", "and", "for", "with", "from", "using", "based", "study",
        "analysis", "paper", "method", "results", "approach", "effects",
        "role", "novel", "new", "efficient", "propose", "present", "are",
        "our", "this", "that", "was", "has", "have", "been", "its", "can",
    }
    words = re.findall(r"\b[a-z]{3,20}\b", text.lower())
    return [w for w in words if w not in stopwords]


def build_tfidf_vector(text: str, vocabulary: List[str]) -> np.ndarray:
    """
    Build a TF-IDF vector for a document against a fixed vocabulary.
    Uses logarithmic TF: tf = 1 + log(count) if count > 0 else 0.
    IDF approximated as 1 / (1 + position_in_vocab) for simplicity.
    """
    tokens = _tokenize(text)
    token_counts: Dict[str, int] = {}
    for t in tokens:
        token_counts[t] = token_counts.get(t, 0) + 1

    total = sum(token_counts.values()) or 1
    vec = np.zeros(len(vocabulary), dtype=np.float32)
    for i, term in enumerate(vocabulary):
        count = token_counts.get(term, 0)
        tf = (1.0 + math.log(count)) if count > 0 else 0.0
        idf = 1.0 / (1.0 + i * 0.01)  # mild position-based IDF
        vec[i] = tf * idf

    norm = np.linalg.norm(vec)
    if norm > 0:
        vec = vec / norm
    return vec


def cosine_similarity(vec_a: np.ndarray, vec_b: np.ndarray) -> float:
    """
    Compute cosine similarity between two L2-normalized vectors.
    Returns value in [0.0, 1.0].
    """
    if vec_a.shape != vec_b.shape or np.linalg.norm(vec_a) == 0 or np.linalg.norm(vec_b) == 0:
        return 0.0
    return float(np.clip(np.dot(vec_a, vec_b), 0.0, 1.0))


# ─── Technique 4: Domain-Scoped Citation PageRank ────────────────────────────

def domain_pagerank(
    candidates: List[Dict[str, Any]],
    damping: float = 0.85,
    iterations: int = 20,
    user_concepts: Optional[Set[str]] = None,
) -> Dict[str, float]:
    """
    Compute a simplified domain-scoped PageRank over candidate papers.
    Papers with more citations from high-cited papers within the same
    domain receive higher influence scores.

    Returns: paper_id -> pagerank_score
    """
    if not candidates:
        return {}

    ids = [p.get("id", str(i)) for i, p in enumerate(candidates)]
    n = len(ids)
    id_to_idx = {pid: i for i, pid in enumerate(ids)}

    # Build adjacency: paper A -> paper B if B cites A (approximated via shared concepts)
    adjacency: Dict[int, List[int]] = {i: [] for i in range(n)}
    for i, paper_a in enumerate(candidates):
        concepts_a = set(
            (c.get("display_name") or "").lower()
            for c in (paper_a.get("concepts") or [])
        )
        for j, paper_b in enumerate(candidates):
            if i == j:
                continue
            concepts_b = set(
                (c.get("display_name") or "").lower()
                for c in (paper_b.get("concepts") or [])
            )
            # Link exists if they share ≥ 2 concepts (in-domain)
            overlap = len(concepts_a & concepts_b)
            if user_concepts:
                # Require at least one concept to match user's domain
                user_overlap = len(concepts_a & user_concepts) + len(concepts_b & user_concepts)
                if overlap >= 2 and user_overlap > 0:
                    adjacency[i].append(j)
            elif overlap >= 2:
                adjacency[i].append(j)

    # Initialize with citation-count prior
    max_citations = max((p.get("cited_by_count") or 0) for p in candidates) or 1
    scores = np.array(
        [(p.get("cited_by_count") or 0) / max_citations for p in candidates],
        dtype=np.float64,
    )
    scores = scores / (scores.sum() or 1.0)

    # Power iteration
    for _ in range(iterations):
        new_scores = np.full(n, (1.0 - damping) / n, dtype=np.float64)
        for i, outlinks in adjacency.items():
            if outlinks:
                contribution = damping * scores[i] / len(outlinks)
                for j in outlinks:
                    new_scores[j] += contribution
        scores = new_scores / (new_scores.sum() or 1.0)

    return {ids[i]: float(scores[i]) for i in range(n)}


# ─── Technique 5: Serendipity Injection ──────────────────────────────────────

def inject_serendipity(
    ranked_papers: List[Dict[str, Any]],
    adjacent_pool: List[Dict[str, Any]],
    feed_size: int = 10,
) -> List[Dict[str, Any]]:
    """
    Replace SERENDIPITY_RATIO of the ranked feed with adjacent-field papers
    to prevent filter bubble effect.

    Args:
        ranked_papers: Papers scored by relevance (descending)
        adjacent_pool: Papers from adjacent disciplines
        feed_size: Target feed size

    Returns: Feed with serendipity papers injected, each flagged serendipity=True
    """
    n_serendipity = max(1, int(feed_size * SERENDIPITY_RATIO))
    n_core = feed_size - n_serendipity

    core = ranked_papers[:n_core]
    serendipity_picks = []
    core_ids = {p.get("id") for p in core}

    for paper in adjacent_pool:
        if paper.get("id") not in core_ids:
            paper = dict(paper)
            paper["_serendipity"] = True
            serendipity_picks.append(paper)
        if len(serendipity_picks) >= n_serendipity:
            break

    # Interleave serendipity papers throughout the feed
    result = []
    serendipity_positions = set(range(n_core // 2, feed_size, feed_size // max(n_serendipity, 1)))
    s_iter = iter(serendipity_picks)
    c_iter = iter(core)
    for pos in range(feed_size):
        if pos in serendipity_positions:
            pick = next(s_iter, None)
            if pick:
                result.append(pick)
                continue
        nxt = next(c_iter, None)
        if nxt:
            result.append(nxt)

    return result


# ─── Technique 6: MMR Diversification ────────────────────────────────────────

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
        candidates: List of (paper_dict, tfidf_vector) tuples
        query_vector: User profile TF-IDF vector
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


# ─── Technique 7: Team Composition Optimizer (Greedy Set Cover) ───────────────

def team_composition_optimizer(
    user_skill_gaps: List[str],
    collaborators: List[Dict[str, Any]],
    max_team_size: int = 3,
) -> List[Dict[str, Any]]:
    """
    Recommend the smallest set of collaborators that covers all user skill gaps.
    Uses greedy set-cover approximation (NP-hard exact, but greedy is (1-1/e)-optimal).

    Args:
        user_skill_gaps: List of skill/concept areas the user lacks
        collaborators: Each collaborator with a `concepts` or `field` list
        max_team_size: Maximum team size to consider

    Returns: Annotated collaborators with `team_composition_role` field set
    """
    if not user_skill_gaps or not collaborators:
        return collaborators

    gaps_lower = set(g.lower() for g in user_skill_gaps)
    remaining_gaps = set(gaps_lower)
    selected = []
    pool = list(collaborators)

    while remaining_gaps and pool and len(selected) < max_team_size:
        best = None
        best_coverage = set()

        for collab in pool:
            # Build concept set for this collaborator
            collab_concepts = set()
            field_str = (collab.get("field") or "").lower()
            if field_str:
                collab_concepts.add(field_str)
            for concept_str in field_str.split(","):
                collab_concepts.add(concept_str.strip())

            coverage = remaining_gaps & collab_concepts
            if len(coverage) > len(best_coverage):
                best = collab
                best_coverage = coverage

        if not best or not best_coverage:
            break

        best = dict(best)
        covered_gaps = sorted(best_coverage)
        best["team_composition_role"] = f"Covers: {', '.join(covered_gaps)}"
        selected.append(best)
        pool.remove(next(c for c in pool if c.get("id") == best.get("id") or c.get("name") == best.get("name")))
        remaining_gaps -= best_coverage

    # Mark remaining collaborators without a specific role
    selected_names = {c.get("name") for c in selected}
    for collab in collaborators:
        if collab.get("name") not in selected_names:
            collab = dict(collab)

    return selected


# ─── Technique 8: Bayesian Grant Success Probability ─────────────────────────

# Prior distribution parameters derived from publicly available award data
_GRANT_PRIORS: Dict[str, Dict[str, float]] = {
    "NSF": {"base_rate": 0.22, "h_index_factor": 0.012, "experience_cap": 0.55},
    "NIH": {"base_rate": 0.20, "h_index_factor": 0.010, "experience_cap": 0.50},
    "ERC": {"base_rate": 0.12, "h_index_factor": 0.015, "experience_cap": 0.45},
    "SERB": {"base_rate": 0.30, "h_index_factor": 0.008, "experience_cap": 0.65},
    "DST": {"base_rate": 0.28, "h_index_factor": 0.009, "experience_cap": 0.62},
    "MoE": {"base_rate": 0.15, "h_index_factor": 0.005, "experience_cap": 0.40},
    "DEFAULT": {"base_rate": 0.20, "h_index_factor": 0.010, "experience_cap": 0.50},
}


def bayesian_grant_probability(
    h_index: int,
    years_active: int,
    works_count: int,
    agency: str,
    field_match_score: float = 0.7,
) -> float:
    """
    Compute a Bayesian posterior probability of grant award success.

    Prior: agency-specific base award rate from historical data.
    Likelihood update: h-index, career experience, publication volume,
                       and field alignment increase/decrease the posterior.

    Args:
        h_index: Researcher h-index
        years_active: Years since first publication
        works_count: Total publications count
        agency: Funding agency name (NSF, NIH, ERC, SERB, DST, MoE)
        field_match_score: [0,1] how well the researcher's field matches grant focus

    Returns: Posterior probability in [0.01, 0.95]
    """
    prior = _GRANT_PRIORS.get(agency, _GRANT_PRIORS["DEFAULT"])
    base_rate = prior["base_rate"]
    h_factor = prior["h_index_factor"]
    cap = prior["experience_cap"]

    # Likelihood components
    h_boost = min(h_index * h_factor, 0.25)
    experience_boost = min(years_active * 0.01, 0.15)
    volume_boost = min(math.log1p(works_count) * 0.01, 0.10)
    field_boost = field_match_score * 0.10

    # Bayesian update (simplified conjugate-like update)
    posterior = base_rate + h_boost + experience_boost + volume_boost + field_boost
    posterior = min(posterior, cap)
    posterior = max(posterior, 0.01)

    return round(posterior, 4)


# ─── Novelty Score Helper ─────────────────────────────────────────────────────

def compute_novelty_score(
    paper: Dict[str, Any],
    all_citations: List[int],
) -> float:
    """
    Compute novelty score combining recency and citation cohort percentile.
    Papers published < 48h ago get +0.25 bonus.
    Papers in top 20% of their age cohort get +0.15 bonus.

    Returns float in [0.0, 1.0].
    """
    score = 0.0

    # Recency bonus
    pub_date_str = paper.get("publication_date") or ""
    try:
        pub_date = datetime.date.fromisoformat(pub_date_str)
        age_hours = (datetime.date.today() - pub_date).days * 24
        if age_hours <= NOVELTY_RECENCY_HOURS:
            score += 0.25
        elif age_hours <= 24 * 7:  # within 1 week
            score += 0.10
    except (ValueError, TypeError):
        pass

    # Citation cohort percentile
    citations = paper.get("cited_by_count") or 0
    if all_citations:
        percentile = sum(1 for c in all_citations if c <= citations) / len(all_citations)
        if percentile >= 0.80:  # Top 20%
            score += 0.15
        elif percentile >= 0.60:
            score += 0.05

    return round(min(score, 1.0), 4)
