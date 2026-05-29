import httpx
from typing import List, Dict, Optional, Any
from app.core.config import settings

class OpenAlexService:
    """
    Consolidated service mapping API calls and results from the OpenAlex database.
    """
    def __init__(self):
        self.base_url = "https://api.openalex.org"
        self.email = settings.openalex_email or "support@skolab.open"
        self.headers = {
            "User-Agent": f"SkolabApp/1.0 (mailto:{self.email})",
            "Accept": "application/json"
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
        """
        clean_id = author_id.split("/")[-1]
        url = f"{self.base_url}/authors/{clean_id}"
        params = {"mailto": self.email}
        try:
            async with httpx.AsyncClient(timeout=15.0) as client:
                res = await client.get(url, params=params, headers=self.headers)
                if res.status_code == 200:
                    return res.json()
        except Exception as e:
            print(f"[OpenAlexService] Error fetching author {clean_id}: {e}", flush=True)
        return None

    async def search_authors(self, query: str, per_page: int = 10) -> List[Dict[str, Any]]:
        """
        Searches OpenAlex authors by name or query term.
        """
        url = f"{self.base_url}/authors"
        params = {
            "search": query,
            "per_page": per_page,
            "mailto": self.email
        }
        try:
            async with httpx.AsyncClient(timeout=15.0) as client:
                res = await client.get(url, params=params, headers=self.headers)
                if res.status_code == 200:
                    return res.json().get("results", [])
        except Exception as e:
            print(f"[OpenAlexService] Error searching authors for query '{query}': {e}", flush=True)
        return []

    async def fetch_author_works(
        self, 
        author_id: str, 
        orcid: Optional[str] = None, 
        per_page: int = 50,
        sort: str = "publication_year:desc"
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
            "mailto": self.email
        }
        try:
            async with httpx.AsyncClient(timeout=15.0) as client:
                res = await client.get(url, params=params, headers=self.headers)
                if res.status_code == 200:
                    return res.json().get("results", [])
        except Exception as e:
            print(f"[OpenAlexService] Error fetching works for author {clean_id}: {e}", flush=True)
        return []

    async def search_works(self, query: str, per_page: int = 20) -> List[Dict[str, Any]]:
        """
        Searches OpenAlex works by keywords.
        """
        url = f"{self.base_url}/works"
        params = {
            "search": query,
            "per_page": per_page,
            "sort": "publication_year:desc,cited_by_count:desc",
            "mailto": self.email
        }
        try:
            async with httpx.AsyncClient(timeout=15.0) as client:
                res = await client.get(url, params=params, headers=self.headers)
                if res.status_code == 200:
                    return res.json().get("results", [])
        except Exception as e:
            print(f"[OpenAlexService] Error searching works for query '{query}': {e}", flush=True)
        return []

    async def fetch_works_by_concept(
        self, 
        concept_id: str, 
        prev_year: int, 
        now_year: int, 
        per_page: int = 15
    ) -> List[Dict[str, Any]]:
        """
        Fetches works filtered by OpenAlex concept ID published since prev_year,
        sorted by citation count descending.
        Uses `from_publication_date` for a continuous range instead of OR-filter.
        """
        url = f"{self.base_url}/works"
        params = {
            # from_publication_date gives a true '>= date' range filter in OpenAlex
            "filter": f"concepts.id:{concept_id},from_publication_date:{prev_year}-01-01",
            "sort": "cited_by_count:desc",
            "per_page": per_page,
            "mailto": self.email
        }
        try:
            async with httpx.AsyncClient(timeout=15.0) as client:
                res = await client.get(url, params=params, headers=self.headers)
                if res.status_code == 200:
                    return res.json().get("results", [])
        except Exception as e:
            print(f"[OpenAlexService] Error fetching works by concept {concept_id}: {e}", flush=True)
        return []

    async def search_funders(self, query: str, per_page: int = 5) -> List[Dict[str, Any]]:
        """
        Searches OpenAlex funders by query/focus.
        """
        url = f"{self.base_url}/funders"
        params = {
            "search": query,
            "per_page": per_page,
            "sort": "cited_by_count:desc",
            "mailto": self.email
        }
        try:
            async with httpx.AsyncClient(timeout=15.0) as client:
                res = await client.get(url, params=params, headers=self.headers)
                if res.status_code == 200:
                    return res.json().get("results", [])
        except Exception as e:
            print(f"[OpenAlexService] Error searching funders for query '{query}': {e}", flush=True)
        return []


