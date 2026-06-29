import httpx
import asyncio
import defusedxml.ElementTree as ET
from typing import List, Dict, Any, Optional
from app.core.config import settings


class PhysicsResearcherFetcher:
    """
    Utility to fetch metadata for physics researchers using ArXiv and Semantic Scholar APIs.
    Note: 'All researchers in the world' is a massive scope; this provides a robust
    framework to crawl and aggregate data starting from ArXiv.
    """

    ARXIV_API_URL = "http://export.arxiv.org/api/query"
    SEMANTIC_SCHOLAR_API_URL = "https://api.semanticscholar.org/graph/v1"

    def __init__(self):
        self.client = httpx.AsyncClient(timeout=settings.http_timeout_seconds)
        self.seen_orcid = set()
        self.seen_author_ids = set()  # Semantic Scholar Author IDs

    async def search_arxiv_physicists(
        self, query: str = "cat:physics*", max_results: int = 50
    ) -> List[str]:
        """
        Search ArXiv for recent physics papers and extract author names.
        """
        params = {
            "search_query": query,
            "start": 0,
            "max_results": max_results,
            "sortBy": "submittedDate",
            "sortOrder": "descending",
        }

        response = await self.client.get(self.ARXIV_API_URL, params=params)
        if response.status_code != 200:
            return []

        root = ET.fromstring(response.text)
        authors = []
        # ArXiv XML uses Atom namespace
        ns = {"atom": "http://www.w3.org/2005/Atom"}

        for entry in root.findall("atom:entry", ns):
            for author in entry.findall("atom:author", ns):
                name = author.find("atom:name", ns).text
                if name not in authors:
                    authors.append(name)

        return authors

    async def get_researcher_details(
        self, author_name: str
    ) -> Optional[Dict[str, Any]]:
        """
        Fetch detailed metadata for a specific author using Semantic Scholar.
        """
        # Search for author to get their ID
        search_url = f"{self.SEMANTIC_SCHOLAR_API_URL}/author/search"
        params = {
            "query": author_name,
            "fields": "name,externalIds,affiliations,homepage,papers.title,papers.year,papers.externalIds",
            "limit": 1,
        }

        try:
            response = await self.client.get(search_url, params=params)
            if response.status_code != 200:
                return None

            data = response.json()
            if not data.get("data"):
                return None

            author_data = data["data"][0]
            author_id = author_data.get("authorId")

            if author_id in self.seen_author_ids:
                return None  # Duplicate

            self.seen_author_ids.add(author_id)

            # Process Papers (Last 5)
            all_papers = author_data.get("papers", [])
            # Sort by year descending if possible (Semantic Scholar might not return sorted)
            recent_papers = sorted(
                all_papers, key=lambda x: x.get("year") or 0, reverse=True
            )[:5]

            name = author_data.get("name") or ""
            if not name.strip() or name.lower().strip() in ["unknown", "anonymous"]:
                return None

            affiliations = author_data.get("affiliations") or []
            if not affiliations or not any(
                aff
                for aff in affiliations
                if aff and aff.strip().lower() not in ["unknown", "none"]
            ):
                return None

            return {
                "name": name,
                "semantic_scholar_id": author_id,
                "orcid": author_data.get("externalIds", {}).get("ORCID"),
                "institution": affiliations[0],
                "last_5_articles": [p.get("title") for p in recent_papers],
                "academic_history": affiliations,  # Simplified history
                "homepage": author_data.get("homepage"),
            }
        except Exception as e:
            print(f"Error fetching details for {author_name}: {e}")
            return None

    async def fetch_batch(self, count: int = 20):
        """
        Orchestrates the fetching process.
        """
        print(f"Starting to fetch {count} physicists...")
        names = await self.search_arxiv_physicists(max_results=count)

        results = []
        for name in names:
            detail = await self.get_researcher_details(name)
            if detail:
                results.append(detail)
                print(f"Fetched: {detail['name']} ({detail['institution']})")

            # Rate limiting safety for Semantic Scholar
            await asyncio.sleep(1)

        return results


async def main():
    fetcher = PhysicsResearcherFetcher()
    data = await fetcher.fetch_batch(10)
    print(f"\nCollected {len(data)} unique researchers.")
    # In a real scenario, you'd save this to a DB
    for r in data:
        print("---")
        print(f"Name: {r['name']}")
        print(f"Inst: {r['institution']}")
        print(f"Recent: {r['last_5_articles']}")


if __name__ == "__main__":
    asyncio.run(main())
