import httpx
from typing import List, Dict, Optional, Any
from app.core.config import settings
from app.core.circuit_breaker import openalex_breaker, CircuitBreakerOpenError


def extract_field_and_expertise(
    author_data: dict, default_name: str = "Researcher"
) -> tuple[str, list[str]]:
    # Get topics first
    topics = author_data.get("topics") or []
    field = None
    expertise = []

    if topics and isinstance(topics, list):
        for t in topics:
            if isinstance(t, dict) and t.get("display_name"):
                expertise.append(t.get("display_name"))
        # Try to get the field from topics[0]["field"]["display_name"]
        if topics:
            first_topic = topics[0]
            if isinstance(first_topic, dict):
                field_obj = (
                    first_topic.get("field")
                    or first_topic.get("subfield")
                    or first_topic.get("domain")
                )
                if isinstance(field_obj, dict):
                    field = field_obj.get("display_name")
                if not field:
                    field = first_topic.get("display_name")

    # Fall back/clean up with concepts
    concepts = author_data.get("x_concepts") or []
    display_name = author_data.get("display_name") or default_name
    a_words = {w.strip().lower() for w in display_name.split() if len(w.strip()) > 2}

    valid_concepts = []
    for c in concepts:
        c_name = c.get("display_name") or ""
        c_words = {w.strip().lower() for w in c_name.split() if len(w.strip()) > 2}
        if c_words and c_words.issubset(a_words):
            continue
        valid_concepts.append(c)

    if not field:
        field = next(
            (c.get("display_name") for c in valid_concepts if c.get("level") == 1),
            valid_concepts[0].get("display_name")
            if valid_concepts
            else "Multidisciplinary",
        )

    if not expertise:
        expertise = [c.get("display_name") for c in valid_concepts][:6]

    return field, expertise


class OpenAlexService:
    """
    Consolidated service mapping API calls and results from the OpenAlex database.
    """

    def __init__(self):
        self.base_url = "https://api.openalex.org"
        self.email = settings.openalex_email or "support@skolab.open"
        self.headers = {
            "User-Agent": f"SkolabApp/1.0 (mailto:{self.email})",
            "Accept": "application/json",
        }
        if settings.openalex_api_key:
            self.headers["api_key"] = settings.openalex_api_key

    def get_headers(self) -> dict:
        """
        Returns consolidated HTTP headers for OpenAlex endpoints.
        """
        return self.headers

    async def fetch_author_by_id(self, author_id: str) -> Optional[Dict[str, Any]]:
        """
        Retrieves a researcher profile by their OpenAlex ID.
        Wrapped with circuit breaker — open after 5 consecutive failures, probes after 30s.
        """
        clean_id = author_id.split("/")[-1]
        url = f"{self.base_url}/authors/{clean_id}"
        params = {"mailto": self.email}
        try:
            await openalex_breaker._check_state()
            async with httpx.AsyncClient(
                timeout=settings.http_timeout_seconds
            ) as client:
                res = await client.get(url, params=params, headers=self.headers)
                if res.status_code == 200:
                    await openalex_breaker._on_success()
                    return res.json()
                else:
                    await openalex_breaker._on_failure(
                        Exception(f"HTTP {res.status_code}")
                    )
        except CircuitBreakerOpenError:
            raise
        except Exception as e:
            await openalex_breaker._on_failure(e)
            print(
                f"[OpenAlexService] Error fetching author {clean_id}: {e}", flush=True
            )
        return None

    async def search_authors(
        self, query: str, per_page: int = 10
    ) -> List[Dict[str, Any]]:
        """
        Searches OpenAlex authors by name or query term.
        """
        url = f"{self.base_url}/authors"
        params = {"search": query, "per_page": per_page, "mailto": self.email}
        try:
            await openalex_breaker._check_state()
            async with httpx.AsyncClient(
                timeout=settings.http_timeout_seconds
            ) as client:
                res = await client.get(url, params=params, headers=self.headers)
                if res.status_code == 200:
                    await openalex_breaker._on_success()
                    return res.json().get("results", [])
                else:
                    await openalex_breaker._on_failure(
                        Exception(f"HTTP {res.status_code}")
                    )
        except CircuitBreakerOpenError:
            raise
        except Exception as e:
            await openalex_breaker._on_failure(e)
            print(
                f"[OpenAlexService] Error searching authors for query '{query}': {e}",
                flush=True,
            )
        return []

    async def fetch_author_works(
        self,
        author_id: str,
        orcid: Optional[str] = None,
        per_page: int = 50,
        sort: str = "publication_year:desc",
    ) -> List[Dict[str, Any]]:
        """
        Fetches publication works list linked to an author ID or ORCID.
        """
        clean_id = author_id.split("/")[-1]
        filter_str = f"authorships.author.id:{clean_id}"
        if orcid:
            filter_str = f"authorships.author.orcid:{orcid}"

        url = f"{self.base_url}/works"
        params = {
            "filter": filter_str,
            "per_page": per_page,
            "sort": sort,
            "mailto": self.email,
        }
        try:
            async with httpx.AsyncClient(
                timeout=settings.http_timeout_seconds
            ) as client:
                res = await client.get(url, params=params, headers=self.headers)
                if res.status_code == 200:
                    return res.json().get("results", [])
        except Exception as e:
            print(
                f"[OpenAlexService] Error fetching works for author {clean_id}: {e}",
                flush=True,
            )
        return []

    async def fetch_related_works(
        self,
        work_id: str,
        per_page: int = 15,
        sort: Optional[str] = None,
    ) -> List[Dict[str, Any]]:
        """
        Fetches works related to a specific work ID using the related_to filter.
        """
        clean_id = work_id.split("/")[-1]
        url = f"{self.base_url}/works"
        params = {
            "filter": f"related_to:{clean_id}",
            "per_page": per_page,
            "sort": sort or "publication_date:desc",
            "mailto": self.email,
        }
        try:
            async with httpx.AsyncClient(
                timeout=settings.http_timeout_seconds
            ) as client:
                res = await client.get(url, params=params, headers=self.headers)
                if res.status_code == 200:
                    return res.json().get("results", [])
        except Exception as e:
            print(
                f"[OpenAlexService] Error fetching related works for work {clean_id}: {e}",
                flush=True,
            )
        return []

    async def search_works(
        self, query: str, per_page: int = 20, sort: Optional[str] = None
    ) -> List[Dict[str, Any]]:
        """
        Searches OpenAlex works by keywords.
        """
        url = f"{self.base_url}/works"
        params = {
            "search": query,
            "per_page": per_page,
            "sort": sort or "publication_year:desc,cited_by_count:desc",
            "mailto": self.email,
        }
        try:
            async with httpx.AsyncClient(
                timeout=settings.http_timeout_seconds
            ) as client:
                res = await client.get(url, params=params, headers=self.headers)
                if res.status_code == 200:
                    return res.json().get("results", [])
        except Exception as e:
            print(
                f"[OpenAlexService] Error searching works for query '{query}': {e}",
                flush=True,
            )
        return []

    async def fetch_works_by_concept(
        self, concept_id: str, prev_year: int, now_year: int, per_page: int = 15
    ) -> List[Dict[str, Any]]:
        """
        Fetches works filtered by concept/topic ID published since prev_year.
        Tries topics.id first (new OpenAlex format), then concepts.id (legacy).
        """
        url = f"{self.base_url}/works"
        date_filter = f"from_publication_date:{prev_year}-01-01"
        # OpenAlex migrated from /concepts to /topics — try both filters
        filters_to_try = [
            f"topics.id:{concept_id},{date_filter}",
            f"concepts.id:{concept_id},{date_filter}",
        ]
        try:
            async with httpx.AsyncClient(
                timeout=settings.http_timeout_seconds
            ) as client:
                for filt in filters_to_try:
                    params = {
                        "filter": filt,
                        "sort": "cited_by_count:desc",
                        "per_page": per_page,
                        "mailto": self.email,
                    }
                    res = await client.get(url, params=params, headers=self.headers)
                    if res.status_code == 200:
                        results = res.json().get("results", [])
                        if results:
                            return results
        except Exception as e:
            print(
                f"[OpenAlexService] Error fetching works by concept {concept_id}: {e}",
                flush=True,
            )
        return []

    async def search_funders(
        self, query: str, per_page: int = 5
    ) -> List[Dict[str, Any]]:
        """
        Searches OpenAlex funders by query/focus.
        """
        url = f"{self.base_url}/funders"
        params = {
            "search": query,
            "per_page": per_page,
            "sort": "cited_by_count:desc",
            "mailto": self.email,
        }
        try:
            await openalex_breaker._check_state()
            async with httpx.AsyncClient(
                timeout=settings.http_timeout_seconds
            ) as client:
                res = await client.get(url, params=params, headers=self.headers)
                if res.status_code == 200:
                    await openalex_breaker._on_success()
                    return res.json().get("results", [])
                else:
                    await openalex_breaker._on_failure(
                        Exception(f"HTTP {res.status_code}")
                    )
        except CircuitBreakerOpenError:
            raise
        except Exception as e:
            await openalex_breaker._on_failure(e)
            print(
                f"[OpenAlexService] Error searching funders for query '{query}': {e}",
                flush=True,
            )
        return []


def is_work_relevant_to_discipline(work: dict, discipline: str) -> bool:
    """
    Meticulously checks if a work (publication) is relevant to the researcher's discipline
    to avoid incorrectly associating papers from different authors with the same name.
    """
    if not discipline:
        return True

    discipline_lower = discipline.lower().strip()
    if not discipline_lower or discipline_lower in [
        "general research",
        "researcher",
        "multidisciplinary",
        "general",
    ]:
        return True

    # Collect all concept and topic names for this work
    concepts = work.get("concepts") or work.get("x_concepts") or []
    topics = work.get("topics") or []

    work_keywords = set()
    for c in concepts:
        if isinstance(c, dict) and c.get("display_name"):
            work_keywords.add(c.get("display_name").lower())

    for t in topics:
        if isinstance(t, dict):
            if t.get("display_name"):
                work_keywords.add(t.get("display_name").lower())
            for field_key in ["field", "subfield", "domain"]:
                field_obj = t.get(field_key)
                if isinstance(field_obj, dict) and field_obj.get("display_name"):
                    work_keywords.add(field_obj.get("display_name").lower())

    # Also scan title and journal
    title = work.get("title") or ""
    if title:
        work_keywords.add(title.lower())

    # Primary locations / sources (journals)
    primary_location = work.get("primary_location") or {}
    source = primary_location.get("source") or {}
    journal = source.get("display_name") or ""
    if journal:
        work_keywords.add(journal.lower())

    # Also scan abstract if available
    abstract = work.get("_custom_abstract")
    if not abstract:
        abstract_index = work.get("abstract_inverted_index")
        if abstract_index:
            try:
                word_list = []
                for word, pos_list in abstract_index.items():
                    for pos in pos_list:
                        word_list.append((pos, word))
                word_list.sort()
                abstract = " ".join([w[1] for w in word_list])
            except Exception:
                pass
    if abstract:
        work_keywords.add(abstract.lower())

    # Extract root search terms from discipline
    discipline_terms = [t for t in discipline_lower.split() if len(t) > 2]
    if not discipline_terms:
        discipline_terms = [discipline_lower]

    # Map disciplines to related concepts to prevent clean false negatives
    # We use independent if statements to support multidisciplinary fields
    extended_terms = set(discipline_terms)
    if "phys" in discipline_lower or "quantum" in discipline_lower:
        extended_terms.update(
            [
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
            ]
        )
    if (
        "comput" in discipline_lower
        or "cs" in discipline_lower
        or "ai" in discipline_lower
        or "crypt" in discipline_lower
        or "secur" in discipline_lower
    ):
        extended_terms.update(
            [
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
                "crypt",
                "secur",
                "protocol",
                "key distribution",
            ]
        )
    if (
        "biochem" in discipline_lower
        or "bio" in discipline_lower
        or "crispr" in discipline_lower
        or "medic" in discipline_lower
        or "health" in discipline_lower
    ):
        extended_terms.update(
            [
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
                "medic",
                "health",
                "clinical",
            ]
        )
    if "chem" in discipline_lower:
        extended_terms.update(
            [
                "chem",
                "molec",
                "organ",
                "inorgan",
                "spectroscop",
                "synthes",
                "reaction",
                "cataly",
            ]
        )

    # Check if any keyword in the work matches any of the extended terms
    for kw in work_keywords:
        for term in extended_terms:
            if term in kw:
                return True

    return False
