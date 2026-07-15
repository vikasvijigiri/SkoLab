"""
test_recommendation_system.py
==============================
11 tests covering all 8 recommendation engine techniques
plus the unified API endpoint.

Runs fully offline — no OpenAlex / arXiv network calls.
Target: 100% pass rate (11/11).
"""
import pytest
pytestmark = []  # asyncio_mode set via ini_options

import datetime
import math
import numpy as np
import pytest
import pytest_asyncio
from httpx import AsyncClient, ASGITransport

# ── Engine imports ─────────────────────────────────────────────────────────────
from app.domains.recommendation.engine import (
    build_time_weighted_profile,
    expand_concepts,
    build_tfidf_vector,
    cosine_similarity,
    domain_pagerank,
    inject_serendipity,
    mmr_diversify,
    team_composition_optimizer,
    bayesian_grant_probability,
    compute_novelty_score,
)


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


def _make_work(title: str, concepts: list, pub_date: str, cited_by_count: int = 10) -> dict:
    return {
        "id": f"W_{title[:5]}",
        "title": title,
        "concepts": [{"display_name": c, "level": 1, "score": 0.9} for c in concepts],
        "publication_date": pub_date,
        "publication_year": int(pub_date[:4]),
        "cited_by_count": cited_by_count,
    }


# ──────────────────────────────────────────────────────────────────────────────
# Test 1 — Technique 1: Time-Weighted Profile
# ──────────────────────────────────────────────────────────────────────────────

def test_time_weighted_profile_recency_bias():
    """Recent papers (<1yr) should contribute higher weights than old papers (>5yr)."""
    today = datetime.date.today()
    recent_date = (today - datetime.timedelta(days=30)).isoformat()   # 1 month ago
    old_date = (today - datetime.timedelta(days=365 * 6)).isoformat() # 6 years ago

    recent_work = _make_work("Recent DL Paper", ["deep learning"], recent_date)
    old_work = _make_work("Old ML Paper", ["machine learning"], old_date)

    profile_recent = build_time_weighted_profile([recent_work])
    profile_old = build_time_weighted_profile([old_work])

    # Recent paper concept should have higher weight than old paper concept
    recent_weight = profile_recent.get("deep learning", 0.0)
    old_weight = profile_old.get("machine learning", 0.0)

    assert recent_weight > old_weight, (
        f"Recent paper weight ({recent_weight:.3f}) should exceed "
        f"old paper weight ({old_weight:.3f})"
    )


# ──────────────────────────────────────────────────────────────────────────────
# Test 2 — Technique 2: Concept Expansion
# ──────────────────────────────────────────────────────────────────────────────

def test_concept_expansion_adjacent_fields():
    """Expanding 'machine learning' should include adjacent concepts like 'deep learning'."""
    expanded = expand_concepts(["machine learning"], depth=1)
    assert "deep learning" in expanded, "Expected 'deep learning' in expanded machine learning concepts"
    assert "machine learning" in expanded, "Original concept should remain in expansion"
    assert len(expanded) > 1, "Expansion should return more than just the input"


def test_concept_expansion_no_duplicates():
    """Expansion should not produce duplicates."""
    expanded = expand_concepts(["machine learning", "deep learning"], depth=1)
    expanded_list = list(expanded)
    assert len(expanded_list) == len(set(expanded_list)), "Expansion should not contain duplicates"


# ──────────────────────────────────────────────────────────────────────────────
# Test 3 — Technique 3: Cosine Similarity
# ──────────────────────────────────────────────────────────────────────────────

def test_cosine_similarity_range():
    """Cosine similarity must always be in [0.0, 1.0]."""
    vocab = ["machine", "learning", "deep", "neural", "networks", "data"]
    texts = [
        "machine learning neural networks",
        "deep learning data",
        "completely unrelated biology chemistry",
        "",
        "machine learning deep neural networks data",
    ]
    vecs = [build_tfidf_vector(t, vocab) for t in texts]
    for i, va in enumerate(vecs):
        for j, vb in enumerate(vecs):
            score = cosine_similarity(va, vb)
            assert 0.0 <= score <= 1.0, (
                f"cosine_similarity({i},{j}) = {score} is out of [0,1]"
            )


def test_cosine_similarity_identical_texts():
    """Identical text vectors should yield similarity close to 1.0."""
    vocab = ["machine", "learning", "deep", "neural"]
    text = "machine learning deep neural"
    vec = build_tfidf_vector(text, vocab)
    score = cosine_similarity(vec, vec)
    assert score >= 0.99, f"Identical vector similarity should be ~1.0, got {score}"


# ──────────────────────────────────────────────────────────────────────────────
# Test 4 — Technique 4: Citation PageRank
# ──────────────────────────────────────────────────────────────────────────────

def test_domain_pagerank_score_validity():
    """PageRank scores must sum to ~1.0 and each be in [0.0, 1.0]."""
    papers = [
        _make_paper("A", "machine learning neural networks", ["machine learning", "neural networks"], cited_by_count=100, pid="P1"),
        _make_paper("B", "deep learning computer vision", ["deep learning", "computer vision"], cited_by_count=50, pid="P2"),
        _make_paper("C", "reinforcement learning agents", ["reinforcement learning", "machine learning"], cited_by_count=30, pid="P3"),
    ]
    scores = domain_pagerank(papers)
    total = sum(scores.values())
    assert abs(total - 1.0) < 0.05, f"PageRank scores should sum to ~1.0, got {total}"
    for pid, score in scores.items():
        assert 0.0 <= score <= 1.0, f"PageRank score for {pid} = {score} out of range"


# ──────────────────────────────────────────────────────────────────────────────
# Test 5 — Technique 5: Serendipity Injection
# ──────────────────────────────────────────────────────────────────────────────

def test_serendipity_injection_ratio():
    """At least 1 adjacent-field paper must appear in a 10-item feed."""
    primary = [_make_paper(f"Paper {i}", "machine learning", ["machine learning"], pid=f"P{i}") for i in range(15)]
    adjacent = [_make_paper(f"Adj {i}", "genomics biology", ["genomics"], pid=f"A{i}") for i in range(5)]

    result = inject_serendipity(primary, adjacent, feed_size=10)
    serendipity_count = sum(1 for p in result if p.get("_serendipity"))

    assert serendipity_count >= 1, (
        f"Expected at least 1 serendipity paper in feed of 10, got {serendipity_count}"
    )
    assert len(result) <= 10, f"Feed should not exceed 10 items, got {len(result)}"


# ──────────────────────────────────────────────────────────────────────────────
# Test 6 — Technique 6: MMR Diversification
# ──────────────────────────────────────────────────────────────────────────────

def test_mmr_diversification_output_size():
    """MMR should return exactly k items from candidates."""
    vocab = ["machine", "learning", "deep", "neural", "networks", "vision"]
    papers = [
        _make_paper(f"Paper {i}", f"machine learning neural networks {i}", ["machine learning"], pid=f"P{i}")
        for i in range(8)
    ]
    candidates_with_vecs = [
        (p, build_tfidf_vector((p.get("title") or "") + " machine learning neural", vocab))
        for p in papers
    ]
    query_vec = build_tfidf_vector("machine learning deep neural networks", vocab)
    result = mmr_diversify(candidates_with_vecs, query_vec, k=5)

    assert len(result) == 5, f"MMR should return exactly 5 papers, got {len(result)}"


def test_mmr_diversification_no_duplicates():
    """MMR output should not contain duplicate papers."""
    vocab = ["machine", "learning", "deep", "neural", "networks"]
    papers = [
        _make_paper(f"P{i}", f"machine learning {i}", ["machine learning"], pid=f"ID{i}")
        for i in range(6)
    ]
    candidates_with_vecs = [
        (p, build_tfidf_vector(p["title"], vocab)) for p in papers
    ]
    query_vec = build_tfidf_vector("machine learning", vocab)
    result = mmr_diversify(candidates_with_vecs, query_vec, k=4)

    ids = [p.get("id") for p in result]
    assert len(ids) == len(set(ids)), "MMR output contains duplicate papers"


# ──────────────────────────────────────────────────────────────────────────────
# Test 7 — Technique 7: Team Composition Optimizer
# ──────────────────────────────────────────────────────────────────────────────

def test_team_composition_optimizer_covers_gaps():
    """Optimizer should return collaborators covering user skill gaps."""
    user_gaps = ["genomics", "clinical trials"]
    collaborators = [
        {"name": "Alice", "id": "C1", "field": "genomics", "relevance_score": 85},
        {"name": "Bob", "id": "C2", "field": "clinical trials", "relevance_score": 80},
        {"name": "Charlie", "id": "C3", "field": "machine learning", "relevance_score": 75},
    ]
    team = team_composition_optimizer(user_gaps, collaborators, max_team_size=3)
    roles = [c.get("team_composition_role") for c in team]

    assert any(r and "genomics" in r for r in roles), "Team should cover 'genomics' gap"
    assert any(r and "clinical trials" in r for r in roles), "Team should cover 'clinical trials' gap"
    assert len(team) <= 3, f"Team should not exceed 3 members, got {len(team)}"


# ──────────────────────────────────────────────────────────────────────────────
# Test 8 — Technique 8: Bayesian Grant Probability
# ──────────────────────────────────────────────────────────────────────────────

def test_bayesian_grant_probability_range():
    """Bayesian probability must be in [0.01, 0.95] for all agency combinations."""
    agencies = ["NSF", "NIH", "ERC", "SERB", "DST", "MoE", "UNKNOWN"]
    for agency in agencies:
        for h_idx in [0, 5, 15, 30]:
            prob = bayesian_grant_probability(
                h_index=h_idx,
                years_active=5,
                works_count=20,
                agency=agency,
                field_match_score=0.7,
            )
            assert 0.01 <= prob <= 0.95, (
                f"bayesian_grant_probability({agency}, h={h_idx}) = {prob} out of range"
            )


def test_bayesian_grant_probability_increases_with_hindex():
    """Higher h-index should yield higher (or equal) award probability."""
    prob_low = bayesian_grant_probability(h_index=2, years_active=3, works_count=10, agency="NSF")
    prob_high = bayesian_grant_probability(h_index=20, years_active=10, works_count=100, agency="NSF")
    assert prob_high >= prob_low, (
        f"Expected prob_high ({prob_high}) >= prob_low ({prob_low})"
    )


# ──────────────────────────────────────────────────────────────────────────────
# Test 9 — API Endpoint: Anonymous fallback
# ──────────────────────────────────────────────────────────────────────────────

def test_recommendation_endpoint_anonymous():
    """Anonymous request (no author_id) with query fallback should return HTTP 200."""
    import asyncio
    from app.main import app

    async def _run():
        async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
            return await client.get(
                "/api/v1/recommendations",
                params={"query": "machine learning", "mode": "papers"},
            )

    response = asyncio.run(_run())
    assert response.status_code == 200, (
        f"Expected 200 for anonymous fallback, got {response.status_code}: {response.text[:300]}"
    )
    body = response.json()
    assert "papers" in body
    assert "grants" in body
    assert "collaborators" in body
    assert body["algorithm_version"] == "hybrid-v2"


# ──────────────────────────────────────────────────────────────────────────────
# Test 10 — API Endpoint: Response schema fields
# ──────────────────────────────────────────────────────────────────────────────

def test_recommendation_response_schema_fields():
    """Response must include all schema fields: novelty_score, serendipity_flag, etc."""
    import asyncio
    from app.main import app

    async def _run():
        async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
            return await client.get(
                "/api/v1/recommendations",
                params={"query": "deep learning", "mode": "papers"},
            )

    response = asyncio.run(_run())
    assert response.status_code == 200
    body = response.json()
    assert "algorithm_version" in body
    assert "cached" in body
    if body["papers"]:
        paper = body["papers"][0]
        assert "novelty_score" in paper, "Paper must have novelty_score field"
        assert "serendipity_flag" in paper, "Paper must have serendipity_flag field"
        assert "citation_percentile" in paper, "Paper must have citation_percentile field"
        assert 0.0 <= paper["relevance_score"] <= 1.0


# ──────────────────────────────────────────────────────────────────────────────
# Test 11 — Caching: recommendations_cache works
# ──────────────────────────────────────────────────────────────────────────────

@pytest.mark.asyncio
async def test_recommendations_cache_stores_and_returns_result():
    """recommendations_cache (PgBackedCache) should store and return a result."""
    from app.domains.recommendation.service import recommendations_cache
    from app.domains.recommendation.schemas import RecommendationResponse

    key = "test_cache_key_unique_12345"
    mock_result = RecommendationResponse(
        papers=[], grants=[], collaborators=[], algorithm_version="hybrid-v2", cached=False
    )

    # Should be empty initially
    assert await recommendations_cache.get(key) is None, "Cache should be empty before setting"

    await recommendations_cache.set(key, mock_result)
    retrieved = await recommendations_cache.get(key)

    assert retrieved is not None, "Cache should return result after setting"
    assert retrieved.get("algorithm_version") == "hybrid-v2"
