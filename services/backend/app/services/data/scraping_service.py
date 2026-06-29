import json
import re
import httpx
import asyncio
import random
from html.parser import HTMLParser
from typing import List, Dict, Any, Optional
from app.prompts import JSON_PARSER_SYSTEM_PROMPT
from app.core.config import settings

# Rotating list of modern web browser User-Agent strings
USER_AGENTS = [
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:125.0) Gecko/20100101 Firefox/125.0",
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:125.0) Gecko/20100101 Firefox/125.0",
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Safari/605.1.15",
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36 Edg/124.0.0.0",
]


def get_random_user_agent() -> str:
    return random.choice(USER_AGENTS)


class HTMLTextExtractor(HTMLParser):
    """
    Standard library-based HTML parser to extract clean text from raw web pages,
    ignoring scripts, styles, metadata, and link blocks.
    """

    def __init__(self):
        super().__init__(
            convert_charrefs=True
        )  # converts &amp; &#x200c; etc automatically
        self.result = []
        self.ignore = False

    def handle_starttag(self, tag, attrs):
        if tag in ["script", "style", "head", "meta", "link", "noscript"]:
            self.ignore = True

    def handle_endtag(self, tag):
        if tag in ["script", "style", "head", "meta", "link", "noscript"]:
            self.ignore = False

    def handle_data(self, data):
        if not self.ignore:
            text = data.strip()
            if text:
                self.result.append(text)

    def get_text(self) -> str:
        # Join words with spaces, cleaning up excessive whitespace
        text_content = " ".join(self.result)
        return re.sub(r"\s+", " ", text_content).strip()


class ScrapingService:
    """
    Service encapsulating non-LLM scraping and searching, combined with
    LLM-based parsing of unstructured content into validated JSON.
    """

    def __init__(self):
        from app.services.ai.llm_service import LLMService

        self.llm_service = LLMService()
        self.model = "llama-3.3-70b-versatile"

    async def scrape_url(self, url: str) -> str:
        """
        Scrapes raw HTML content of a URL and extracts clean textual content.
        """
        # Inject rate limit delay
        await asyncio.sleep(0.5)
        headers = {
            "User-Agent": get_random_user_agent(),
            "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        }
        try:
            async with httpx.AsyncClient(
                timeout=settings.http_timeout_seconds, follow_redirects=True
            ) as client:
                res = await client.get(url, headers=headers)
                if res.status_code == 200:
                    extractor = HTMLTextExtractor()
                    extractor.feed(res.text)
                    return extractor.get_text()
                else:
                    raise Exception(
                        f"Failed to fetch URL. Status code: {res.status_code}"
                    )
        except Exception as e:
            print(f"[ScrapingService] Error scraping {url}: {e}", flush=True)
            raise e

    async def search_web(
        self, query: str, max_results: int = 5
    ) -> List[Dict[str, str]]:
        """
        Searches the live web using DuckDuckGo HTML and Instant Answer API.
        Returns a list of structured search results: [{"title": ..., "url": ..., "snippet": ...}].
        """
        # Inject rate limit delay
        await asyncio.sleep(0.5)
        url = f"https://html.duckduckgo.com/html/?q={query}"
        headers = {"User-Agent": get_random_user_agent()}
        results = []

        try:
            async with httpx.AsyncClient(
                timeout=settings.http_timeout_seconds
            ) as client:
                res = await client.get(url, headers=headers)
                if res.status_code == 200 and "result__a" in res.text:
                    html = res.text
                    parts = html.split(
                        'class="result results_links results_links_deep web-result'
                    )
                    if len(parts) <= 1:
                        parts = html.split('class="web-result')

                    for part in parts[1 : max_results + 1]:
                        try:
                            title_part = part.split('class="result__a"')
                            if len(title_part) > 1:
                                link = title_part[1].split('href="')[1].split('"')[0]
                                if "uddg=" in link:
                                    from urllib.parse import unquote

                                    link = unquote(link.split("uddg=")[1].split("&")[0])

                                first_gt = title_part[1].find(">")
                                if first_gt != -1:
                                    title = title_part[1][first_gt + 1 :].split("</a")[
                                        0
                                    ]
                                    title = re.sub("<[^>]+?>", "", title).strip()
                                else:
                                    title = "Unknown"
                            else:
                                continue

                            snippet_part = part.split('class="result__snippet"')
                            if len(snippet_part) > 1:
                                first_gt = snippet_part[1].find(">")
                                if first_gt != -1:
                                    snippet = snippet_part[1][first_gt + 1 :].split(
                                        "</a"
                                    )[0]
                                    snippet = re.sub("<[^>]+?>", "", snippet).strip()
                                else:
                                    snippet = ""
                            else:
                                snippet = ""

                            results.append(
                                {"title": title, "url": link, "snippet": snippet}
                            )
                        except Exception:
                            continue
                    if results:
                        return results
        except Exception as e:
            print(f"[ScrapingService] DDG HTML search error: {e}", flush=True)

        # Fallback to DDG Instant Answer API
        try:
            api_url = f"https://api.duckduckgo.com/?q={query}&format=json&no_html=1&skip_disambig=1"
            async with httpx.AsyncClient(
                timeout=settings.http_timeout_seconds
            ) as client:
                res = await client.get(api_url, headers=headers)
                if res.status_code == 200:
                    data = res.json()
                    abstract = data.get("AbstractText", "")
                    abstract_url = data.get("AbstractURL", "")
                    if abstract:
                        results.append(
                            {
                                "title": "Abstract",
                                "url": abstract_url,
                                "snippet": abstract,
                            }
                        )

                    topics = data.get("RelatedTopics", [])
                    for topic in topics[:max_results]:
                        if "Text" in topic and "FirstURL" in topic:
                            results.append(
                                {
                                    "title": "Related Topic",
                                    "url": topic["FirstURL"],
                                    "snippet": topic["Text"],
                                }
                            )
                    return results
        except Exception as e:
            print(f"[ScrapingService] DDG API fallback search error: {e}", flush=True)

        return results

    async def search_portal(
        self, portal_name: str, portal_url: str
    ) -> Optional[Dict[str, str]]:
        """
        Fetches a research job portal's search results page directly.
        Returns {"name": portal_name, "url": portal_url, "text": cleaned_text} or None on failure.
        """
        # Inject rate limit delay
        await asyncio.sleep(0.5)
        headers = {
            "User-Agent": get_random_user_agent(),
            "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language": "en-US,en;q=0.9",
        }
        try:
            async with httpx.AsyncClient(
                timeout=settings.http_timeout_seconds, follow_redirects=True
            ) as client:
                res = await client.get(portal_url, headers=headers)
                if res.status_code == 200:
                    # Safely decode bytes — replace unencodable characters instead of crashing
                    try:
                        html_text = res.text  # httpx auto-detects encoding
                    except Exception:
                        html_text = res.content.decode("utf-8", errors="replace")
                    extractor = HTMLTextExtractor()
                    try:
                        extractor.feed(html_text)
                    except Exception:
                        # If feed() crashes on malformed HTML, try with sanitised input
                        safe_html = html_text.encode("ascii", errors="replace").decode(
                            "ascii"
                        )
                        extractor = HTMLTextExtractor()
                        extractor.feed(safe_html)
                    text = extractor.get_text()
                    if len(text) < 200:
                        print(
                            f"[ScrapingService] Portal '{portal_name}' returned too-short text ({len(text)} chars), skipping.",
                            flush=True,
                        )
                        return None
                    print(
                        f"[ScrapingService] Portal '{portal_name}' returned {len(text)} chars of text.",
                        flush=True,
                    )
                    return {"name": portal_name, "url": portal_url, "text": text}
                else:
                    print(
                        f"[ScrapingService] Portal '{portal_name}' returned status {res.status_code}.",
                        flush=True,
                    )
                    return None
        except Exception as e:
            print(
                f"[ScrapingService] Error fetching portal '{portal_name}' ({portal_url}): {e}",
                flush=True,
            )
            return None

    async def parse_content_to_json(
        self, raw_content: str, response_schema: dict, instruction: str = ""
    ) -> Dict[str, Any]:
        """
        Parses unstructured text/HTML content into a structured JSON dictionary
        using Groq's json_object output format.
        """
        # Truncate content to avoid token overflow (10 portals × 5000 chars each = 50k; keep 40k buffer)
        truncated_content = raw_content[:40000]

        system_prompt = JSON_PARSER_SYSTEM_PROMPT.format(
            schema=json.dumps(response_schema, indent=2),
            instruction=instruction or "Parse the document details logically.",
        )

        messages = [
            {"role": "system", "content": system_prompt},
            {
                "role": "user",
                "content": f"Document content to parse:\n\n{truncated_content}",
            },
        ]

        response = await self.llm_service.query(
            messages=messages,
            models=[self.model],
            temperature=0.1,
            response_format={"type": "json_object"},
        )

        if not response.content:
            raise Exception("LLM parsing failed to generate a response.")

        return json.loads(response.content.strip())
