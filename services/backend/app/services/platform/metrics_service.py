import numpy as np
import networkx as nx
from typing import List, Dict, Optional
import math
import json
import os
from app.services.data.openalex_service import OpenAlexService
from app.services.data.scraping_service import ScrapingService


class MetricsService:
    """
    Implements the 10 Modern Research Metrics for scientific evaluation.
    """

    def __init__(self):
        self.taxonomy = self._load_taxonomy()

    def _load_taxonomy(self):
        try:
            path = os.path.join(
                os.path.dirname(__file__), "..", "data", "arxiv_taxonomy.json"
            )
            if os.path.exists(path):
                with open(path, "r") as f:
                    return json.load(f)
        except Exception:
            pass
        return {}

    @staticmethod
    def calculate_disruption_score(n1: int, n2: int, n3: int) -> float:
        """
        1. Disruption Score (D-Index)
        D = (N1 - N2) / (N1 + N2 + N3)
        """
        denom = n1 + n2 + n3
        return round((n1 - n2) / denom, 3) if denom > 0 else 0.0

    @staticmethod
    def calculate_citation_acceleration(yearly_citations: List[int]) -> int:
        """
        2. Citation Acceleration
        Acceleration = C_t - 2*C_{t-1} + C_{t-2}
        """
        if len(yearly_citations) < 3:
            return 0
        # yearly_citations should be sorted by year ascending
        c = yearly_citations
        return c[-1] - 2 * c[-2] + c[-3]

    @staticmethod
    def calculate_future_impact(
        early_citations: int, journal_score: float, h_index: int
    ) -> float:
        """
        3. Future Impact Score (ML-based Approximation)
        Approx: a1*early_cites + a2*journal + a3*h_index
        """
        # Normalized approximation (0-100)
        score = (early_citations * 2.5) + (journal_score * 15) + (h_index * 0.5)
        return round(min(score, 100.0), 1)

    @staticmethod
    def calculate_network_centrality(graph: nx.Graph, node_id: str) -> float:
        """
        4. Network Centrality (Betweenness)
        """
        if graph.number_of_nodes() < 2:
            return 1.0
        centrality = nx.betweenness_centrality(graph)
        return round(centrality.get(node_id, 0.0) * 100, 1)

    @staticmethod
    def calculate_semantic_novelty(
        embedding: np.ndarray, peer_embeddings: List[np.ndarray]
    ) -> float:
        """
        5. Semantic Novelty Score
        Novelty = 1 - max(cosine_similarity)
        """
        if not peer_embeddings:
            return 1.0

        def cosine_sim(a, b):
            norm_a = np.linalg.norm(a)
            norm_b = np.linalg.norm(b)
            if norm_a == 0 or norm_b == 0:
                return 0
            return np.dot(a, b) / (norm_a * norm_b)

        similarities = [cosine_sim(embedding, peer) for peer in peer_embeddings]
        max_sim = max(similarities)
        return round((1.0 - max_sim) * 100, 1)

    def calculate_interdisciplinary_index(self, topic_counts: Dict[str, int]) -> float:
        """
        6. Interdisciplinary Index (Entropy)
        H = - sum(p_i * log(p_i))
        Refined by checking cross-domain variety in ArXiv taxonomy.
        """
        if not topic_counts:
            return 0.0

        # Group topics by top-level domains in taxonomy
        domain_counts = {}
        for topic, count in topic_counts.items():
            found_domain = "Other"
            for domain, sub_domains in self.taxonomy.items():
                # Check if topic matches any sub-domain name or keywords
                # This is a fuzzy match for demo purposes
                flat_sub = str(sub_domains).lower()
                if topic.lower() in flat_sub:
                    found_domain = domain
                    break
            domain_counts[found_domain] = domain_counts.get(found_domain, 0) + count

        total = sum(domain_counts.values())
        entropy = 0.0
        for count in domain_counts.values():
            p = count / total
            if p > 0:
                entropy -= p * math.log(p)

        # Normalize: if topics span multiple major domains (Physics, CS, Bio), score is higher
        # Max entropy for 4 domains is ln(4) approx 1.38
        return round(min((entropy / 1.2) * 100, 100.0), 1)

    @staticmethod
    def calculate_policy_patent_score(policy_cites: int, patent_cites: int) -> int:
        """
        7. Policy & Patent Citation Score
        """
        return (policy_cites * 5) + (patent_cites * 10)

    @staticmethod
    def calculate_open_science_score(
        code: bool, data: bool, oa: bool, preprint: bool
    ) -> int:
        """
        8. Open Science Score
        (C + D + O + P) / 4
        """
        score = sum([code, data, oa, preprint])
        return int((score / 4.0) * 100)

    @staticmethod
    def calculate_collaboration_diversity(countries: List[str]) -> float:
        """
        9. Collaboration Diversity Index
        Entropy over countries.
        """
        if not countries:
            return 0.0
        from collections import Counter

        counts = Counter(countries)
        total = len(countries)
        entropy = 0.0
        for count in counts.values():
            p = count / total
            entropy -= p * math.log(p)
        return round(min((entropy / 2.3) * 100, 100.0), 1)

    @staticmethod
    def calculate_research_consistency(citations_per_year: List[int]) -> float:
        """
        10. Research Consistency Score
        Consistency = 1 / Variance
        """
        if len(citations_per_year) < 2:
            return 100.0
        variance = np.var(citations_per_year)
        if variance == 0:
            return 100.0
        # Normalize: inverse of variance, mapped to 0-100
        score = 100.0 / (1.0 + math.sqrt(variance))
        return round(score, 1)

    def calculate_metrics(self, data: Dict) -> Dict:
        """
        Backwards compatibility and batch calculation.
        """
        # Legacy mapping for researcher_worker.py
        creativity = self.calculate_semantic_novelty(
            data.get("embedding", np.random.rand(384)), data.get("peer_embeddings", [])
        )
        complexity = self.calculate_interdisciplinary_index(
            data.get("topic_counts", {"Physics": 1})
        )
        skill_score = self.calculate_open_science_score(
            data.get("code", False),
            data.get("data", False),
            data.get("oa", True),
            data.get("preprint", True),
        )

        return {
            "creativity": creativity,
            "complexity": complexity,
            "skill_set_score": skill_score,
            "disruption": self.calculate_disruption_score(
                data.get("n1", 0), data.get("n2", 0), data.get("n3", 0)
            ),
            "acceleration": self.calculate_citation_acceleration(
                data.get("yearly_cites", [0, 0, 0])
            ),
            "consistency": self.calculate_research_consistency(
                data.get("yearly_cites", [0, 0, 0])
            ),
            "diversity": self.calculate_collaboration_diversity(
                data.get("countries", [])
            ),
            "impact": data.get("cited_by_count", 0),  # Placeholder
        }

    def extract_top_skills(self, concepts: List[Dict]) -> List[str]:
        """
        Extracts specific, high-value technical skills/concepts from OpenAlex concepts.
        Filters out broad categories (Level 0/1) to find 'Core Proficiencies'.
        """
        skills = []
        for c in concepts:
            level = c.get("level")
            name = c.get("display_name")
            score = c.get("score", 0)

            # Level 2-4 usually represents specific techniques, materials, or methods
            if level is not None and level >= 2 and score > 0.4:
                skills.append(name)

        # Sort by relevance score if possible (though OpenAlex usually sorts by score)
        # Limit to top 6 specific skills
        return list(dict.fromkeys(skills))[:6]


import logging

logger = logging.getLogger(__name__)


async def compute_author_metrics(
    author_id: str,
    openalex_service: Optional[OpenAlexService] = None,
    scraping_service: Optional[ScrapingService] = None,
) -> dict:
    if not openalex_service:
        openalex_service = OpenAlexService()
    if not scraping_service:
        scraping_service = ScrapingService()

    clean_id = author_id.split("/")[-1]
    try:
        results = await openalex_service.fetch_author_works(
            author_id=clean_id, per_page=10
        )
    except Exception as e:
        logger.error(
            f"Failed to fetch works for clean_id {clean_id} (original: {author_id}) via OpenAlexService: {e}"
        )
        results = []

    if not results:
        raise ValueError("Not enough recent papers to analyze comprehensively.")

    context = build_author_metrics_context(results)
    return await analyze_author_metrics_context(context, scraping_service)


def build_author_metrics_context(works: List[Dict]) -> str:
    """Digest recent works into the title/concepts blob the LLM analysis reads.

    Kept as a standalone function so the Go gateway's ``/author_metrics`` handler
    (which does the OpenAlex fetch itself) and this in-process path produce the
    byte-identical context string — see decisions/0010.
    """
    abstracts = []
    for work in works:
        title = work.get("title", "")
        concepts = [
            c.get("display_name")
            for c in work.get("concepts", [])
            if c.get("display_name")
        ]
        abstracts.append(f"Title: {title}. Concepts: {', '.join(concepts)}")
    return "\n".join(abstracts)


async def analyze_author_metrics_context(
    context: str,
    scraping_service: Optional[ScrapingService] = None,
) -> dict:
    """The LLM half of ``/author_metrics``: parse a title/concepts digest into the
    scored bundle. This is the one genuinely model-bound step; the Go gateway
    calls it via ``POST /api/v1/internal/author_metrics_enrich`` (decisions/0010),
    and the teleport worker reaches it through ``compute_author_metrics``.
    """
    if not scraping_service:
        scraping_service = ScrapingService()

    schema = {
        "topic_toughness": "integer (0-100 indicating the complexity and niche of their topics)",
        "velocity": "integer (0-100 indicating how rapidly they are producing complex work)",
        "skills": "array of strings (3-5 high-level research skills implied by their work)",
        "tools": "array of strings (3-5 tools/frameworks/datasets implied by their work)",
        "analysis": "string (a short 2 sentence explanation of why they got this score)",
    }

    try:
        parsed = await scraping_service.parse_content_to_json(
            raw_content=context,
            response_schema=schema,
            instruction="Analyze the recent research works of the author based on the density of the terminology, concepts, and titles, and evaluate their topic toughness and research velocity, along with identifying key skills, tools, and a brief analytical explanation.",
        )

        # Calculate an overall composite score and guarantee integer types for Ktor
        tt = int(parsed.get("topic_toughness", 50))
        vel = int(parsed.get("velocity", 50))
        parsed["topic_toughness"] = tt
        parsed["velocity"] = vel
        parsed["overall_score"] = int((tt + vel) / 2)
        return parsed
    except Exception as e:
        # The LLM step is the only thing that can fail here. There is no local
        # fallback for this analysis, so surface it as a transient 503, not a
        # 500. See app/core/exceptions.py. (The Go gateway degrades this to an
        # empty bundle on its side — decisions/0010.)
        from app.core.exceptions import AIUnavailable

        logger.error(f"Error analyzing metrics with LLM: {e}")
        raise AIUnavailable(
            "Author metrics analysis is temporarily unavailable."
        ) from e
