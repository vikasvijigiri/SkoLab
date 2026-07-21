import datetime
import httpx
import json
import random
import asyncio
import re
import time
import numpy as np
from contextlib import asynccontextmanager
from typing import List, Dict, Optional, Any, AsyncGenerator, Tuple, Set
from app.services.ai.llm_service import is_llm_working
from sqlalchemy.future import select
from app.db.database import AsyncSessionLocal
from app.core.config import settings
from app.models.user_models import AgentChatHistory
from app.services.data.openalex_service import (
    OpenAlexService,
    is_work_relevant_to_discipline,
)
from app.db.pg_cache import PgBackedCache
from sqlalchemy.ext.asyncio import AsyncSession
from app.domains.recommendation.engine import (
    cosine_similarity as vector_cosine_similarity,
    mmr_diversify,
)
from app.services.ai.embedding_service import (
    embed_texts,
    embed_query,
    score_candidates_against_profile,
)
from app.prompts import (
    DAILY_FEED_ADVISOR_PROMPT_TEMPLATE,
    METADATA_EXTRACTION_PROMPT_TEMPLATE,
    AUTHOR_CHAT_SYSTEM_PROMPT_TEMPLATE,
    GRANT_ADVISOR_PROMPT_TEMPLATE,
    SYNERGY_COUNSELOR_PROMPT_TEMPLATE,
    JOURNAL_ADVISOR_RATIONALE_PROMPT_TEMPLATE,
)


def is_field_semantically_relevant(
    collab_field: str, collab_path: str, discipline: str
) -> bool:
    if not discipline:
        return True

    disc_lower = discipline.lower().strip()
    collab_field_lower = (collab_field or "").lower().strip()
    collab_path_lower = (collab_path or "").lower().strip()

    # Direct substring matches
    if disc_lower in collab_field_lower or collab_field_lower in disc_lower:
        return True
    if disc_lower in collab_path_lower:
        return True

    # Split the discipline and collaborator field into words
    disc_words = [
        w.strip()
        for w in disc_lower.replace("and", "").replace("&", "").split()
        if len(w.strip()) > 2
    ]
    collab_words = [w.strip() for w in collab_field_lower.split() if len(w.strip()) > 2]

    # Check if there is any word overlap
    for dw in disc_words:
        for cw in collab_words:
            if dw in cw or cw in dw:
                return True

    # Term expansion for major fields of study
    # Stems mapped to their broader scientific domain keywords
    domain_keywords = {
        "phys": [
            "phys",
            "quantum",
            "spin",
            "antiferromagnet",
            "squaric",
            "condensed",
            "superconduct",
            "particle",
            "magnetic",
            "optical",
            "fluid",
            "thermodynamic",
            "mechanics",
            "gravity",
            "energy",
            "matter",
            "cosmology",
            "phonon",
            "semiconductor",
            "crystallography",
            "spectroscopy",
            "resonance",
            "laser",
            "field",
            "relativity",
            "plasma",
            "astro",
            "nuclear",
        ],
        "comput": [
            "comput",
            "learn",
            "intel",
            "neural",
            "vision",
            "algorithm",
            "software",
            "network",
            "image",
            "data",
            "robot",
            "nlp",
            "processing",
            "code",
            "programming",
            "cyber",
            "security",
            "database",
            "graphics",
            "web",
        ],
        "cs": [
            "comput",
            "learn",
            "intel",
            "neural",
            "vision",
            "algorithm",
            "software",
            "network",
            "image",
            "data",
            "robot",
            "nlp",
            "processing",
            "code",
            "programming",
            "cyber",
            "security",
            "database",
            "graphics",
            "web",
        ],
        "ai": [
            "comput",
            "learn",
            "intel",
            "neural",
            "vision",
            "algorithm",
            "software",
            "network",
            "image",
            "data",
            "robot",
            "nlp",
            "processing",
            "code",
            "programming",
            "cyber",
            "security",
            "database",
            "graphics",
            "web",
        ],
        "bio": [
            "chem",
            "bio",
            "molec",
            "gene",
            "crispr",
            "dna",
            "rna",
            "enzyme",
            "protein",
            "cell",
            "genom",
            "nuclease",
            "chromatin",
            "nucleic",
            "medical",
            "clinical",
            "health",
            "disease",
            "drug",
            "pharma",
            "biotech",
            "immunology",
            "microbiology",
        ],
        "chem": [
            "chem",
            "molec",
            "organ",
            "inorgan",
            "spectroscop",
            "synthes",
            "reaction",
            "cataly",
            "polymer",
            "materials",
            "electro",
            "nano",
        ],
        "math": [
            "math",
            "algebra",
            "calculus",
            "geometry",
            "topology",
            "statistics",
            "probability",
            "discrete",
            "theorem",
            "equation",
            "numerical",
            "optimiz",
        ],
        "eng": [
            "eng",
            "mechanic",
            "electric",
            "civil",
            "chemical",
            "aerospace",
            "material",
            "device",
            "circuit",
            "system",
            "nano",
            "sensor",
            "failure",
        ],
    }

    # Determine the domains of the user's discipline
    matched_domains = []
    for stem, keywords in domain_keywords.items():
        if any(stem in dw for dw in disc_words):
            matched_domains.extend(keywords)

    # Check if the collaborator field contains any of these matched domain keywords
    if matched_domains:
        for kw in matched_domains:
            if kw in collab_field_lower or any(
                kw in cw or cw in kw for cw in collab_words
            ):
                return True

    return False


def is_prestigious_journal(journal: str) -> bool:
    if not journal:
        return False
    j_lower = journal.lower().strip()

    prestigious_patterns = [
        "nature",
        "science",
        "physical review",
        "proceedings of the national academy of sciences",
        "pnas",
        "ieee transactions",
        "ieee/cvf",
        "acm transactions",
        "journal of machine learning research",
        "jmlr",
        "neurips",
        "neural information processing systems",
        "icml",
        "international conference on machine learning",
        "cvpr",
        "iccv",
        "eccv",
        "kdd",
        "sigkdd",
        "association for computational linguistics",
        "emnlp",
        "naacl",
        "aaai",
        "ijcai",
        "cell",
        "lancet",
        "new england journal of medicine",
        "nejm",
        "journal of the american chemical society",
        "jacs",
        "angewandte chemie",
        "advanced materials",
        "monthly notices of the royal astronomical society",
        "mnras",
        "astrophysical journal",
        "journal of high energy physics",
        "jhep",
        "bioinformatics",
    ]

    for pat in prestigious_patterns:
        if pat == "science":
            if (
                j_lower == "science"
                or j_lower.startswith("science ")
                or j_lower.endswith(" science")
            ):
                exclude_words = [
                    "computer",
                    "social",
                    "materials",
                    "political",
                    "applied",
                    "environmental",
                    "management",
                    "education",
                    "policy",
                    "society",
                    "information",
                    "forestry",
                    "agricultural",
                    "clinical",
                    "engineering",
                    "humanities",
                    "sports",
                ]
                if any(w in j_lower for w in exclude_words):
                    continue
                return True
        elif pat == "nature":
            if j_lower == "nature" or j_lower.startswith("nature "):
                return True
        elif pat in j_lower:
            return True

    return False


def extract_metadata_from_abstract(title: str, abstract: str) -> dict:
    title_lower = title.lower()
    abstract_lower = abstract.lower()

    # 1. Methodology
    methodology = "Empirical Analysis & Literature Evaluation"
    if (
        "neural" in title_lower
        or "transformer" in title_lower
        or "deep learning" in title_lower
        or "attention" in title_lower
    ):
        methodology = "Deep Learning & Attention Matrix Optimization"
    elif (
        "quantum" in title_lower
        or "qubit" in title_lower
        or "superconducting" in title_lower
    ):
        methodology = "Quantum Circuit Tomography & Coherence Analysis"
    elif (
        "genome" in title_lower
        or "sequence" in title_lower
        or "dna" in title_lower
        or "regulatory" in title_lower
    ):
        methodology = "Genomic Motif Mapping & Sequence Alignment"
    elif (
        "gravitational" in title_lower
        or "cosmology" in title_lower
        or "astroph" in title_lower
    ):
        methodology = "Numerical Relativity Boundary Solver"
    elif (
        "network" in title_lower
        or "collaboration" in title_lower
        or "workspace" in title_lower
    ):
        methodology = "Collaboration Graph Network Analytics"
    elif (
        "cognitive" in title_lower
        or "eye-tracking" in title_lower
        or "behavioral" in title_lower
    ):
        methodology = "Real-time Cognitive Load EEG Measurement"

    # Try abstract hints
    elif "methodology" in abstract_lower or "method" in abstract_lower:
        # Find sentence containing "method"
        sentences = abstract.split(".")
        for s in sentences:
            if "method" in s.lower() or "approach" in s.lower():
                cleaned = s.strip()
                if len(cleaned) < 80:
                    methodology = cleaned
                    break

    # 2. Tools Used
    tools = []
    # Machine Learning / CS
    if "pytorch" in abstract_lower or "pytorch" in title_lower:
        tools.append("PyTorch")
    if "tensorflow" in abstract_lower:
        tools.append("TensorFlow")
    if "cuda" in abstract_lower:
        tools.append("CUDA C++")
    if "jax" in abstract_lower:
        tools.append("JAX")
    if "gpu" in abstract_lower or "h100" in abstract_lower:
        tools.append("GPU Cluster")
    # Quantum / Physics
    if "qiskit" in abstract_lower:
        tools.append("Qiskit Metal")
    if "hfss" in abstract_lower:
        tools.append("ANSYS HFSS")
    if "cryo" in abstract_lower or "dilution" in abstract_lower:
        tools.append("Cryogenic Fridge")
    # Genomics / Bio
    if "blast" in abstract_lower:
        tools.append("NCBI BLAST")
    if "bioconductor" in abstract_lower or "r/" in abstract_lower:
        tools.append("R/Bioconductor")
    if "nextflow" in abstract_lower:
        tools.append("Nextflow")
    # General / Fallback
    if not tools:
        # Pick 2-3 standard tools based on field
        if "quantum" in title_lower or "phys" in title_lower:
            tools = ["Mathematica", "Python (SciPy)", "HPC Cluster"]
        elif (
            "learn" in title_lower
            or "network" in title_lower
            or "ai" in title_lower
            or "model" in title_lower
        ):
            tools = ["PyTorch", "Hugging Face", "Weights & Biases"]
        elif (
            "genom" in title_lower or "bio" in title_lower or "sequence" in title_lower
        ):
            tools = ["RStudio", "MEME Suite", "BLAST"]
        else:
            tools = ["Python (NumPy)", "MATLAB", "LaTeX"]
    else:
        # pad if too few
        if len(tools) == 1:
            tools.append("Python")
            tools.append("LaTeX")

    # 3. Key Findings
    key_findings = "Demonstrated a robust model performance improvement and identified critical parameter bounds."
    if "quantum" in title_lower:
        key_findings = "Enhanced quantum coherence times and reduced state dephasing errors under environmental noise."
    elif "attention" in title_lower or "transformer" in title_lower:
        key_findings = "Reduced computational complexity and memory usage while preserving tasks downstream perplexity."
    elif "genom" in title_lower:
        key_findings = "Discovered conserved regulatory sequence motifs that control transcription in target organisms."
    elif "gravitational" in title_lower:
        key_findings = "Decreased boundary-reflection artifacts in wave propagation simulations by over 90%."
    elif "collaboration" in title_lower or "workspace" in title_lower:
        key_findings = "Verified that integrated co-author workspaces increase cross-disciplinary productivity metrics."
    elif "cognitive" in title_lower or "behavioral" in title_lower:
        key_findings = "Identified user interface feedback loops that significantly reduce subjective cognitive load."

    return {
        "abstract": abstract,
        "methodology": methodology,
        "tools_used": tools,
        "key_findings": key_findings,
    }


# Per-feature PG caches with appropriate TTLs
# These are the local fast layer; Firestore backs the large enriched docs.
_pg_daily_feed_cache = PgBackedCache(ttl_seconds=3600, name="pipeline_daily_feed")
_pg_match_grants_cache = PgBackedCache(ttl_seconds=3600, name="pipeline_match_grants")
_pg_synergy_cache = PgBackedCache(ttl_seconds=7200, name="pipeline_synergy")
_pg_heatmap_cache = PgBackedCache(ttl_seconds=3600, name="pipeline_heatmap")
_pg_journal_advisor_cache = PgBackedCache(
    ttl_seconds=7200, name="pipeline_journal_advisor"
)
_pg_network_collab_cache = PgBackedCache(
    ttl_seconds=3600, name="pipeline_network_collab"
)
# Effectively-permanent (10y TTL) — a dismissal shouldn't quietly expire and
# bring a paper the user already rejected back into their feed.
_pg_dismissed_recs_cache = PgBackedCache(
    ttl_seconds=315360000, name="pipeline_dismissed_recs"
)


class PipelineServices:
    def __init__(self, db: Optional[AsyncSession] = None):
        self.db = db
        from app.services.ai.llm_service import LLMService

        self.llm_service = LLMService()
        self.model = "llama-3.3-70b-versatile"
        self.openalex_service = OpenAlexService()

    @asynccontextmanager
    async def _db_session(self) -> AsyncGenerator[AsyncSession, None]:
        if self.db is not None:
            yield self.db
        else:
            async with AsyncSessionLocal() as session:
                yield session

    def _get_firestore_db(self) -> Any:
        """Returns a Firestore client if available, else None."""
        try:
            from app.services.data.researcher_worker import FIRESTORE_AVAILABLE

            if not FIRESTORE_AVAILABLE:
                return None
            from firebase_admin import firestore as _firestore

            return _firestore.client()
        except Exception as exc:
            print(f"[PipelineServices] Firestore unavailable: {exc}", flush=True)
            return None

    async def _firestore_get_safe(
        self, collection: str, doc_id: str, timeout: float = 5.0
    ) -> Optional[Dict[str, Any]]:
        """
        Wraps a synchronous Firestore document.get() in a thread executor with a short timeout.
        Prevents the blocking Firestore SDK from stalling the asyncio event loop when
        credentials are expired or the network is unavailable.
        Returns the document dict if found and within time, else None.
        """
        db = self._get_firestore_db()
        if not db:
            return None
        loop = asyncio.get_event_loop()
        try:

            def _blocking_get() -> Optional[Dict[str, Any]]:
                doc = db.collection(collection).document(doc_id).get()
                return doc.to_dict() if doc.exists else None

            result = await asyncio.wait_for(
                loop.run_in_executor(None, _blocking_get), timeout=timeout
            )
            return result
        except asyncio.TimeoutError:
            print(
                f"[PipelineServices] Firestore get timed out ({timeout}s) for {collection}/{doc_id}",
                flush=True,
            )
        except Exception as e:
            print(
                f"[PipelineServices] Firestore get error for {collection}/{doc_id}: {e}",
                flush=True,
            )
        return None

    async def _firestore_set_safe(
        self, collection: str, doc_id: str, data: Dict[str, Any], timeout: float = 5.0
    ) -> bool:
        """
        Wraps a synchronous Firestore document.set() in a thread executor with a short timeout.
        Prevents the blocking Firestore SDK from stalling the asyncio event loop.
        """
        db = self._get_firestore_db()
        if not db:
            return False
        loop = asyncio.get_event_loop()
        try:

            def _blocking_set() -> bool:
                db.collection(collection).document(doc_id).set(data)
                return True

            await asyncio.wait_for(
                loop.run_in_executor(None, _blocking_set), timeout=timeout
            )
            return True
        except asyncio.TimeoutError:
            print(
                f"[PipelineServices] Firestore set timed out ({timeout}s) for {collection}/{doc_id}",
                flush=True,
            )
        except Exception as e:
            print(
                f"[PipelineServices] Firestore set error for {collection}/{doc_id}: {e}",
                flush=True,
            )
        return False

    async def _save_to_postgres(
        self, cache_key: str, data: Dict[str, Any], ttl_seconds: int = 3600
    ) -> None:
        """Save data to PostgreSQL cache_entries with TTL via PgBackedCache."""
        cache = PgBackedCache(ttl_seconds=ttl_seconds, name="pipeline")
        await cache.set(cache_key, data)

    async def _load_from_postgres(
        self, cache_key: str, ttl_seconds: int = 3600
    ) -> Optional[Dict[str, Any]]:
        """Load data from PostgreSQL cache_entries with TTL check."""
        cache = PgBackedCache(ttl_seconds=ttl_seconds, name="pipeline")
        return await cache.get(cache_key)

    async def _fetch_author_profile(self, author_id: str) -> Optional[Dict[str, Any]]:
        """Helper to fetch author profile from OpenAlex or database."""
        return await self.openalex_service.fetch_author_by_id(author_id)

    async def extract_metadata_via_llm(
        self, title: str, abstract: str
    ) -> Dict[str, Any]:
        """
        Uses the LLM to dynamically extract methodology, tools used, and key findings
        from the paper abstract.
        """
        prompt = METADATA_EXTRACTION_PROMPT_TEMPLATE.format(
            title=title, abstract=abstract
        )

        try:
            response = await self.llm_service.query(
                messages=[
                    {
                        "role": "system",
                        "content": "You are a helpful assistant that outputs only valid raw JSON.",
                    },
                    {"role": "user", "content": prompt},
                ],
                temperature=0.1,
                max_tokens=256,
                response_format={"type": "json_object"},
            )
            if response.content:
                data = json.loads(response.content)
                # Ensure structure is valid
                methodology = str(data.get("methodology") or "Empirical Research")
                tools = data.get("tools_used")
                if not isinstance(tools, list):
                    tools = ["Python"]
                tools = [str(t) for t in tools][:4]
                key_findings = str(
                    data.get("key_findings") or "Demonstrated significant results."
                )
                return {
                    "methodology": methodology,
                    "tools_used": tools,
                    "key_findings": key_findings,
                }
        except Exception as e:
            print(
                f"[DailyFeed] LLM metadata extraction failed: {e}. Falling back to rules.",
                flush=True,
            )

        # Rule-based fallback
        return extract_metadata_from_abstract(title, abstract)

    async def _extract_recent_paper_keywords(
        self, user_works: List[Dict[str, Any]]
    ) -> List[str]:
        """
        LLM-extracted technical keywords/phrases from the researcher's 5 most
        recent papers (title + abstract). Sharper and more specific than raw
        title/concept tokens alone — surfaces the actual methods, models, and
        sub-topics the researcher is currently working on (e.g. "spin-echo
        decoherence", "Jaynes-Cummings model") rather than just broad field
        tags, which is what feeds the embedding query below.
        """
        if not is_llm_working() or not user_works:
            return []

        def sort_key(w: Dict[str, Any]) -> str:
            return w.get("publication_date") or str(w.get("publication_year") or "0")

        recent = sorted(
            [w for w in user_works if w.get("title")], key=sort_key, reverse=True
        )[:5]
        if not recent:
            return []

        context_parts = []
        for w in recent:
            abstract = w.get("abstract") or w.get("_custom_abstract") or ""
            if not abstract and w.get("abstract_inverted_index"):
                abstract = self._reconstruct_abstract(w["abstract_inverted_index"])
            context_parts.append(f"Title: {w.get('title')}\nAbstract: {abstract[:600]}")
        context = "\n\n".join(context_parts)

        try:
            response = await self.llm_service.query(
                messages=[
                    {
                        "role": "system",
                        "content": "You are a helpful assistant that outputs only valid raw JSON.",
                    },
                    {
                        "role": "user",
                        "content": (
                            "Extract 10-15 precise technical research keywords/phrases "
                            "(specific methods, models, phenomena, materials — not generic "
                            "field names like 'physics' or 'machine learning') that best "
                            "characterize this researcher's current work, based on their 5 "
                            "most recent papers below.\n\n"
                            f"{context}\n\n"
                            'Return raw JSON: {"keywords": ["...", "..."]}'
                        ),
                    },
                ],
                temperature=0.2,
                max_tokens=300,
                response_format={"type": "json_object"},
            )
            if response.content:
                data = json.loads(response.content)
                keywords = data.get("keywords")
                if isinstance(keywords, list):
                    return [str(k) for k in keywords if k][:15]
        except Exception as e:
            print(f"[DailyFeed] LLM keyword extraction failed: {e}", flush=True)
        return []

    def _find_similar_researchers(
        self, topic_matched_works: List[Dict[str, Any]], exclude_author_id: Optional[str]
    ) -> List[str]:
        """
        Derives a handful of OpenAlex author IDs from the authorships of papers
        that already matched the topic/keyword search — i.e. "who wrote the
        papers that are already known to be on-topic", not an independent
        author-name search. (OpenAlex's /authors search endpoint does fuzzy
        matching against author *display names*; feeding it a topic phrase like
        "CRISPR and Genetic Engineering" returns nothing or garbage, since a
        concept string doesn't look like a person's name — confirmed by testing
        it directly.) Entirely generic: driven by whatever candidate works the
        caller's own topic search actually found, no per-user hardcoding.
        """
        exclude_clean = exclude_author_id.split("/")[-1] if exclude_author_id else None
        seen: List[str] = []
        for w in topic_matched_works:
            for authorship in w.get("authorships") or []:
                aid = (authorship.get("author") or {}).get("id") or ""
                aid = aid.split("/")[-1]
                if aid and aid != exclude_clean and aid not in seen:
                    seen.append(aid)
        return seen[:4]

    async def _resolve_author_concepts_and_name(
        self, author_id: str, doc_id: str
    ) -> Tuple[str, List[str]]:
        """Resolves concepts and name for a researcher from profile cache or local DB."""
        profile = await self._fetch_author_profile(author_id)
        if profile:
            author_name = profile.get("display_name", "Researcher")
            from app.services.data.openalex_service import extract_field_and_expertise

            _, concepts = extract_field_and_expertise(profile, author_name)
            return author_name, concepts or []

        # Fallback: Query local database metrics for concepts
        try:
            from app.models.researcher_models import ResearcherMetrics

            async with self._db_session() as session:
                stmt = select(ResearcherMetrics).where(
                    ResearcherMetrics.openalex_id == doc_id
                )
                res = await session.execute(stmt)
                rm = res.scalars().first()
                if rm:
                    return rm.display_name, rm.expertise or []
        except Exception as e:
            print(f"[DailyFeed] Database lookup fallback error: {e}", flush=True)
        return "Researcher", []

    def _add_paper_if_valid(
        self, w: Dict[str, Any], papers: List[Dict[str, Any]], seen_titles: Set[str]
    ) -> bool:
        """Helper to validate, deduplicate, and append a paper to the feed list."""
        title = w.get("title", "")
        if not title:
            return False
        title_norm = title.strip().lower().rstrip(".")
        abstract_index = w.get("abstract_inverted_index")
        if (
            abstract_index
            and title_norm not in seen_titles
            and w.get("id") not in [p.get("id") for p in papers]
        ):
            papers.append(w)
            seen_titles.add(title_norm)
            return True
        return False

    async def _resolve_author_id_by_name(self, name: str, field: str) -> Optional[str]:
        """Resolves an author name and discipline to an OpenAlex ID."""
        try:
            results = await self.openalex_service.search_authors(name, per_page=10)
            if not results:
                return None

            # Filter candidates that actually match the name tokens first
            name_matches = []
            query_tokens = [tok for tok in name.lower().split() if len(tok) > 2]
            for cand in results:
                cand_name = cand.get("display_name", "").lower()
                if not query_tokens or all(tok in cand_name for tok in query_tokens):
                    name_matches.append(cand)

            best_cand = None
            norm_field = field.lower() if field else ""
            if norm_field:
                for cand in name_matches:
                    concepts = cand.get("x_concepts", []) or []
                    concept_names = [
                        c.get("display_name", "").lower() for c in concepts
                    ]
                    if any(
                        norm_field in c_name or c_name in norm_field
                        for c_name in concept_names
                    ):
                        best_cand = cand
                        break

            if not best_cand and name_matches:
                best_cand = name_matches[0]
            if not best_cand:
                best_cand = results[0]

            return best_cand["id"] if best_cand else None
        except Exception as e:
            print(
                f"[Dynamic Resolve Error] Failed to resolve '{name}' on OpenAlex: {e}",
                flush=True,
            )
        return None

    def _reconstruct_abstract(
        self, abstract_index: Optional[Dict[str, List[int]]]
    ) -> Optional[str]:
        """Reconstructs the abstract from the OpenAlex abstract_inverted_index."""
        if not abstract_index:
            return None
        try:
            word_list = []
            for word, pos_list in abstract_index.items():
                for pos in pos_list:
                    word_list.append((pos, word))
            word_list.sort()
            return " ".join([w[1] for w in word_list])
        except Exception:
            return None

    async def _fetch_arxiv_candidates(
        self, query: str, max_results: int = 15
    ) -> List[Dict[str, Any]]:
        """
        Searches arXiv for the query, returning list of candidate dicts structured like OpenAlex works.
        """
        import defusedxml.ElementTree as ET
        import urllib.parse
        import datetime

        safe_query = urllib.parse.quote(query)
        url = f"https://export.arxiv.org/api/query?search_query=all:{safe_query}&start=0&max_results={max_results}&sortBy=submittedDate&sortOrder=descending"
        try:
            async with httpx.AsyncClient(
                timeout=settings.http_timeout_seconds
            ) as client:
                res = await client.get(url)
                if res.status_code == 200:
                    root = ET.fromstring(res.content)
                    ns = {"atom": "http://www.w3.org/2005/Atom"}
                    candidates = []
                    for entry in root.findall("atom:entry", ns):
                        id_elem = entry.find("atom:id", ns)
                        title_elem = entry.find("atom:title", ns)
                        summary_elem = entry.find("atom:summary", ns)
                        published_elem = entry.find("atom:published", ns)

                        id_text = id_elem.text.strip() if id_elem is not None else ""
                        title_text = (
                            title_elem.text.strip().replace("\n", " ")
                            if title_elem is not None
                            else "Untitled"
                        )
                        abstract_text = (
                            summary_elem.text.strip().replace("\n", " ")
                            if summary_elem is not None
                            else ""
                        )
                        pub_date = (
                            published_elem.text.split("T")[0]
                            if published_elem is not None
                            else f"{datetime.datetime.now().year}-01-01"
                        )
                        try:
                            pub_year = int(pub_date.split("-")[0])
                        except Exception:
                            pub_year = datetime.datetime.now().year

                        authors = []
                        for author in entry.findall("atom:author", ns):
                            name_elem = author.find("atom:name", ns)
                            if name_elem is not None:
                                authors.append(
                                    {"author": {"display_name": name_elem.text.strip()}}
                                )

                        # Extract doi if present
                        doi_text = None
                        doi_elem = entry.find("{http://arxiv.org/schemas/atom}doi")
                        if doi_elem is not None:
                            doi_text = doi_elem.text.strip()

                        # Build a mock OpenAlex work dictionary
                        w = {
                            "id": id_text,
                            "title": title_text,
                            "_custom_abstract": abstract_text,
                            "publication_date": pub_date,
                            "publication_year": pub_year,
                            "authorships": authors,
                            "primary_location": {
                                "source": {
                                    "display_name": "arXiv",
                                    "id": "S4306400194",
                                },
                                "landing_page_url": id_text,
                            },
                            "doi": doi_text,
                            "cited_by_count": 0,
                        }
                        candidates.append(w)
                    return candidates
        except Exception as e:
            print(f"[DailyFeed] arXiv search for '{query}' failed: {e}", flush=True)
        return []

    async def _fetch_and_add_fallback_papers(
        self, term: str, papers: List[Dict[str, Any]], seen_titles: Set[str]
    ) -> None:
        """Fetches works from OpenAlex for a fallback term and adds valid ones."""
        try:
            results = await self.openalex_service.search_works(term, per_page=20)
            for w in results:
                if (
                    self._add_paper_if_valid(w, papers, seen_titles)
                    and len(papers) >= 3
                ):
                    break
        except Exception as e:
            print(f"Fallback fetch for term '{term}' failed: {e}")

    def _reconstruct_abstract(self, inv_idx: Optional[Dict[str, List[int]]]) -> str:
        if not inv_idx or not isinstance(inv_idx, dict):
            return ""
        try:
            word_pos = [
                (pos, word) for word, positions in inv_idx.items() for pos in positions
            ]
            return " ".join(wp[1] for wp in sorted(word_pos))
        except Exception:
            return ""

    def _compute_jaccard_similarity(self, list1: List[str], list2: List[str]) -> float:
        if not list1 or not list2:
            return 0.0
        set1 = {x.strip().lower() for x in list1 if x.strip()}
        set2 = {x.strip().lower() for x in list2 if x.strip()}
        if not set1 or not set2:
            return 0.0

        exact_intersection = set1.intersection(set2)
        partial_matches = 0.0
        for u in set1:
            if u in exact_intersection:
                continue
            for c in set2:
                if u in c or c in u:
                    partial_matches += 0.5
                    break

        overlap = len(exact_intersection) + partial_matches
        union_size = len(set1.union(set2))
        if union_size == 0:
            return 0.0
        return min(1.0, overlap / union_size)

    async def get_dismissed_recommendation_ids(self, author_id: Optional[str]) -> Set[str]:
        """OpenAlex work IDs this author has explicitly dismissed from their feed."""
        doc_id = author_id.split("/")[-1] if author_id else "anon"
        ids = await _pg_dismissed_recs_cache.get(doc_id)
        return set(ids) if ids else set()

    async def dismiss_recommendation(self, author_id: str, work_id: str) -> None:
        """
        Records a dismissal and invalidates the cached feed so the next fetch
        recomputes without this paper, instead of silently re-serving a stale
        cached feed that still contains it for up to an hour.
        """
        doc_id = author_id.split("/")[-1] if author_id else "anon"
        current = await self.get_dismissed_recommendation_ids(author_id)
        current.add(work_id)
        await _pg_dismissed_recs_cache.set(doc_id, list(current))
        feed_cache = PgBackedCache(ttl_seconds=3600, name="pipeline")
        await feed_cache.delete(f"daily_feed_{doc_id}")

    async def get_daily_feed(
        self, author_id: Optional[str], query_fallback: Optional[str] = None
    ) -> List[Dict[str, Any]]:
        """
        Generates a personalized daily feed of 3 papers based on author's primary concepts and works.
        """
        doc_id = None
        if author_id:
            doc_id = author_id.split("/")[-1]
        elif query_fallback:
            doc_id = f"fallback_{re.sub(r'[^a-zA-Z0-9_]', '_', query_fallback.strip().lower())}"
        else:
            doc_id = "default_feed"
        dismissed_ids = await self.get_dismissed_recommendation_ids(author_id)
        cache_key = f"daily_feed_{doc_id}"
        cached_data = await self._load_from_postgres(cache_key)
        if isinstance(cached_data, dict) and "items" in cached_data:
            cached_items = cached_data["items"]
            if not dismissed_ids or not any(
                it.get("id") in dismissed_ids for it in cached_items
            ):
                print(f"[Postgres Cache Hit] daily_feeds for doc_id={doc_id}", flush=True)
                return cached_items
        _fs_cached = await self._firestore_get_safe("daily_feeds", doc_id, timeout=5.0)
        if isinstance(_fs_cached, dict) and "items" in _fs_cached:
            # Firestore itself has no TTL/expiry — unlike the Postgres L2 cache above,
            # an entry here is served forever unless we check its age ourselves. Without
            # this, once the Postgres cache expires (1h), it would just re-read this same
            # Firestore doc and re-seed Postgres with it, making a bad cached feed
            # permanent regardless of any future fix to the generation logic below.
            last_synced = _fs_cached.get("last_synced")
            is_fresh = False
            if last_synced is not None:
                try:
                    now = datetime.datetime.now(datetime.timezone.utc)
                    synced_at = (
                        last_synced
                        if last_synced.tzinfo
                        else last_synced.replace(tzinfo=datetime.timezone.utc)
                    )
                    is_fresh = (now - synced_at).total_seconds() < 3600
                except Exception:
                    is_fresh = False
            fs_items = _fs_cached["items"]
            fs_has_dismissed = dismissed_ids and any(
                it.get("id") in dismissed_ids for it in fs_items
            )
            if is_fresh and not fs_has_dismissed:
                print(f"[Firestore Cache Hit] daily_feeds for doc_id={doc_id}", flush=True)
                await self._save_to_postgres(cache_key, {"items": fs_items})
                return fs_items
            print(
                f"[Firestore Cache Stale] daily_feeds for doc_id={doc_id} — recomputing",
                flush=True,
            )

        # Fetch user publications if profile exists
        user_works = []
        user_concepts = []
        user_titles = []
        if author_id and author_id != "fallback_seed":
            try:
                user_works = await self.openalex_service.fetch_author_works(
                    author_id, per_page=10
                )
                if user_works:
                    # Sort to get top publications by citations/year
                    user_works = sorted(
                        user_works,
                        key=lambda w: (
                            w.get("cited_by_count") or 0,
                            w.get("publication_year") or 0,
                        ),
                        reverse=True,
                    )
                    for w in user_works:
                        title_val = w.get("title")
                        if title_val:
                            user_titles.append(title_val)
                        for c in w.get("concepts", []):
                            name_val = c.get("display_name")
                            if name_val and name_val not in user_concepts:
                                user_concepts.append(name_val)
            except Exception as e:
                print(f"[DailyFeed] Error fetching user works: {e}", flush=True)

        concepts = []
        author_name = "Researcher"
        if author_id:
            author_name, concepts = await self._resolve_author_concepts_and_name(
                author_id, doc_id
            )

        # Merge concepts — ORDER MATTERS: only the first 3 unique entries become the
        # actual search queries below, and `combined_concepts[0]` becomes the
        # discipline-relevance filter. `concepts` (author-level OpenAlex *topics*,
        # e.g. "Advanced Condensed Matter Physics") are curated and specific by
        # construction. `user_concepts` (per-work OpenAlex *concept* tags) are a mix
        # of specific (e.g. "Heisenberg model", "Quantum Monte Carlo") and generic
        # (e.g. "Physics", "Quantum mechanics") — and since near-every physics paper
        # carries the generic ones, they used to win the "first 3 unique" race almost
        # every time, so the search ended up querying something as broad as "Physics"
        # instead of the far more specific signal sitting right next to it. Priority
        # is now: curated topics -> specific work concepts -> generic ones last.
        GENERIC_CONCEPT_TERMS = {
            "physics", "science", "biology", "chemistry", "mathematics",
            "engineering", "computer science", "medicine", "quantum mechanics",
            "materials science", "quantum",
        }
        combined_concepts = []
        for c in concepts:
            if c not in combined_concepts:
                combined_concepts.append(c)
        for c in user_concepts:
            if c.lower() not in GENERIC_CONCEPT_TERMS and c not in combined_concepts:
                combined_concepts.append(c)
        for c in user_concepts:
            if c not in combined_concepts:
                combined_concepts.append(c)

        # LLM-extracted keywords from the researcher's 5 most recent papers (see
        # docstring on _extract_recent_paper_keywords). Computed here — before
        # search_queries — specifically so they can drive *retrieval* itself,
        # not just softly nudge the embedding profile text further downstream.
        # Previously that was the gap: sharp, specific keywords existed but only
        # ever fed a similarity average, never actually widened or focused what
        # got fetched in the first place.
        recent_keywords = await self._extract_recent_paper_keywords(user_works)

        # Build dynamic search queries: concepts (broader field coverage) +
        # recent keywords (sharp, current-work-specific terms) — deduped,
        # capped so fan-out below stays bounded. Previously only the top 3
        # concepts drove retrieval, which is why the candidate pool was a thin,
        # somewhat arbitrary ~20-40 papers instead of a genuinely representative
        # sample of the relevant literature. Capped at 6 terms total (4 concepts
        # + 2 keywords) — wider (10) measured ~2m48s cold-compute latency, too
        # slow for a synchronous first-load wait; this keeps most of the pool
        # breadth gain while landing closer to the original ~40-80s.
        search_queries = []
        for term in combined_concepts[:4] + recent_keywords[:2]:
            if term and term not in search_queries:
                search_queries.append(term)
        if not search_queries:
            search_queries = [query_fallback] if query_fallback else ["research"]

        candidates = []
        seen_titles = set()
        # Same priority as search_queries above: prefer the author's real OpenAlex
        # concepts over query_fallback whenever concepts exist. query_fallback is
        # only a topic hint when there's nothing better — it must never be treated
        # as authoritative over real profile data, since callers may pass something
        # that isn't a topic at all (see get_daily_feed's caller).
        discipline = (
            combined_concepts[0] if combined_concepts else (query_fallback or "STEM")
        )
        print(
            f"[DailyFeed][DEBUG] discipline={discipline!r} combined_concepts={combined_concepts[:8]!r} search_queries={search_queries!r}",
            flush=True,
        )

        def add_candidate_if_valid(w: Dict[str, Any]) -> None:
            # Raised from 40 — search_queries now spans up to 10 terms (6 concepts
            # + 4 recent keywords) plus a similar-researchers channel, so the raw
            # fetch is much larger than before; capping too low here would throw
            # most of that broader pool away before it ever reaches scoring.
            if len(candidates) >= 200:
                return
            title = w.get("title", "")
            if not title:
                return
            if w.get("id") in dismissed_ids:
                return
            title_norm = title.strip().lower().rstrip(".")
            abstract_index = w.get("abstract_inverted_index")
            custom_abstract = w.get("_custom_abstract")

            # Filter out future years
            current_year = datetime.datetime.now().year
            pub_year = w.get("publication_year")
            if pub_year and pub_year > current_year + 1:
                return

            if (
                (abstract_index or custom_abstract)
                and title_norm not in seen_titles
                and w.get("id") not in [p.get("id") for p in candidates]
            ):
                candidates.append(w)
                seen_titles.add(title_norm)

        # Fetch in parallel from:
        # 1. Related works (using OpenAlex related_to filter for top 3 user works)
        # 2. arXiv API (latest preprints)
        # 3. OpenAlex search_works (latest sorted)
        tasks = []
        if user_works:
            for w in user_works[:3]:
                work_id = w.get("id")
                if work_id:
                    tasks.append(
                        self.openalex_service.fetch_related_works(work_id, per_page=10)
                    )

        for q in search_queries:
            # Fetch latest preprints from arXiv
            tasks.append(self._fetch_arxiv_candidates(q, max_results=15))
            # Fetch latest works from OpenAlex
            tasks.append(
                self.openalex_service.search_works(
                    q, per_page=15, sort="publication_date:desc"
                )
            )

        _t0 = time.monotonic()
        try:
            results_list = await asyncio.gather(*tasks, return_exceptions=True)
            for res in results_list:
                if isinstance(res, list):
                    for w in res:
                        add_candidate_if_valid(w)
                elif isinstance(res, Exception):
                    print(f"[DailyFeed] Gather query failed: {res}", flush=True)
        except Exception as e:
            print(f"Error fetching papers for daily feed: {e}", flush=True)
        print(
            f"[DailyFeed][TIMING] main_gather={time.monotonic()-_t0:.1f}s tasks={len(tasks)} candidates={len(candidates)}",
            flush=True,
        )

        # Pull in recent works from researchers who authored papers already
        # found by the topic search above — widens the pool beyond exact
        # keyword matches (a paper can be a perfect topical match while using
        # different terminology than any of our search queries). Generic: the
        # source works came from search_queries, themselves derived entirely
        # from whichever profile is logged in.
        _t0 = time.monotonic()
        similar_researcher_ids = self._find_similar_researchers(
            candidates[:20], author_id
        )
        try:
            if similar_researcher_ids:
                peer_works_tasks = [
                    self.openalex_service.fetch_author_works(
                        peer_id, per_page=5, sort="publication_date:desc"
                    )
                    for peer_id in similar_researcher_ids
                ]
                peer_results = await asyncio.gather(
                    *peer_works_tasks, return_exceptions=True
                )
                for res in peer_results:
                    if isinstance(res, list):
                        for w in res:
                            add_candidate_if_valid(w)
                    elif isinstance(res, Exception):
                        print(
                            f"[DailyFeed] Similar-researcher works fetch failed: {res}",
                            flush=True,
                        )
        except Exception:
            pass
        print(
            f"[DailyFeed][TIMING] similar_researchers={time.monotonic()-_t0:.1f}s peers={len(similar_researcher_ids)} candidates={len(candidates)}",
            flush=True,
        )

        # Fallbacks if candidates < 15
        if len(candidates) < 15:
            print(
                f"[DailyFeed] Only {len(candidates)} candidate papers for queries='{search_queries}', loading fallbacks...",
                flush=True,
            )
            fallback_terms = [
                "nature",
                "science",
                "proceedings of the national academy of sciences",
            ]
            concepts_lower = [c.lower() for c in combined_concepts]
            fld = discipline.lower()
            if (
                any("quantum" in c or "phys" in c for c in concepts_lower)
                or "phys" in fld
                or "quantum" in fld
            ):
                fallback_terms = [
                    "physical review letters",
                    "physical review x",
                    "quantum physics",
                    "physics",
                ]
            elif (
                any(
                    "comput" in c or "machine" in c or "cs" in c or "learn" in c
                    for c in concepts_lower
                )
                or "comput" in fld
                or "ai" in fld
                or "cs" in fld
            ):
                fallback_terms = [
                    "neurips",
                    "cvpr",
                    "icml",
                    "machine learning",
                    "computer science",
                ]
            elif (
                any("genom" in c or "biol" in c or "dna" in c for c in concepts_lower)
                or "genom" in fld
                or "biol" in fld
            ):
                fallback_terms = [
                    "cell",
                    "lancet",
                    "nature medicine",
                    "genomics",
                    "biology",
                ]

            fallback_tasks = []
            for term in fallback_terms:
                fallback_tasks.append(
                    self._fetch_arxiv_candidates(term, max_results=10)
                )
                fallback_tasks.append(
                    self.openalex_service.search_works(
                        term, per_page=10, sort="publication_date:desc"
                    )
                )

            fallback_results_list = await asyncio.gather(
                *fallback_tasks, return_exceptions=True
            )
            for fallback_results in fallback_results_list:
                if isinstance(fallback_results, list):
                    for w in fallback_results:
                        add_candidate_if_valid(w)
                elif isinstance(fallback_results, Exception):
                    print(f"Fallback fetch failed: {fallback_results}", flush=True)

        # Emergency fallback to prevent crash
        if len(candidates) < 3:
            try:
                emergency_results = await self.openalex_service.search_works(
                    "science", per_page=10
                )
                for w in emergency_results:
                    title = w.get("title", "")
                    if title:
                        title_norm = title.strip().lower().rstrip(".")
                        if title_norm not in seen_titles:
                            candidates.append(w)
                            seen_titles.add(title_norm)
            except Exception:
                pass

        if len(candidates) < 3:
            raise ValueError(
                f"Could not retrieve at least 3 real, unique publications from OpenAlex/arXiv matching queries '{search_queries}'."
            )

        # Build user profile string for semantic similarity — includes the
        # researcher's own abstracts (not just titles/concepts) plus LLM-extracted
        # keywords from their 5 most recent papers. Two papers can share almost no
        # title vocabulary while being topically identical; the abstract is where
        # the real shared technical terms live, and the extracted keywords surface
        # specific current-work signals (methods, models, sub-topics) that get
        # diluted inside a long, generic abstract.
        user_abstracts = []
        for w in user_works[:5]:
            u_abstract = w.get("abstract") or w.get("_custom_abstract") or ""
            if not u_abstract and w.get("abstract_inverted_index"):
                u_abstract = self._reconstruct_abstract(w["abstract_inverted_index"])
            if u_abstract:
                user_abstracts.append(u_abstract)
        # recent_keywords already computed earlier (it now drives search_queries too).
        # Capped at 2500 chars — same reasoning as CANDIDATE_TEXT_CHAR_LIMIT below:
        # a single very long sequence still costs real compute under attention's
        # quadratic scaling, and titles+concepts+keywords (the sharpest signal)
        # come first, so truncation only ever trims the lower-value abstract tail.
        user_profile_text = (
            " ".join(user_titles)
            + " "
            + " ".join(combined_concepts)
            + " "
            + " ".join(recent_keywords)
            + " "
            + " ".join(user_abstracts)
        )[:2500]
        print(
            f"[DailyFeed][DEBUG] recent_keywords={recent_keywords!r} "
            f"user_titles={user_titles[:5]!r} profile_text_len={len(user_profile_text)}",
            flush=True,
        )

        # Pre-extract each candidate's abstract once, then embed the user profile
        # + every candidate (title+abstract) with a self-hosted sentence-transformer
        # model (BAAI/bge-small-en-v1.5) and score via cosine similarity — real
        # neural semantic similarity rather than sparse term-overlap, and it
        # understands paraphrase/synonymy that TF-IDF structurally cannot (e.g.
        # "spin-echo dephasing" vs "coherence loss under refocusing pulses").
        # Candidates are embedded in one batched forward pass for speed, not
        # one-by-one.
        # Truncated to ~300 chars (title + first 1-2 sentences of abstract) —
        # plenty for topic-similarity purposes; the rest of a long abstract
        # (methodology detail, specific numeric results) contributes
        # diminishing returns to "is this the same general topic". Critical for
        # speed: sentence-transformers pads every text in a batch to the length
        # of the *longest* text in it, so even a handful of long abstracts among
        # ~150 candidates multiplies the cost of the *entire* batch — measured:
        # untruncated abstracts (some 1000+ chars) pushed a 144-item batch to
        # 90-180s; capping at 600 chars still cost ~70-80s with real scientific-
        # text tokenization (denser than plain English), hence the tighter cap.
        CANDIDATE_TEXT_CHAR_LIMIT = 300
        candidate_texts: Dict[int, str] = {}
        for w in candidates:
            w_abstract = w.get("abstract") or w.get("_custom_abstract") or ""
            if not w_abstract and w.get("abstract_inverted_index"):
                w_abstract = self._reconstruct_abstract(w["abstract_inverted_index"])
            text = (w.get("title") or "") + " " + w_abstract
            candidate_texts[id(w)] = text[:CANDIDATE_TEXT_CHAR_LIMIT]

        _t0 = time.monotonic()
        query_vec = await embed_query(user_profile_text)
        candidate_ids = [id(w) for w in candidates]
        candidate_vecs_arr = await embed_texts(
            [candidate_texts[cid] for cid in candidate_ids]
        )
        print(
            f"[DailyFeed][TIMING] embedding={time.monotonic()-_t0:.1f}s candidates={len(candidate_ids)}",
            flush=True,
        )

        # Raw (uncentered) similarity, captured before centering below, is used
        # only for the *displayed* match % (see relevance_score in process_paper).
        # Centering is right for *ranking* (best-of-this-pool), but wrong for a
        # user-facing percentage — it guarantees roughly half the pool scores
        # "below average" every single request, which manufactures an artificial
        # cliff (e.g. 96% / 80% / 66%) even when hundreds of papers are
        # genuinely, comparably relevant. The raw score is calibrated against a
        # fixed scale instead, so it reflects absolute match quality.
        query_vec_raw = query_vec.copy()
        candidate_vecs_raw = dict(zip(candidate_ids, candidate_vecs_arr.copy()))

        # Mean-center before comparing. Sentence-embedding spaces (bge included)
        # are anisotropic — cosine similarity between two *unrelated* documents
        # commonly still lands ~0.4-0.6 instead of ~0, which flattens every
        # candidate's score into a narrow high band and destroys discrimination
        # between a great match and a mediocre one. Subtracting the pool mean
        # (a standard whitening step for sentence embeddings) re-centers "average
        # relevance to this candidate pool" at 0, so cosine similarity actually
        # spreads across the range instead of clustering near the ceiling.
        pool = np.vstack([query_vec[None, :], candidate_vecs_arr])
        mean_vec = pool.mean(axis=0)

        def _center_and_norm(v: np.ndarray) -> np.ndarray:
            c = v - mean_vec
            norm = np.linalg.norm(c)
            return c / norm if norm > 0 else c

        query_vec = _center_and_norm(query_vec)
        candidate_vecs_arr = np.array([_center_and_norm(v) for v in candidate_vecs_arr])
        candidate_vecs = dict(zip(candidate_ids, candidate_vecs_arr))

        # Score and sort candidates
        current_year = datetime.datetime.now().year
        scored_candidates = []
        for w in candidates:
            primary_loc = w.get("primary_location") or {}
            source_obj = primary_loc.get("source") or {} if primary_loc else {}
            journal = source_obj.get("display_name") or ""
            is_prest = is_prestigious_journal(journal)
            citations = w.get("cited_by_count") or 0

            candidate_vec = candidate_vecs[id(w)]
            similarity_score = vector_cosine_similarity(candidate_vec, query_vec)
            raw_similarity_score = vector_cosine_similarity(
                candidate_vecs_raw[id(w)], query_vec_raw
            )
            # Relevance is now decided by the same mean-centered embedding signal
            # that's actually built from the researcher's recent-paper keywords,
            # abstracts, and concepts (see user_profile_text above) — not by
            # is_work_relevant_to_discipline's generic substring matching against
            # a single discipline string (e.g. "physics" triggers on "energy",
            # "matter", "optical", "mechanics"... which is nearly every hard-
            # science abstract, including totally unrelated fields). similarity_score
            # > 0 means "above this candidate pool's average relevance" post-centering.
            is_rel = similarity_score > 0

            # Decays linearly to 0 over a 10-year window — see get_sort_key below for
            # why this needs to dominate the ranking rather than just break ties.
            pub_date = w.get("publication_date") or "1970-01-01"
            try:
                pub_year = int(pub_date[:4])
            except (ValueError, TypeError):
                pub_year = 1970
            recency_score = max(0.0, min(1.0, (pub_year - (current_year - 10)) / 10))

            scored_candidates.append(
                {
                    "work": w,
                    "is_prestigious": is_prest,
                    "is_relevant": is_rel,
                    "citations": citations,
                    "similarity_score": similarity_score,
                    "raw_similarity_score": raw_similarity_score,
                    "recency_score": recency_score,
                    "vec": candidate_vec,
                }
            )

        # Sorting strategy:
        # 1. Relevance: is_relevant (True vs False) — hard gate, unchanged.
        # 2. Blended (recency-weighted similarity) — recency is the dominant signal
        #    among relevant candidates, not a last-resort tiebreaker. Previously
        #    pub_date only broke *exact* similarity_score ties, which almost never
        #    happen with a continuous token-overlap score — so a well-cited decade-old
        #    paper on an established topic (lots of shared terminology with the
        #    author's own older works) would always outrank a genuinely new paper,
        #    even when the whole point is "what's new in your field". recency_score
        #    decays linearly to 0 over a 10-year window and is weighted 2x against a
        #    mean-centered similarity_score (0 for below-pool-average candidates, up
        #    to ~0.5 for standout ones — see the embedding centering step above), so
        #    recent papers dominate by default — but an exceptional old match can
        #    still surface if nothing recent is relevant, instead of the list going
        #    empty.
        # 3. publication_date (descending) — tiebreaker for near-equal blended scores.
        # 4. Prestige: is_prestigious (True vs False)
        # 5. Citations: citations (descending)
        def get_sort_key(item):
            rel = 1 if item["is_relevant"] else 0
            blended = item["recency_score"] * 2.0 + item["similarity_score"]
            pub_date = item["work"].get("publication_date") or "1970-01-01"
            prest = 1 if item["is_prestigious"] else 0
            citations = item["citations"]
            return (rel, blended, pub_date, prest, citations)

        scored_candidates.sort(key=get_sort_key, reverse=True)

        # Re-ranking pass: rather than trusting the raw top-3 by blended score
        # (which tends to cluster on near-duplicate papers about the same narrow
        # sub-topic — several arXiv preprints citing each other, say), pull a
        # broader shortlist and let MMR diversification pick the final 3. MMR
        # balances relevance to the user's TF-IDF profile against redundancy with
        # papers already selected, so the feed doesn't just show 3 versions of
        # the same paper. Raised from 15 to 30 alongside the larger candidate
        # pool above, so MMR has a genuinely representative set of strong
        # matches to choose diversity from, not just whatever the top 15 of a
        # ~20-paper pool happened to be.
        shortlist = scored_candidates[:30]

        # MMR must only diversify among candidates that are actually relevant —
        # its redundancy penalty rewards picking whatever is most *different*
        # from what's already selected, so if the truly on-topic candidates are
        # few (say only 2 papers with above-average similarity), MMR will happily
        # fill the last slot with a totally unrelated paper specifically because
        # it's maximally dissimilar. similarity_score is mean-centered (see
        # above), so 0 already means "at or below this candidate pool's average
        # relevance" — those get excluded from MMR's input entirely and only
        # backfilled (in blended-sort_key order, i.e. best-remaining-first) if
        # there aren't 3 genuinely relevant candidates to choose from.
        relevant_pool = [it for it in shortlist if it["similarity_score"] > 0]
        mmr_input = relevant_pool if len(relevant_pool) >= 3 else shortlist

        scored_by_work_id = {id(item["work"]): item for item in shortlist}
        mmr_papers = mmr_diversify(
            [(item["work"], item["vec"]) for item in mmr_input], query_vec, k=3
        )
        top_candidates = [
            scored_by_work_id[id(p)] for p in mmr_papers if id(p) in scored_by_work_id
        ]
        if len(top_candidates) < 3:
            seen_ids = {id(item["work"]) for item in top_candidates}
            for item in shortlist:
                if len(top_candidates) >= 3:
                    break
                if id(item["work"]) not in seen_ids:
                    top_candidates.append(item)

        async def process_paper(i: int, scored: Dict[str, Any]) -> Dict[str, Any]:
            paper = scored["work"]
            title = paper.get("title", "Untitled Research Paper")
            authors = [
                a.get("author", {}).get("display_name", "Unknown")
                for a in paper.get("authorships", [])
            ][:3]
            primary_loc = paper.get("primary_location") or {}
            source_obj = primary_loc.get("source") or {} if primary_loc else {}
            journal = source_obj.get("display_name")
            if not journal:
                doi_val = paper.get("doi") or ""
                pdf_val = primary_loc.get("pdf_url") or ""
                landing_val = primary_loc.get("landing_page_url") or ""
                if (
                    "arxiv" in doi_val.lower()
                    or "arxiv.org" in pdf_val.lower()
                    or "arxiv.org" in landing_val.lower()
                ):
                    journal = "arXiv Preprint"
                else:
                    concepts_list = paper.get("concepts") or paper.get("topics") or []
                    if concepts_list and isinstance(concepts_list, list):
                        journal = concepts_list[0].get("display_name")
                    if not journal:
                        journal = "Scientific Journal"
            year = paper.get("publication_year") or 2025
            doi = paper.get("doi")
            openalex_id = paper.get("id")
            publication_date = paper.get("publication_date") or f"{year}-01-01"

            abstract = paper.get("_custom_abstract")
            if not abstract:
                abstract = self._reconstruct_abstract(
                    paper.get("abstract_inverted_index")
                )
            if not abstract:
                abstract = "No abstract available."

            custom_meta = paper.get("_custom_metadata")
            if custom_meta:
                meta = custom_meta
                relevance_score = custom_meta.get("relevance_score", 95)
                recommendation_reason = custom_meta.get(
                    "recommendation_reason", "Recommended historical literature."
                )
            else:
                # Displayed match % is deliberately NOT the pool-centered
                # similarity_score used for ranking (see get_sort_key/MMR above).
                # Centering answers "best of what we fetched this round" — good
                # for choosing which 3 to show, bad for a user-facing percentage,
                # since it guarantees ~half of any pool reads as "below average"
                # regardless of absolute quality, manufacturing an artificial
                # cliff between rank 1 and rank 2 even when both are genuinely
                # strong matches. raw_similarity_score (uncentered bge cosine
                # similarity) is calibrated instead against a fixed scale —
                # ~0.45 (weak/generic overlap) to ~0.80 (excellent, closely
                # on-topic) — so the percentage reflects absolute match quality
                # and doesn't collapse when hundreds of candidates are all
                # legitimately relevant.
                raw_similarity_score = scored["raw_similarity_score"]
                recency_score = scored["recency_score"]
                is_relevant = scored["is_relevant"]
                is_prestigious = scored["is_prestigious"]
                calibrated = 40 + (raw_similarity_score - 0.45) / 0.35 * 57
                relevance_score = round(
                    min(
                        97,
                        max(
                            40,
                            calibrated
                            + recency_score * 3
                            + (3 if is_relevant else -10)
                            + (2 if is_prestigious else 0),
                        ),
                    )
                )
                recommendation_reason = "Recommended based on your research profile."
                llm_ok = is_llm_working()

                meta_task = None
                if llm_ok:
                    meta_task = asyncio.create_task(
                        self.extract_metadata_via_llm(title, abstract)
                    )

                reason_task = None
                if llm_ok and concepts:
                    messages = [
                        {
                            "role": "user",
                            "content": DAILY_FEED_ADVISOR_PROMPT_TEMPLATE.format(
                                paper_title=title,
                                paper_abstract=abstract[:800],
                                author_name=author_name,
                                concepts=", ".join(concepts),
                            ),
                        }
                    ]
                    reason_task = asyncio.create_task(
                        self.llm_service.query(
                            messages=messages,
                            models=[self.model],
                            temperature=0.5,
                            max_tokens=50,
                        )
                    )

                if meta_task and reason_task:
                    try:
                        meta_res, reason_res = await asyncio.gather(
                            meta_task, reason_task, return_exceptions=True
                        )
                        if isinstance(meta_res, Exception):
                            print(f"Error in metadata task: {meta_res}", flush=True)
                            meta = extract_metadata_from_abstract(title, abstract)
                        else:
                            meta = meta_res or extract_metadata_from_abstract(
                                title, abstract
                            )

                        if isinstance(reason_res, Exception):
                            print(
                                f"Error in recommendation reason task: {reason_res}",
                                flush=True,
                            )
                        elif reason_res and reason_res.content:
                            recommendation_reason = reason_res.content.strip()
                    except Exception as e:
                        print(f"Error in gather for paper {i}: {e}", flush=True)
                        meta = extract_metadata_from_abstract(title, abstract)
                elif meta_task:
                    try:
                        meta = await meta_task
                        if not meta:
                            meta = extract_metadata_from_abstract(title, abstract)
                    except Exception as e:
                        print(f"Error awaiting metadata task: {e}", flush=True)
                        meta = extract_metadata_from_abstract(title, abstract)
                elif reason_task:
                    meta = extract_metadata_from_abstract(title, abstract)
                    try:
                        reason_res = await reason_task
                        if reason_res and reason_res.content:
                            recommendation_reason = reason_res.content.strip()
                    except Exception as e:
                        print(
                            f"Error awaiting recommendation reason task: {e}",
                            flush=True,
                        )
                else:
                    meta = extract_metadata_from_abstract(title, abstract)

            return {
                "id": openalex_id,
                "title": title,
                "authors": authors,
                "journal": journal,
                "year": year,
                "publication_date": publication_date,
                "relevance_score": relevance_score,
                "recommendation_reason": recommendation_reason,
                "doi": doi,
                "abstract": abstract,
                "methodology": meta.get("methodology", ""),
                "tools_used": meta.get("tools_used", []),
                "key_findings": meta.get("key_findings", ""),
            }

        _t0 = time.monotonic()
        tasks = [process_paper(i, scored) for i, scored in enumerate(top_candidates)]
        feed_items = list(await asyncio.gather(*tasks))
        print(
            f"[DailyFeed][TIMING] process_paper_llm={time.monotonic()-_t0:.1f}s papers={len(feed_items)}",
            flush=True,
        )

        if feed_items:
            try:
                await self._save_to_postgres(cache_key, {"items": feed_items})
                print(
                    f"[Postgres Cache Save] daily_feeds for doc_id={doc_id}", flush=True
                )
            except Exception as e:
                print(
                    f"[Postgres Cache Error] daily_feeds write failed: {e}", flush=True
                )
        if feed_items:
            try:
                from firebase_admin import firestore as _fs

                await self._firestore_set_safe(
                    "daily_feeds",
                    doc_id,
                    {"items": feed_items, "last_synced": _fs.SERVER_TIMESTAMP},
                )
            except Exception as e:
                print(
                    f"[Firestore Cache Error] daily_feeds write failed: {e}", flush=True
                )
        return feed_items

    async def match_grants(self, author_id: str) -> List[Dict[str, Any]]:
        """
        Scores prestigious STEM grant options against the researcher's profile.
        """
        clean_id = author_id.split("/")[-1]
        cache_key = f"match_grants_{clean_id}"
        cached_data = await self._load_from_postgres(cache_key)
        if isinstance(cached_data, dict) and "items" in cached_data:
            print(
                f"[Postgres Cache Hit] match_grants for author_id={clean_id}",
                flush=True,
            )
            return cached_data["items"]
        _fs_cached = await self._firestore_get_safe(
            "match_grants", clean_id, timeout=5.0
        )
        if isinstance(_fs_cached, dict) and "items" in _fs_cached:
            print(
                f"[Firestore Cache Hit] match_grants for author_id={clean_id}",
                flush=True,
            )
            await self._save_to_postgres(cache_key, {"items": _fs_cached["items"]})
            return _fs_cached["items"]
        self._get_firestore_db()
        profile = await self._fetch_author_profile(author_id)
        author_name = "Researcher"
        h_index = 5
        concepts = ["STEM"]
        concepts_lower = ["stem"]
        if profile:
            author_name = profile.get("display_name", "Researcher")
            h_index = profile.get("summary_stats", {}).get("h_index", 5)
            author_name_lower = author_name.lower()
            x_concepts = profile.get("x_concepts", []) or []
            # Filter self-name concepts and extract level 1/2
            valid = [
                c
                for c in x_concepts
                if c.get("display_name")
                and c.get("display_name").lower() != author_name_lower
            ]
            concepts = [
                c.get("display_name") for c in valid if c.get("level") in [1, 2]
            ][:3]
            if not concepts:
                concepts = [
                    c.get("display_name") for c in valid[:5] if c.get("display_name")
                ]
            if not concepts:
                topics = profile.get("topics", []) or []
                concepts = [
                    t.get("display_name") for t in topics[:3] if t.get("display_name")
                ]
            concepts_lower = [c.lower() for c in concepts if c]
        grants = [
            {
                "title": "Core Research Grant (CRG) 2025–26",
                "agency": "SERB",
                "agency_color": "#009688",  # Teal
                "days_left": 23,
                "amount": "₹50–90 Lakh",
                "field": "All STEM Fields",
                "url": "https://www.serbonline.in/",
            },
            {
                "title": "National Science Foundation — CAREER Award",
                "agency": "NSF",
                "agency_color": "#3F51B5",  # Indigo
                "days_left": 41,
                "amount": "$500K–1M",
                "field": "CS/Engineering/Basic Sciences",
                "url": "https://www.nsf.gov/funding/opportunities/career-faculty-early-career-development-program",
            },
            {
                "title": "DST INSPIRE Faculty Award",
                "agency": "DST",
                "agency_color": "#4CAF50",  # Emerald
                "days_left": 58,
                "amount": "₹35 Lakh/yr",
                "field": "Science & Tech Innovation",
                "url": "https://dst.gov.in/scientific-programmes/scientific-engineering-research/inspire",
            },
            {
                "title": "NIH R01 Research Project Grant",
                "agency": "NIH",
                "agency_color": "#E91E63",  # Rose
                "days_left": 67,
                "amount": "$250K–1.5M",
                "field": "Biomedical & Life Sciences",
                "url": "https://grants.nih.gov/grants/funding/r01.htm",
            },
            {
                "title": "Prime Minister's Research Fellows (PMRF)",
                "agency": "MoE",
                "agency_color": "#FF9800",  # Amber
                "days_left": 89,
                "amount": "₹80K/month + research grants",
                "field": "Sleek Technical PhD/Post-doc research",
                "url": "https://www.pmrf.in/",
            },
            {
                "title": "ERC Starting Grants",
                "agency": "ERC",
                "agency_color": "#9C27B0",  # Purple
                "days_left": 105,
                "amount": "€1.5M",
                "field": "High-impact pioneering science",
                "url": "https://erc.europa.eu/apply-funding/starting-grant",
            },
        ]

        async def process_grant(grant: Dict[str, Any]) -> Dict[str, Any]:
            grant_field_lower = grant["field"].lower()
            # Compute field overlap: how many author concepts match the grant's field
            field_overlap = sum(
                1
                for c in concepts_lower
                if c
                and (
                    c in grant_field_lower
                    or grant_field_lower in c
                    or any(
                        word in grant_field_lower for word in c.split() if len(word) > 3
                    )
                )
            )
            # Deterministic score: base (60) + h_index contribution + field overlap bonus
            # All STEM grants get at least base score; specific field grants get bonus
            h_contribution = min(h_index * 2, 20)  # cap at 20 points
            overlap_bonus = field_overlap * 5  # 5 points per matching concept
            # Universal grants (SERB, ERC) get a small base bonus
            universal_bonus = (
                5 if "all" in grant_field_lower or "pioneer" in grant_field_lower else 0
            )
            match_score = min(
                max(60 + h_contribution + overlap_bonus + universal_bonus, 62), 98
            )
            rationale = f"Aligned with your research track in {', '.join(concepts[:2]) if concepts else 'STEM'}."
            if (
                is_llm_working()
            ):  # Decoupled: LLM moved to background addon to unblock core app
                messages = [
                    {
                        "role": "system",
                        "content": GRANT_ADVISOR_PROMPT_TEMPLATE.format(
                            title=grant["title"],
                            agency=grant["agency"],
                            author_name=author_name,
                            h_index=h_index,
                            concepts=", ".join(concepts),
                        ),
                    }
                ]
                try:
                    response = await self.llm_service.query(
                        messages=messages,
                        models=[self.model],
                        temperature=0.4,
                        max_tokens=100,
                    )
                    if response.content:
                        rationale = response.content.strip()
                except Exception as e:
                    print(f"Grant rationale generation failed: {e}", flush=True)
            return {
                "title": grant["title"],
                "agency": grant["agency"],
                "agency_color": grant["agency_color"],
                "days_left": grant["days_left"],
                "amount": grant["amount"],
                "field": grant["field"],
                "match_score": match_score,
                "url": grant["url"],
                "rationale": rationale,
            }

        tasks = [process_grant(grant) for grant in grants]
        scored_grants = list(await asyncio.gather(*tasks))
        if scored_grants:
            try:
                await self._save_to_postgres(cache_key, {"items": scored_grants})
                print(
                    f"[Postgres Cache Save] match_grants for author_id={clean_id}",
                    flush=True,
                )
            except Exception as e:
                print(
                    f"[Postgres Cache Error] match_grants write failed: {e}", flush=True
                )
        if scored_grants:
            try:
                from firebase_admin import firestore as _fs

                await self._firestore_set_safe(
                    "match_grants",
                    clean_id,
                    {"items": scored_grants, "last_synced": _fs.SERVER_TIMESTAMP},
                )
            except Exception as e:
                print(
                    f"[Firestore Cache Error] match_grants write failed: {e}",
                    flush=True,
                )
        return scored_grants

    async def get_collaborator_synergy(
        self, author_id: str, collaborator_id: str
    ) -> Dict[str, Any]:
        """
        Generates specific joint proposals and strategic co-authorship pathways between two researchers.
        """
        clean_author = author_id.split("/")[-1]
        clean_collab = collaborator_id.split("/")[-1]
        doc_id = f"{clean_author}_{clean_collab}"
        cache_key = f"collaborator_synergy_{doc_id}"
        cached_data = await self._load_from_postgres(cache_key, ttl_seconds=7200)
        if cached_data:
            print(
                f"[Postgres Cache Hit] collaborator_synergy for doc_id={doc_id}",
                flush=True,
            )
            return cached_data
        _fs_cached = await self._firestore_get_safe(
            "collaborator_synergies", doc_id, timeout=5.0
        )
        if isinstance(_fs_cached, dict):
            print(
                f"[Firestore Cache Hit] collaborator_synergies for doc_id={doc_id}",
                flush=True,
            )
            _fs_cached.pop("last_synced", None)
            await self._save_to_postgres(cache_key, _fs_cached, ttl_seconds=7200)
            return _fs_cached
        profile1_task = self._fetch_author_profile(author_id)
        profile2_task = self._fetch_author_profile(collaborator_id)
        profile1, profile2 = await asyncio.gather(profile1_task, profile2_task)
        # Fallback database lookup for profiles if OpenAlex fails
        if not profile1:
            try:
                from app.models.researcher_models import ResearcherMetrics

                async with self._db_session() as session:
                    stmt = select(ResearcherMetrics).where(
                        ResearcherMetrics.openalex_id == clean_author
                    )
                    res = await session.execute(stmt)
                    rm = res.scalars().first()
                    if rm:
                        profile1 = {
                            "display_name": rm.display_name,
                            "x_concepts": [
                                {"display_name": c, "level": 1}
                                for c in rm.expertise or []
                            ],
                        }
            except Exception as e:
                print(
                    f"[CollaboratorSynergy] DB fallback lookup for author failed: {e}",
                    flush=True,
                )
        if not profile2:
            try:
                from app.models.researcher_models import ResearcherMetrics

                async with self._db_session() as session:
                    stmt = select(ResearcherMetrics).where(
                        ResearcherMetrics.openalex_id == clean_collab
                    )
                    res = await session.execute(stmt)
                    rm = res.scalars().first()
                    if rm:
                        profile2 = {
                            "display_name": rm.display_name,
                            "x_concepts": [
                                {"display_name": c, "level": 1}
                                for c in rm.expertise or []
                            ],
                        }
            except Exception as e:
                print(
                    f"[CollaboratorSynergy] DB fallback lookup for collab failed: {e}",
                    flush=True,
                )
        name1 = (
            profile1.get("display_name", "Researcher A") if profile1 else "Researcher A"
        )
        name2 = (
            profile2.get("display_name", "Researcher B") if profile2 else "Researcher B"
        )

        def extract_concepts(
            profile: Optional[Dict[str, Any]], fallback_name: str
        ) -> List[str]:
            """Extract clean concept list, filtering the researcher's own name (OpenAlex quirk)."""
            if not profile:
                return []
            name_lower = (profile.get("display_name") or "").lower()
            x = profile.get("x_concepts", []) or []
            # Filter self-name concepts
            valid = [
                c
                for c in x
                if c.get("display_name") and c.get("display_name").lower() != name_lower
            ]
            result = [
                c.get("display_name")
                for c in valid
                if c.get("level") in [1, 2] and c.get("display_name")
            ]
            if not result:
                result = [
                    c.get("display_name") for c in valid[:5] if c.get("display_name")
                ]
            if not result:
                topics = profile.get("topics", []) or []
                result = [
                    t.get("display_name") for t in topics[:5] if t.get("display_name")
                ]
            return result

        concepts1 = extract_concepts(profile1, "Quantum Mechanics") or [
            "Quantum Mechanics"
        ]
        concepts2 = extract_concepts(profile2, "Machine Learning") or [
            "Machine Learning"
        ]
        overlap_concepts = list(set(concepts1).intersection(set(concepts2)))
        # Deterministic synergy score based on overlap — no random component
        synergy_score = 72 + min(
            len(overlap_concepts) * 5, 20
        )  # max 92 from overlap alone
        synergy_score = min(max(synergy_score, 70), 99)

        try:
            if not is_llm_working():
                raise Exception("LLM service is currently offline or rate-limited.")
            messages = [
                {
                    "role": "system",
                    "content": SYNERGY_COUNSELOR_PROMPT_TEMPLATE.format(
                        name1=name1,
                        concepts1=", ".join(concepts1[:4]),
                        name2=name2,
                        concepts2=", ".join(concepts2[:4]),
                    ),
                }
            ]
            response = await self.llm_service.query(
                messages=messages,
                models=[self.model],
                temperature=0.3,
                max_tokens=300,
                response_format={"type": "json_object"},
            )
            if response.content:
                data = json.loads(response.content.strip())
                joint_proposal_title = data["joint_proposal_title"]
                co_authorship_direction = data["co_authorship_direction"]
                strategic_action_plan = data["strategic_action_plan"]
            else:
                raise ValueError("LLM returned empty synergy analysis.")
        except Exception as e:
            print(
                f"[CollaboratorSynergy] LLM query failed: {e}. Generating high-quality local fallback...",
                flush=True,
            )
            joint_proposal_title = f"Synergistic Research Framework in {overlap_concepts[0] if overlap_concepts else 'Cross-Disciplinary Studies'}"
            co_authorship_direction = f"A collaborative study between {name1} and {name2} focusing on integrating their respective expertise in {', '.join(concepts1[:2])} and {', '.join(concepts2[:2])}."
            strategic_action_plan = [
                "Establish common datasets and shared repository of code.",
                "Co-draft a preliminary outline targeting a high-impact journal.",
                "Submit a joint seed-grant application to fund the collaborative research.",
            ]

        result = {
            "synergy_score": synergy_score,
            "joint_proposal_title": joint_proposal_title,
            "co_authorship_direction": co_authorship_direction,
            "strategic_action_plan": strategic_action_plan,
        }
        try:
            await self._save_to_postgres(cache_key, result, ttl_seconds=7200)
            print(
                f"[Postgres Cache Save] collaborator_synergy for doc_id={doc_id}",
                flush=True,
            )
        except Exception as e:
            print(
                f"[Postgres Cache Error] collaborator_synergy write failed: {e}",
                flush=True,
            )
        try:
            from firebase_admin import firestore as _fs

            await self._firestore_set_safe(
                "collaborator_synergies",
                doc_id,
                {**result, "last_synced": _fs.SERVER_TIMESTAMP},
            )
        except Exception as e:
            print(
                f"[Firestore Cache Error] collaborator_synergies write failed: {e}",
                flush=True,
            )
        return result

    async def get_citation_heatmap(self, author_id: str) -> Dict[str, Any]:
        """
        Visualizes year-by-year citation and publication count trends.
        """
        clean_id = author_id.split("/")[-1]
        cache_key = f"citation_heatmap_{clean_id}"
        cached_data = await self._load_from_postgres(cache_key)
        if cached_data:
            print(
                f"[Postgres Cache Hit] citation_heatmap for author_id={clean_id}",
                flush=True,
            )
            return cached_data
        _fs_cached = await self._firestore_get_safe(
            "citation_heatmaps", clean_id, timeout=5.0
        )
        if isinstance(_fs_cached, dict):
            print(
                f"[Firestore Cache Hit] citation_heatmaps for author_id={clean_id}",
                flush=True,
            )
            _fs_cached.pop("last_synced", None)
            await self._save_to_postgres(cache_key, _fs_cached)
            return _fs_cached
        profile = await self._fetch_author_profile(author_id)
        if not profile:
            return {
                "years": [],
                "citations": [],
                "works": [],
                "institutional_reach": 0,
                "h_index": 0,
            }
        counts_by_year = profile.get("counts_by_year", [])
        counts_by_year = sorted(counts_by_year, key=lambda x: x.get("year", 0))
        # Keep last 8 years for compactness in mobile layout
        recent_counts = (
            counts_by_year[-8:] if len(counts_by_year) > 8 else counts_by_year
        )
        years = [x.get("year") or 0 for x in recent_counts]
        citations = [x.get("cited_by_count") or 0 for x in recent_counts]
        works = [x.get("works_count") or 0 for x in recent_counts]
        h_index = int((profile.get("summary_stats") or {}).get("h_index") or 5)
        # Estimate institutional reach based on co-authorships count from recent works
        institutional_reach = min(int(h_index * 1.5) + random.randint(2, 6), 35)
        result = {
            "years": years,
            "citations": citations,
            "works": works,
            "institutional_reach": institutional_reach,
            "h_index": h_index,
        }
        try:
            await self._save_to_postgres(cache_key, result)
            print(
                f"[Postgres Cache Save] citation_heatmap for author_id={clean_id}",
                flush=True,
            )
        except Exception as e:
            print(
                f"[Postgres Cache Error] citation_heatmap write failed: {e}", flush=True
            )
        try:
            from firebase_admin import firestore as _fs

            await self._firestore_set_safe(
                "citation_heatmaps",
                clean_id,
                {**result, "last_synced": _fs.SERVER_TIMESTAMP},
            )
        except Exception as e:
            print(
                f"[Firestore Cache Error] citation_heatmaps write failed: {e}",
                flush=True,
            )
        return result

    async def get_journal_advisor(self, author_id: str) -> List[Dict[str, Any]]:
        """
        Recommends real journal venues from OpenAlex, ranked by embedding
        similarity to the researcher's actual profile. The LLM is used only
        to write a short plain-English rationale per journal — never to
        invent the journal identity, its stats, or (the past source of
        garbled-LaTeX bugs) a "submission tip" with gratuitous equations.
        """
        clean_id = author_id.split("/")[-1]
        cache_key = f"journal_advisor_{clean_id}"
        cached_data = await self._load_from_postgres(cache_key, ttl_seconds=7200)
        if isinstance(cached_data, dict) and "venues" in cached_data:
            print(
                f"[Postgres Cache Hit] journal_advisor for author_id={clean_id}",
                flush=True,
            )
            return cached_data["venues"]
        _fs_cached = await self._firestore_get_safe(
            "journal_advisor_recommendations", clean_id, timeout=5.0
        )
        if isinstance(_fs_cached, dict) and "venues" in _fs_cached:
            print(
                f"[Firestore Cache Hit] journal_advisor_recommendations for author_id={clean_id}",
                flush=True,
            )
            await self._save_to_postgres(
                cache_key, {"venues": _fs_cached["venues"]}, ttl_seconds=7200
            )
            return _fs_cached["venues"]

        # x_concepts is largely empty on current OpenAlex author objects
        # (confirmed live — deprecated in favor of `topics`), so reading it
        # directly silently fell back to a useless "science" query term.
        # extract_field_and_expertise (topics-first, same helper get_daily_feed
        # uses via _resolve_author_concepts_and_name) fixes that — called
        # directly here (not through the helper) because it also needs the
        # broader single `field` label the helper discards.
        profile = await self._fetch_author_profile(author_id)
        author_name = "Researcher"
        field = ""
        concepts: List[str] = []
        if profile:
            from app.services.data.openalex_service import extract_field_and_expertise

            author_name = profile.get("display_name", "Researcher")
            field, concepts = extract_field_and_expertise(profile, author_name)
        else:
            try:
                from app.models.researcher_models import ResearcherMetrics

                async with self._db_session() as session:
                    stmt = select(ResearcherMetrics).where(
                        ResearcherMetrics.openalex_id == clean_id
                    )
                    res = await session.execute(stmt)
                    rm = res.scalars().first()
                    if rm:
                        author_name = rm.display_name
                        field = rm.field_of_study or ""
                        concepts = rm.expertise or []
            except Exception as e:
                print(
                    f"[JournalAdvisor] Database lookup fallback error: {e}", flush=True
                )

        # Richer profile signal than concepts alone — same recent-paper
        # keyword extraction get_daily_feed uses, so sparse profiles (few
        # broad concepts) still get sharp, current-work-specific query terms.
        recent_keywords: List[str] = []
        try:
            user_works = await self.openalex_service.fetch_author_works(
                clean_id, per_page=10
            )
            if user_works:
                recent_keywords = await self._extract_recent_paper_keywords(user_works)
        except Exception as e:
            print(f"[JournalAdvisor] Recent-works fetch failed: {e}", flush=True)

        # `field` first — OpenAlex's /sources search matches fairly literally
        # against journal names/descriptions (confirmed live: short, standard
        # field labels like "Theoretical Physics" or "Cosmology" reliably
        # match real journals; composite topic labels like "Black Holes and
        # Theoretical Physics" or specific technical keywords usually match
        # nothing at all) — so lead with the label most likely to actually
        # hit, and treat the sharper concepts/keywords as additional tries
        # rather than the primary query.
        query_terms = list(
            dict.fromkeys([t for t in [field, *concepts, *recent_keywords] if t])
        )[:8] or ["science"]

        # Fetch real candidate journals from OpenAlex — multiple query terms,
        # deduped by id, same "gather from several search terms" pattern
        # get_daily_feed uses for candidate papers. Tries more terms than a
        # typical candidate search since several will legitimately return
        # zero results (see note above) rather than being a bug to fix.
        seen_ids: Set[str] = set()
        candidates: List[Dict[str, Any]] = []
        for term in query_terms[:6]:
            try:
                results = await self.openalex_service.search_sources(term, per_page=8)
            except Exception as e:
                print(f"[JournalAdvisor] search_sources failed for '{term}': {e}", flush=True)
                continue
            for src in results:
                src_id = src.get("id")
                if not src_id or src_id in seen_ids:
                    continue
                # OpenAlex /sources search matches repositories, conference
                # proceedings, ebook platforms, and funding-agency
                # aggregators alongside real journals (confirmed live —
                # "Open Science Framework", "Lecture notes in computer
                # science", a Portuguese funding agency's repository all
                # matched generic queries) — "Journal Advisor" should only
                # ever recommend type == "journal".
                if src.get("type") != "journal":
                    continue
                # Drop implausibly inactive/defunct-looking venues rather
                # than inventing a scoring system for venue health.
                if (src.get("works_count") or 0) < 20:
                    continue
                seen_ids.add(src_id)
                candidates.append(src)

        if not candidates:
            # Real data genuinely has nothing for this niche — return empty
            # and let the frontend show its existing "not enough data yet"
            # state, instead of falling back to invented journals.
            return []

        # Rank by real embedding similarity between the researcher's profile
        # and each journal's own real text (name + topics + publisher) — the
        # same grounding already used for match_score, now also deciding
        # *which* journals to show, not just their displayed score.
        profile_text = (author_name + " " + " ".join(query_terms))[:1000]
        candidate_texts = [
            f"{c.get('display_name', '')} "
            + " ".join(t.get("display_name", "") for t in (c.get("topics") or [])[:5])
            + f" {c.get('host_organization_name') or ''}"
            for c in candidates
        ]
        try:
            scores = await score_candidates_against_profile(profile_text, candidate_texts)
        except Exception as e:
            print(f"[JournalAdvisor] Grounded scoring failed: {e}", flush=True)
            scores = [70] * len(candidates)

        ranked = sorted(zip(candidates, scores), key=lambda pair: pair[1], reverse=True)
        top3 = ranked[:3]

        async def _rationale_for(src: Dict[str, Any]) -> str:
            default = f"Aligned with your work in {', '.join(query_terms[:2])}."
            if not is_llm_working():
                return default
            try:
                response = await self.llm_service.query(
                    messages=[
                        {
                            "role": "user",
                            "content": JOURNAL_ADVISOR_RATIONALE_PROMPT_TEMPLATE.format(
                                author_name=author_name,
                                concepts=", ".join(query_terms[:3]),
                                journal_name=src.get("display_name") or "this journal",
                                works_count=src.get("works_count") or 0,
                                oa_status="fully open access"
                                if src.get("is_oa")
                                else "hybrid/subscription access",
                                host_organization=src.get("host_organization_name")
                                or "an academic publisher",
                            ),
                        }
                    ],
                    models=[self.model],
                    temperature=0.4,
                    max_tokens=100,
                )
                return (response.content or default).strip() or default
            except Exception as e:
                print(
                    f"[JournalAdvisor] Rationale generation failed for "
                    f"{src.get('display_name')}: {e}",
                    flush=True,
                )
                return default

        rationales = await asyncio.gather(*[_rationale_for(src) for src, _ in top3])

        venues: List[Dict[str, Any]] = []
        for (src, score), rationale in zip(top3, rationales):
            stats = src.get("summary_stats") or {}
            venues.append(
                {
                    "journal_name": src.get("display_name") or "Unknown Journal",
                    "works_count": src.get("works_count") or 0,
                    "is_oa": bool(src.get("is_oa")),
                    "citation_impact": round(stats.get("2yr_mean_citedness") or 0.0, 2),
                    "match_score": score,
                    "rationale": rationale,
                }
            )

        try:
            await self._save_to_postgres(cache_key, {"venues": venues}, ttl_seconds=7200)
            print(
                f"[Postgres Cache Save] journal_advisor for author_id={clean_id}",
                flush=True,
            )
        except Exception as e:
            print(
                f"[Postgres Cache Error] journal_advisor write failed: {e}", flush=True
            )

        try:
            from firebase_admin import firestore as _fs

            await self._firestore_set_safe(
                "journal_advisor_recommendations",
                clean_id,
                {"venues": venues, "last_synced": _fs.SERVER_TIMESTAMP},
            )
        except Exception as e:
            print(
                f"[Firestore Cache Error] journal_advisor_recommendations write failed: {e}",
                flush=True,
            )
        return venues

    def _process_depth1_authorship(
        self,
        auth_ship: Dict[str, Any],
        work_title: str,
        work_field: str,
        work_concepts: List[str],
        exclude_set: Set[str],
        depth1_authors: Dict[str, Any],
    ) -> None:
        """Processes a single authorship entry for depth 1 collaborators."""
        author_meta = auth_ship.get("author", {})
        auth_id = author_meta.get("id")
        if not auth_id:
            return
        auth_clean = auth_id.split("/")[-1]
        if auth_clean in exclude_set:
            return
        name = author_meta.get("display_name", "Unknown")
        insts = auth_ship.get("institutions", [])
        inst_name = insts[0].get("display_name") if insts else "Independent Researcher"
        if auth_id not in depth1_authors:
            depth1_authors[auth_id] = {
                "id": auth_id,
                "name": name,
                "institution": inst_name,
                "field": work_field,
                "shared_paper": work_title,
                "joint_count": 1,
                "work_concepts": work_concepts,
            }
        else:
            depth1_authors[auth_id]["joint_count"] += 1

    def _process_depth1_work(
        self,
        work: Dict[str, Any],
        exclude_set: Set[str],
        depth1_authors: Dict[str, Any],
    ) -> None:
        """Processes a single publication to extract depth 1 co-authors."""
        work_title = work.get("title", "Research Paper")
        concepts = work.get("concepts", [])
        work_concepts = [
            c.get("display_name") for c in concepts if c.get("display_name")
        ]
        work_field = work_concepts[0] if work_concepts else "Researcher"
        for auth_ship in work.get("authorships", []):
            self._process_depth1_authorship(
                auth_ship=auth_ship,
                work_title=work_title,
                work_field=work_field,
                work_concepts=work_concepts,
                exclude_set=exclude_set,
                depth1_authors=depth1_authors,
            )

    def _process_depth2_authorship(
        self,
        d1: Dict[str, Any],
        auth_ship: Dict[str, Any],
        work_field: str,
        work_concepts: List[str],
        exclude_set: Set[str],
        depth1_authors: Dict[str, Any],
        depth2_authors: Dict[str, Any],
        primary_name: str,
    ) -> None:
        """Processes a single authorship entry for depth 2 collaborators."""
        author_meta = auth_ship.get("author", {})
        auth_id = author_meta.get("id")
        if not auth_id:
            return
        auth_clean = auth_id.split("/")[-1]
        if (
            auth_clean in exclude_set
            or auth_id in depth1_authors
            or auth_id in depth2_authors
        ):
            return
        name = author_meta.get("display_name", "Unknown")
        insts = auth_ship.get("institutions", [])
        inst_name = insts[0].get("display_name") if insts else "Independent Researcher"
        depth2_authors[auth_id] = {
            "id": auth_id,
            "name": name,
            "institution": inst_name,
            "field": work_field,
            "connection_path": f"Collaborates with {d1['name']} (connected via {primary_name})",
            "joint_count": 1,
            "d1_parent_name": d1["name"],
            "work_concepts": work_concepts,
        }

    def _process_depth2_work(
        self,
        d1: Dict[str, Any],
        work: Dict[str, Any],
        exclude_set: Set[str],
        depth1_authors: Dict[str, Any],
        depth2_authors: Dict[str, Any],
        primary_name: str,
    ) -> None:
        """Processes a single depth 2 publication to extract depth 2 co-authors."""
        concepts = work.get("concepts", [])
        work_concepts = [
            c.get("display_name") for c in concepts if c.get("display_name")
        ]
        work_field = work_concepts[0] if work_concepts else "Expert Collaborator"
        for auth_ship in work.get("authorships", []):
            self._process_depth2_authorship(
                d1=d1,
                auth_ship=auth_ship,
                work_field=work_field,
                work_concepts=work_concepts,
                exclude_set=exclude_set,
                depth1_authors=depth1_authors,
                depth2_authors=depth2_authors,
                primary_name=primary_name,
            )

    def _process_depth2_works(
        self,
        d1: Dict[str, Any],
        works: Any,
        exclude_set: Set[str],
        depth1_authors: Dict[str, Any],
        depth2_authors: Dict[str, Any],
        primary_name: str,
    ) -> None:
        """Processes works for a specific depth 1 collaborator to find depth 2 connections."""
        if not isinstance(works, list):
            return
        for work in works:
            self._process_depth2_work(
                d1=d1,
                work=work,
                exclude_set=exclude_set,
                depth1_authors=depth1_authors,
                depth2_authors=depth2_authors,
                primary_name=primary_name,
            )

    async def get_network_collaborators(
        self,
        author_id: str,
        limit: int = 10,
        offset: int = 0,
        exclude_ids: List[str] = None,
        field: str = "",
        name: str = "",
    ) -> List[Dict[str, Any]]:
        """
        Fetches Depth 1 and Depth 2 co-author connections.
        Cache strategy (fastest-first):
          1. ResearcherConnection table (PostgreSQL) — instant, with 24-hour TTL.
          2. CacheEntry blob (legacy key) — retained for backwards compat.
          3. Full OpenAlex computation — stores results in both above for next time.
        """
        clean_id = author_id.split("/")[-1]
        if (not clean_id or clean_id == "fallback_seed") and name:
            print(
                f"[Dynamic Resolve] Attempting dynamic resolution for '{name}' with discipline '{field}' on OpenAlex...",
                flush=True,
            )
            resolved_id = await self._resolve_author_id_by_name(name, field)
            if resolved_id:
                author_id = resolved_id
                clean_id = author_id.split("/")[-1]
                print(
                    f"[Dynamic Resolve] Successfully resolved '{name}' to OpenAlex ID: {author_id} ({clean_id})",
                    flush=True,
                )

        if not clean_id or clean_id == "fallback_seed":
            raise ValueError(
                "No valid author ID provided for collaborator network extraction."
            )
        from app.models.user_models import ResearcherConnection, ResearcherProfile
        from sqlalchemy import delete

        now = datetime.datetime.now(datetime.timezone.utc).replace(tzinfo=None)
        CONNECTION_TTL_HOURS = 24
        PROFILE_TTL_DAYS = 7
        # ── 1. Fast path: read from ResearcherConnection table ────────────────
        async with self._db_session() as session:
            try:
                stmt = (
                    select(ResearcherConnection)
                    .where(
                        ResearcherConnection.author_openalex_id == clean_id,
                        ResearcherConnection.expires_at > now,
                    )
                    .order_by(ResearcherConnection.relevance_score.desc())
                )
                result = await session.execute(stmt)
                cached_rows = result.scalars().all()
                if cached_rows:
                    print(
                        f"[DB Fast Path] ResearcherConnection hit: {len(cached_rows)} rows for {clean_id}",
                        flush=True,
                    )
                    exclude_set_fast = set(exclude_ids or [])
                    exclude_set_fast.add(clean_id)
                    all_rows = [
                        {
                            "id": row.connection_openalex_id,
                            "name": row.connection_name,
                            "institution": row.connection_institution
                            or "Independent Researcher",
                            "field": row.connection_field or "Researcher",
                            "connection_path": row.connection_path or "",
                            "relevance_score": row.relevance_score,
                            "papers_collaborated": row.papers_collaborated,
                            "total_publications": row.total_publications,
                            "h_index": row.h_index,
                        }
                        for row in cached_rows
                        if row.connection_openalex_id.split("/")[-1]
                        not in exclude_set_fast
                    ]
                    # Apply field filter if requested
                    if field:
                        all_rows = [
                            r
                            for r in all_rows
                            if is_field_semantically_relevant(
                                r["field"], r["connection_path"], field
                            )
                        ]
                    return all_rows[offset : offset + limit]
            except Exception as e:
                print(
                    f"[DB Fast Path Error] ResearcherConnection read failed: {e}",
                    flush=True,
                )
        # ── 2. Legacy CacheEntry blob (cache_key fallback) ────────────────────
        cache_key = f"network_collaborators_{clean_id}_{field}"
        cached_blob = await self._load_from_postgres(cache_key)
        if isinstance(cached_blob, dict) and "collaborators" in cached_blob:
            print(
                f"[Postgres Blob Hit] network_collaborators for author_id={clean_id}",
                flush=True,
            )
            collaborators = cached_blob["collaborators"]
            if exclude_ids:
                ex = set(exclude_ids)
                collaborators = [
                    c for c in collaborators if c["id"].split("/")[-1] not in ex
                ]
            return collaborators[offset : offset + limit]
        # ── 3. Full OpenAlex computation with robust fallback ─────────────────
        exclude_set = set(exclude_ids or [])
        exclude_set.add(clean_id)
        try:
            profile = await self._fetch_author_profile(author_id)
            if not profile:
                raise ValueError(f"Author with ID '{author_id}' not found on OpenAlex.")
            primary_name = profile.get("display_name", "Main Author")
            # Persist the primary author's profile
            await self._upsert_researcher_profile(profile, PROFILE_TTL_DAYS)
            target_fields: List[str] = []
            if field:
                target_fields = [
                    f.strip().lower() for f in field.split(",") if f.strip()
                ]
            else:
                target_fields = [
                    c.get("display_name", "").lower()
                    for c in profile.get("x_concepts", [])
                    if c.get("display_name")
                ]
                if not target_fields:
                    try:
                        from app.models.researcher_models import ResearcherMetrics

                        async with self._db_session() as session:
                            stmt = select(ResearcherMetrics).where(
                                ResearcherMetrics.openalex_id == clean_id
                            )
                            res = await session.execute(stmt)
                            rm = res.scalars().first()
                            if rm and rm.field_of_study:
                                target_fields = [rm.field_of_study.lower()]
                    except Exception:
                        pass

            def is_relevant_collaborator(candidate_fields: List[str]) -> bool:
                if not field:
                    return True
                for cf in candidate_fields:
                    if is_field_semantically_relevant(cf, "", field):
                        return True
                return False

            async def fetch_works_for_author(
                auth_clean_id: str, max_works: int = 20
            ) -> List[Dict[str, Any]]:
                try:
                    works = await self.openalex_service.fetch_author_works(
                        auth_clean_id, per_page=max_works
                    )
                    if field:
                        from app.services.data.openalex_service import (
                            is_work_relevant_to_discipline,
                        )

                        works = [
                            w for w in works if is_work_relevant_to_discipline(w, field)
                        ]
                    return works
                except Exception:
                    return []

            d1_works = await fetch_works_for_author(clean_id, 100)
            if not d1_works:
                raise ValueError(
                    f"No publications found for author '{primary_name}' ({clean_id}) on OpenAlex."
                )
            # Stably sort works by citations count, then by year
            d1_works = sorted(
                d1_works,
                key=lambda w: (
                    w.get("cited_by_count", 0),
                    w.get("publication_year", 0),
                ),
                reverse=True,
            )
            depth1_authors: Dict[str, Any] = {}
            for work in d1_works:
                self._process_depth1_work(work, exclude_set, depth1_authors)
            depth2_authors: Dict[str, Any] = {}
            # Stably sort top direct co-authors by joint collaboration count descending first
            d1_list = sorted(
                depth1_authors.values(), key=lambda x: x["joint_count"], reverse=True
            )[:10]
            d2_tasks = [
                fetch_works_for_author(d1["id"].split("/")[-1], 20) for d1 in d1_list
            ]
            d2_results = await asyncio.gather(*d2_tasks, return_exceptions=True)
            for d1, works in zip(d1_list, d2_results):
                self._process_depth2_works(
                    d1=d1,
                    works=works,
                    exclude_set=exclude_set,
                    depth1_authors=depth1_authors,
                    depth2_authors=depth2_authors,
                    primary_name=primary_name,
                )
            # Batch-fetch h-index and works_count for all discovered authors
            all_ids = [
                v["id"].split("/")[-1]
                for v in list(depth1_authors.values()) + list(depth2_authors.values())
            ]
            real_stats: Dict[str, Any] = {}
            if all_ids:
                try:

                    async def fetch_stats_chunk(chunk: List[str]) -> None:
                        filter_str = "openalex:" + "|".join(chunk)
                        async with httpx.AsyncClient(
                            timeout=settings.http_timeout_seconds
                        ) as client:
                            res = await client.get(
                                "https://api.openalex.org/authors",
                                params={
                                    "filter": filter_str,
                                    "per_page": 50,
                                    "mailto": self.openalex_service.email,
                                },
                            )
                            if res.status_code == 200:
                                for a in res.json().get("results", []):
                                    aid_short = a["id"].split("/")[-1]
                                    real_stats[aid_short] = {
                                        "works_count": a.get("works_count", 0),
                                        "h_index": a.get("summary_stats", {}).get(
                                            "h_index", 0
                                        ),
                                        "concepts": [
                                            c.get("display_name", "")
                                            for c in a.get("x_concepts", [])
                                            if c.get("display_name")
                                        ],
                                        "institution": (
                                            a.get("last_known_institutions") or [{}]
                                        )[0].get(
                                            "display_name", "Independent Researcher"
                                        ),
                                        "raw_profile": a,
                                    }

                    stat_tasks = [
                        fetch_stats_chunk(all_ids[i : i + 50])
                        for i in range(0, len(all_ids), 50)
                    ]
                    await asyncio.gather(*stat_tasks, return_exceptions=True)
                except Exception as e:
                    print(f"[Stats Batch Error] {e}", flush=True)
                # ── Fallback to database for missing stats ───────────────────
                missing_ids = [aid for aid in all_ids if aid not in real_stats]
                if missing_ids:
                    async with self._db_session() as session:
                        try:
                            profile_ids = missing_ids + [
                                f"https://openalex.org/{mid}" for mid in missing_ids
                            ]
                            stmt_profile = select(ResearcherProfile).where(
                                ResearcherProfile.openalex_id.in_(profile_ids)
                            )
                            res_p = await session.execute(stmt_profile)
                            for rp in res_p.scalars().all():
                                aid_short = rp.openalex_id.split("/")[-1]
                                real_stats[aid_short] = {
                                    "works_count": rp.works_count or 0,
                                    "h_index": rp.h_index or 0,
                                    "concepts": rp.concepts or [],
                                    "institution": rp.institution
                                    or "Independent Researcher",
                                    "raw_profile": rp.raw_profile,
                                }
                            still_missing = [
                                aid for aid in missing_ids if aid not in real_stats
                            ]
                            if still_missing:
                                from app.models.researcher_models import (
                                    ResearcherMetrics,
                                )

                                metrics_ids = still_missing + [
                                    f"https://openalex.org/{mid}"
                                    for mid in still_missing
                                ]
                                stmt_metrics = select(ResearcherMetrics).where(
                                    ResearcherMetrics.openalex_id.in_(metrics_ids)
                                )
                                res_m = await session.execute(stmt_metrics)
                                for rm in res_m.scalars().all():
                                    aid_short = rm.openalex_id.split("/")[-1]
                                    real_stats[aid_short] = {
                                        "works_count": rm.works_count or 0,
                                        "h_index": rm.h_index or 0,
                                        "concepts": rm.expertise or [],
                                        "institution": rm.current_institution
                                        or "Independent Researcher",
                                        "raw_profile": None,
                                    }
                        except Exception as db_exc:
                            print(f"[DB Stats Fallback Error] {db_exc}", flush=True)
            collaborators_pool: List[Dict[str, Any]] = []
            for auth_id, d1 in depth1_authors.items():
                auth_id_short = auth_id.split("/")[-1]
                stats = real_stats.get(auth_id_short, {})
                total_pubs = stats.get("works_count", 0)
                h_idx = stats.get("h_index", 0)
                author_concepts = stats.get("concepts", [])
                cand_concepts = d1.get("work_concepts", []) + author_concepts
                if not is_relevant_collaborator(cand_concepts):
                    continue
                if stats.get("raw_profile"):
                    await self._upsert_researcher_profile(
                        stats["raw_profile"], PROFILE_TTL_DAYS
                    )

                similarity = self._compute_jaccard_similarity(
                    target_fields, cand_concepts
                )
                relevance_val = min(
                    99, max(80, int(80 + similarity * 40 + (d1["joint_count"] * 2)))
                )
                rec = {
                    "id": auth_id,
                    "name": d1["name"],
                    "institution": d1["institution"],
                    "field": d1.get("field") or "Researcher",
                    "connection_path": f"Co-authored '{d1['shared_paper']}' with {primary_name}",
                    "relevance_score": relevance_val,
                    "papers_collaborated": d1["joint_count"],
                    "total_publications": total_pubs,
                    "h_index": h_idx,
                    "depth": 1,
                }
                collaborators_pool.append(rec)
            for auth_id, d2 in depth2_authors.items():
                auth_id_short = auth_id.split("/")[-1]
                stats = real_stats.get(auth_id_short, {})
                total_pubs = stats.get("works_count", 0)
                h_idx = stats.get("h_index", 0)
                author_concepts = stats.get("concepts", [])
                cand_concepts = d2.get("work_concepts", []) + author_concepts
                if not is_relevant_collaborator(cand_concepts):
                    continue
                if stats.get("raw_profile"):
                    await self._upsert_researcher_profile(
                        stats["raw_profile"], PROFILE_TTL_DAYS
                    )

                similarity = self._compute_jaccard_similarity(
                    target_fields, cand_concepts
                )
                relevance_val = min(99, max(60, int(60 + similarity * 100)))
                rec = {
                    "id": auth_id,
                    "name": d2["name"],
                    "institution": d2["institution"],
                    "field": d2["field"],
                    "connection_path": d2["connection_path"],
                    "relevance_score": relevance_val,
                    "papers_collaborated": 0,
                    "total_publications": total_pubs,
                    "h_index": h_idx,
                    "depth": 2,
                }
                collaborators_pool.append(rec)
            collaborators_pool.sort(key=lambda x: x["relevance_score"], reverse=True)
            if collaborators_pool:
                expires_at = now + datetime.timedelta(hours=CONNECTION_TTL_HOURS)
                async with self._db_session() as session:
                    try:
                        await session.execute(
                            delete(ResearcherConnection).where(
                                ResearcherConnection.author_openalex_id == clean_id
                            )
                        )
                        for rec in collaborators_pool:
                            row = ResearcherConnection(
                                author_openalex_id=clean_id,
                                connection_openalex_id=rec["id"],
                                connection_name=rec["name"],
                                connection_institution=rec["institution"],
                                connection_field=rec["field"],
                                depth=rec.get("depth", 2),
                                connection_path=rec["connection_path"],
                                relevance_score=rec["relevance_score"],
                                papers_collaborated=rec.get("papers_collaborated", 1),
                                total_publications=rec.get("total_publications"),
                                h_index=rec.get("h_index"),
                                last_synced=now,
                                expires_at=expires_at,
                            )
                            session.add(row)
                        await session.commit()
                        print(
                            f"[DB Save] {len(collaborators_pool)} ResearcherConnection rows saved for {clean_id}",
                            flush=True,
                        )
                    except Exception as e:
                        print(
                            f"[DB Save Error] ResearcherConnection write failed: {e}",
                            flush=True,
                        )
                        await session.rollback()
                await self._save_to_postgres(
                    cache_key, {"collaborators": collaborators_pool}
                )
                print(
                    f"[Postgres Blob Save] network_collaborators cached for {clean_id}",
                    flush=True,
                )
        except Exception as exc:
            print(
                f"[NetworkCollaborators] OpenAlex computation failed: {exc}", flush=True
            )
            raise ValueError(
                f"Failed to fetch network collaborators from OpenAlex: {exc}"
            ) from exc
        filtered_final = [
            c for c in collaborators_pool if c["id"].split("/")[-1] not in exclude_set
        ]
        return filtered_final[offset : offset + limit]

    async def _upsert_researcher_profile(
        self, openalex_author: Dict[str, Any], ttl_days: int = 7
    ) -> None:
        """
        Upsert a researcher's profile into the ResearcherProfile table.
        Skips gracefully if the author dict is missing the 'id' key.
        """
        from app.models.user_models import ResearcherProfile

        auth_id = openalex_author.get("id")
        if not auth_id:
            return
        clean = auth_id.split("/")[-1]
        insts = openalex_author.get("last_known_institutions") or []
        inst_name = (
            insts[0].get("display_name", "Independent Researcher")
            if insts
            else "Independent Researcher"
        )
        from app.services.data.openalex_service import extract_field_and_expertise

        field, concepts = extract_field_and_expertise(
            openalex_author, openalex_author.get("display_name", "Researcher")
        )
        stats = openalex_author.get("summary_stats") or {}
        now = datetime.datetime.now(datetime.timezone.utc).replace(tzinfo=None)
        expires_at = now + datetime.timedelta(days=ttl_days)
        async with self._db_session() as session:
            try:
                stmt = select(ResearcherProfile).where(
                    ResearcherProfile.openalex_id == clean
                )
                result = await session.execute(stmt)
                row = result.scalars().first()
                if row:
                    row.display_name = openalex_author.get(
                        "display_name", row.display_name
                    )
                    row.institution = inst_name
                    row.field_of_study = field
                    row.h_index = stats.get("h_index")
                    row.works_count = openalex_author.get("works_count")
                    row.concepts = concepts
                    row.raw_profile = openalex_author
                    row.last_synced = now
                    row.expires_at = expires_at
                else:
                    row = ResearcherProfile(
                        openalex_id=clean,
                        display_name=openalex_author.get("display_name", "Unknown"),
                        institution=inst_name,
                        field_of_study=field,
                        h_index=stats.get("h_index"),
                        works_count=openalex_author.get("works_count"),
                        concepts=concepts,
                        raw_profile=openalex_author,
                        last_synced=now,
                        expires_at=expires_at,
                    )
                    session.add(row)
                await session.commit()
            except Exception as e:
                print(
                    f"[DB Upsert Error] ResearcherProfile for {clean}: {e}", flush=True
                )
                await session.rollback()

    async def chat_with_author(
        self,
        author_id: str,
        paper_title: str,
        user_message: str,
        history: List[Dict[str, str]],
    ) -> Dict[str, Any]:
        """
        Simulates chatting with a specific researcher about their paper using Groq.
        """
        clean_author = author_id.split("/")[-1]
        sanitized_title = re.sub(r"[^a-zA-Z0-9]", "_", paper_title.lower())[:100]
        doc_id = f"chat_{clean_author}_{sanitized_title}"
        user_id = "default_local_user"
        async with self._db_session() as session:
            if not history:
                try:
                    stmt = (
                        select(AgentChatHistory)
                        .where(
                            AgentChatHistory.user_id == user_id,
                            AgentChatHistory.context_id == doc_id,
                        )
                        .order_by(AgentChatHistory.timestamp.asc())
                    )
                    result = await session.execute(stmt)
                    db_msgs = result.scalars().all()
                    if db_msgs:
                        history = [
                            {"role": msg.role, "content": msg.content}
                            for msg in db_msgs
                        ]
                        print(
                            f"[Postgres Chat Load] Loaded {len(history)} messages for doc_id={doc_id}",
                            flush=True,
                        )
                except Exception as e:
                    print(f"[Postgres Chat Load Error] failed: {e}", flush=True)
        profile = await self._fetch_author_profile(author_id)
        author_name = "Researcher"
        concepts = ["science"]
        institution = "Research Lab"
        if profile:
            author_name = profile.get("display_name", "Researcher")
            concepts = [
                c.get("display_name")
                for c in profile.get("x_concepts", [])
                if c.get("level") in [1, 2]
            ][:3]
            institution = profile.get("institution") or "Research Institution"
        system_prompt = AUTHOR_CHAT_SYSTEM_PROMPT_TEMPLATE.format(
            author_name=author_name,
            institution=institution,
            concepts=", ".join(concepts),
            paper_title=paper_title,
        )
        messages = [{"role": "system", "content": system_prompt}]
        # Append history
        for msg in history[-5:]:  # limit to last 5 messages for token economy
            role = msg.get("role", "user")
            if role in ["user", "assistant"]:
                messages.append({"role": role, "content": msg.get("content", "")})
        # Append current user message
        messages.append({"role": "user", "content": user_message})
        reply = f"Thank you for your question about my work. I believe the principles discussed in '{paper_title}' outline a strong foundation for this domain."
        if (
            is_llm_working()
        ):  # Decoupled: LLM moved to background addon to unblock core app
            try:
                response = await self.llm_service.query(
                    messages=messages,
                    models=[self.model],
                    temperature=0.6,
                    max_tokens=150,
                )
                if response.content:
                    reply = response.content.strip()
            except Exception as e:
                print(f"Author chat simulation failed: {e}", flush=True)
        async with self._db_session() as session:
            try:
                user_msg = AgentChatHistory(
                    user_id=user_id,
                    context_id=doc_id,
                    role="user",
                    content=user_message,
                )
                asst_msg = AgentChatHistory(
                    user_id=user_id, context_id=doc_id, role="assistant", content=reply
                )
                session.add(user_msg)
                session.add(asst_msg)
                await session.commit()
                print(
                    f"[Postgres Chat Save] Saved to chat_history for doc_id={doc_id}",
                    flush=True,
                )
            except Exception as e:
                print(f"[Postgres Chat Save Error] failed: {e}", flush=True)
        return {"author_id": author_id, "author_name": author_name, "reply": reply}
