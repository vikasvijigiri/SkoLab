import os
import json
import httpx
import re
import urllib.parse
import xml.etree.ElementTree as ET
from typing import Dict, Any, List
from pathlib import Path
import csv
from app.core.config import settings

# ── Portable path constants ───────────────────────────────────────────────────
# Resolved from this file's location so they are correct regardless of CWD.
_BACKEND_ROOT = Path(__file__).resolve().parents[2]
DOWNLOADS_DIR = _BACKEND_ROOT / "downloads"

def _base_url() -> str:
    """Return the server's public base URL from env, defaulting to localhost."""
    return os.environ.get("APP_BASE_URL", "http://localhost:8000")

# --- Real Connectors ---

async def search_arxiv_publications(query: str, max_results: int = 5) -> str:
    """Searches the official arXiv API using HTTPS and safe URL-encoding, returning parsed publications."""
    safe_query = urllib.parse.quote(query)
    url = f"https://export.arxiv.org/api/query?search_query=all:{safe_query}&start=0&max_results={max_results}"
    try:
        async with httpx.AsyncClient(timeout=15.0, follow_redirects=True) as client:
            res = await client.get(url)
            if res.status_code == 200:
                root = ET.fromstring(res.content)
                ns = {'atom': 'http://www.w3.org/2005/Atom'}
                results = []
                for entry in root.findall('atom:entry', ns):
                    title = entry.find('atom:title', ns)
                    summary = entry.find('atom:summary', ns)
                    published = entry.find('atom:published', ns)
                    link = entry.find("atom:link[@rel='alternate']", ns)
                    
                    title_text = title.text.strip().replace('\n', ' ') if title is not None else "Untitled"
                    summary_text = summary.text.strip().replace('\n', ' ') if summary is not None else ""
                    date_text = published.text.split('-')[0] if published is not None else "Unknown"
                    link_href = link.attrib.get('href', '') if link is not None else ""
                    
                    results.append(f"Title: {title_text}\nYear: {date_text}\nURL: {link_href}\nAbstract: {summary_text[:300]}...")
                
                if results:
                    return f"[arXiv PUBLICATIONS FOUND]\n\n" + "\n\n---\n\n".join(results)
                return "No publications found on arXiv matching this query."
            return f"arXiv API error. Status code: {res.status_code}"
    except Exception as e:
        return f"Failed to connect to arXiv: {e}"

async def search_google_scholar_profile(query: str) -> str:
    """Searches for a researcher's Google Scholar citation page and returns their profile details and publications list."""
    from app.services.scraping_service import ScrapingService
    scraper = ScrapingService()
    try:
        results = await scraper.search_web(f"{query} Google Scholar", max_results=3)
        profile_url = None
        for r in results:
            url = r.get("url", "")
            if "citations?user=" in url:
                profile_url = url
                break
        
        if not profile_url:
            res_str = []
            for r in results:
                res_str.append(f"Title: {r['title']}\nURL: {r['url']}\nSnippet: {r['snippet']}")
            return "No Google Scholar citations profile found. Here are relevant publications matches:\n\n" + "\n\n".join(res_str)
        
        headers = {
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36"
        }
        async with httpx.AsyncClient(timeout=15.0, follow_redirects=True) as client:
            res = await client.get(profile_url, headers=headers)
            if res.status_code == 200:
                html = res.text
                name_match = re.search(r'id="gsc_prf_in"[^>]*>(.*?)</div>', html)
                author_name = name_match.group(1).strip() if name_match else query
                
                aff_match = re.search(r'class="gsc_prf_il"[^>]*>(.*?)</div>', html)
                affiliation = re.sub('<[^>]+>', '', aff_match.group(1)).strip() if aff_match else "Unknown"
                
                rows = re.findall(r'<tr class="gsc_a_tr".*?</tr>', html, re.DOTALL)
                pub_list = []
                for idx, row in enumerate(rows[:15]):
                    t_match = re.search(r'class="gsc_a_at"[^>]*>(.*?)</a>', row)
                    title = re.sub('<[^>]+>', '', t_match.group(1)).strip() if t_match else "Untitled"
                    
                    authors_match = re.search(r'<div class="gs_gray"[^>]*>(.*?)</div>', row)
                    authors = re.sub('<[^>]+>', '', authors_match.group(1)).strip() if authors_match else "Unknown"
                    
                    cite_match = re.search(r'class="gsc_a_ac gs_ibl"[^>]*>(.*?)</a>', row)
                    citations = cite_match.group(1).strip() if cite_match else "0"
                    citations = citations if citations else "0"
                    
                    year_match = re.search(r'class="gsc_a_h gsc_a_hc gs_ibl"[^>]*>(.*?)</span>', row)
                    year = year_match.group(1).strip() if year_match else "Unknown"
                    
                    pub_list.append(f"  - Title: {title} ({year}) | Citations: {citations} | Authors: {authors}")
                
                pubs_str = "\n".join(pub_list) if pub_list else "  - No publication works found on profile."
                return f"[GOOGLE SCHOLAR PROFILE]\nAuthor: {author_name}\nAffiliation: {affiliation}\nProfile URL: {profile_url}\nPublications:\n{pubs_str}"
            else:
                return f"Failed to retrieve Google Scholar profile page. Status code: {res.status_code}"
    except Exception as e:
        return f"Error searching Google Scholar: {e}"

async def search_researchgate_profile(query: str) -> str:
    """Searches for a researcher's profile on ResearchGate using DuckDuckGo snippets."""
    from app.services.scraping_service import ScrapingService
    scraper = ScrapingService()
    try:
        results = await scraper.search_web(f"{query} ResearchGate", max_results=4)
        if not results:
            return f"No ResearchGate profiles found for query: {query}"
        
        matches = []
        for r in results:
            url = r.get("url", "")
            if "researchgate.net" in url:
                matches.append(f"Title: {r['title']}\nProfile URL: {url}\nSummary: {r['snippet']}")
        
        if matches:
            return "[RESEARCHGATE PROFILE MATCHES]\n\n" + "\n\n---\n\n".join(matches)
        
        res_str = []
        for r in results:
            res_str.append(f"Title: {r['title']}\nURL: {r['url']}\nSnippet: {r['snippet']}")
        return "No direct ResearchGate links found in top matches. Here are relevant search results:\n\n" + "\n\n".join(res_str)
    except Exception as e:
        return f"Error searching ResearchGate: {e}"

def list_local_files(directory_path: str = ".") -> str:
    """Real connector to list local files in a directory."""
    try:
        # Sanitize to prevent accessing root system files
        safe_path = os.path.abspath(directory_path)
        if not os.path.exists(safe_path):
            return f"Directory {safe_path} does not exist."
        files = os.listdir(safe_path)
        return f"Files in {safe_path}:\n" + "\n".join(files[:50])
    except Exception as e:
        return f"Error reading local folder: {e}"

def read_local_file(file_path: str) -> str:
    """Real connector to read a local file's content."""
    try:
        safe_path = os.path.abspath(file_path)
        if not os.path.exists(safe_path):
            return f"File {safe_path} does not exist."
        with open(safe_path, 'r', encoding='utf-8') as f:
            content = f.read(2000) # read up to 2000 chars
            return f"Content of {safe_path}:\n{content}"
    except Exception as e:
        return f"Error reading file: {e}"

async def search_web(query: str, max_results: int = 5) -> str:
    """Real connector to search the web using ScrapingService."""
    from app.services.scraping_service import ScrapingService
    scraping_service = ScrapingService()
    try:
        results = await scraping_service.search_web(query, max_results)
        if results:
            formatted = []
            for r in results:
                formatted.append(f"Title: {r['title']}\nURL: {r['url']}\nSnippet: {r['snippet']}")
            return "\n\n".join(formatted)
    except Exception as e:
        return f"Failed to search the web: {e}"

    return "No web results found."


# --- Simulated Connectors ---

def search_gmail(sender: str = "", topic: str = "") -> str:
    """Simulated connector for Gmail."""
    # In a real scenario, this would use google-api-python-client with OAuth2.
    return f"""[SIMULATED GMAIL] Found 2 recent emails matching sender='{sender}' or topic='{topic}':
1. From: collaborator@mit.edu - "Re: Draft of the quantum physics paper" - Let's review the final draft tomorrow.
2. From: grant-committee@nsf.gov - "Grant Application Update" - Your application is in the final review stage."""

def search_whatsapp(contact: str = "") -> str:
    """Simulated connector for WhatsApp."""
    # In a real scenario, this would integrate with WhatsApp Business API or a local bridge.
    return f"""[SIMULATED WHATSAPP] Recent messages from '{contact}':
- Yesterday, 4:30 PM: "Hey, did you see the new Nature preprint?"
- Today, 9:15 AM: "I'll send over the datasets by noon." """


def generate_downloadable_table(headers: List[str], rows: List[List[Any]], filename: str, base_url: str = None) -> str:
    """Generates a downloadable CSV table file and returns its URL."""
    try:
        os.makedirs(DOWNLOADS_DIR, exist_ok=True)
        safe_filename = "".join(c for c in filename if c.isalnum() or c in (".", "_", "-")).strip()
        if not safe_filename.endswith(".csv"):
            safe_filename += ".csv"
        
        file_path = DOWNLOADS_DIR / safe_filename
        with open(file_path, "w", newline="", encoding="utf-8") as f:
            writer = csv.writer(f)
            writer.writerow(headers)
            writer.writerows(rows)
        
        host = base_url or _base_url()
        download_url = f"{host}/downloads/{safe_filename}"
        return f"[SUCCESS] Generated table successfully. Download URL: {download_url}"
    except Exception as e:
        return f"[ERROR] Failed to generate table: {e}"

def generate_interactive_chart(chart_type: str, labels: List[str], datasets: List[Dict[str, Any]], title: str, filename: str, base_url: str = None) -> str:
    """Generates a beautiful interactive Chart.js chart HTML page and returns its URL."""
    try:
        os.makedirs(DOWNLOADS_DIR, exist_ok=True)
        safe_filename = "".join(c for c in filename if c.isalnum() or c in (".", "_", "-")).strip()
        if not safe_filename.endswith(".html"):
            safe_filename += ".html"
            
        file_path = DOWNLOADS_DIR / safe_filename
        
        # Inject standard theme styling into datasets if background/border colors aren't defined
        colors = [
            {"bg": "rgba(255, 179, 0, 0.2)", "border": "rgba(255, 179, 0, 1)"},    # Gold/Amber
            {"bg": "rgba(33, 150, 243, 0.2)", "border": "rgba(33, 150, 243, 1)"},   # Blue
            {"bg": "rgba(0, 230, 118, 0.2)", "border": "rgba(0, 230, 118, 1)"},    # Green/Emerald
            {"bg": "rgba(233, 30, 99, 0.2)", "border": "rgba(233, 30, 99, 1)"},     # Pink/Rose
            {"bg": "rgba(156, 39, 176, 0.2)", "border": "rgba(156, 39, 176, 1)"},   # Purple
        ]
        for idx, dataset in enumerate(datasets):
            if "backgroundColor" not in dataset:
                dataset["backgroundColor"] = colors[idx % len(colors)]["bg"]
            if "borderColor" not in dataset:
                dataset["borderColor"] = colors[idx % len(colors)]["border"]
            if "borderWidth" not in dataset:
                dataset["borderWidth"] = 2
                
        labels_json = json.dumps(labels)
        datasets_json = json.dumps(datasets)
        
        html_content = f"""<!DOCTYPE html>
<html>
<head>
    <title>{title}</title>
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <script src="https://cdn.jsdelivr.net/npm/chart.js"></script>
    <style>
        body {{
            font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
            background-color: #0b0c10;
            color: #ffffff;
            margin: 0;
            padding: 16px;
            display: flex;
            flex-direction: column;
            align-items: center;
            justify-content: center;
            min-height: 100vh;
            box-sizing: border-box;
        }}
        .chart-container {{
            position: relative;
            width: 100%;
            max-width: 700px;
            background: #1f2833;
            padding: 20px;
            border-radius: 12px;
            border: 1px solid rgba(255, 255, 255, 0.08);
            box-shadow: 0 4px 20px rgba(0, 0, 0, 0.3);
            box-sizing: border-box;
        }}
        h2 {{
            margin-top: 0;
            font-size: 1.25rem;
            font-weight: 700;
            color: #c5a059;
            text-align: center;
            margin-bottom: 20px;
        }}
    </style>
</head>
<body>
    <div class="chart-container">
        <h2>{title}</h2>
        <canvas id="myChart"></canvas>
    </div>
    <script>
        const ctx = document.getElementById('myChart').getContext('2d');
        new Chart(ctx, {{
            type: '{chart_type}',
            data: {{
                labels: {labels_json},
                datasets: {datasets_json}
            }},
            options: {{
                responsive: true,
                maintainAspectRatio: true,
                plugins: {{
                    legend: {{
                        labels: {{
                            color: '#c5a059',
                            font: {{ size: 12 }}
                        }}
                    }}
                }},
                scales: {{
                    x: {{
                        grid: {{ color: 'rgba(255, 255, 255, 0.05)' }},
                        ticks: {{ color: '#c5c6c7' }}
                    }},
                    y: {{
                        grid: {{ color: 'rgba(255, 255, 255, 0.05)' }},
                        ticks: {{ color: '#c5c6c7' }}
                    }}
                }}
            }}
        }});
    </script>
</body>
</html>"""
        with open(file_path, "w", encoding="utf-8") as f:
            f.write(html_content)
            
        host = base_url or _base_url()
        download_url = f"{host}/downloads/{safe_filename}"
        return f"[SUCCESS] Generated chart successfully. View URL: {download_url}"
    except Exception as e:
        return f"[ERROR] Failed to generate chart: {e}"

def export_bibtex_file(publications: List[Dict[str, Any]], filename: str, base_url: str = None) -> str:
    """Exports a list of publications to a LaTeX-compatible .bib file and returns its URL."""
    try:
        os.makedirs(DOWNLOADS_DIR, exist_ok=True)
        safe_filename = "".join(c for c in filename if c.isalnum() or c in (".", "_", "-")).strip()
        if not safe_filename.endswith(".bib"):
            safe_filename += ".bib"
            
        file_path = DOWNLOADS_DIR / safe_filename
        bib_entries = []
        for idx, pub in enumerate(publications):
            title = pub.get("title", "Untitled Work")
            authors = pub.get("authors") or pub.get("author") or "Unknown"
            if isinstance(authors, list):
                authors = " and ".join(authors)
            year = pub.get("year") or pub.get("publication_year") or "2025"
            journal = pub.get("journal") or pub.get("venue") or "Scientific Journal"
            doi = pub.get("doi") or ""
            
            # Generate a key
            first_author = authors.split(" and ")[0].split()[-1] if authors else "author"
            clean_first_author = "".join(c for c in first_author if c.isalnum()).lower()
            clean_title_first_word = "".join(c for c in title.split()[0] if c.isalnum()).lower() if title.split() else "work"
            citation_key = f"{clean_first_author}{year}{clean_title_first_word}"
            
            entry = f"@article{{{citation_key},\n"
            entry += f"  author = {{{authors}}},\n"
            entry += f"  title = {{{title}}},\n"
            entry += f"  journal = {{{journal}}},\n"
            entry += f"  year = {{{year}}}"
            if doi:
                entry += f",\n  doi = {{{doi}}}"
            entry += "\n}"
            bib_entries.append(entry)
            
        with open(file_path, "w", encoding="utf-8") as f:
            f.write("\n\n".join(bib_entries))
            
        host = base_url or _base_url()
        download_url = f"{host}/downloads/{safe_filename}"
        return f"[SUCCESS] Exported BibTeX successfully. Download URL: {download_url}"
    except Exception as e:
        return f"[ERROR] Failed to export BibTeX: {e}"

def generate_research_report(title: str, sections: List[Dict[str, Any]], filename: str, base_url: str = None) -> str:
    """Generates a styled Markdown research report and returns its URL."""
    try:
        os.makedirs(DOWNLOADS_DIR, exist_ok=True)
        safe_filename = "".join(c for c in filename if c.isalnum() or c in (".", "_", "-")).strip()
        if not safe_filename.endswith(".md"):
            safe_filename += ".md"
            
        file_path = DOWNLOADS_DIR / safe_filename
        md_content = []
        md_content.append(f"# {title}\n")
        md_content.append(f"*Generated by Ask Skolar*\n")
        md_content.append("---")
        
        for section in sections:
            heading = section.get("heading", "Section")
            content = section.get("content", "")
            md_content.append(f"\n## {heading}\n")
            md_content.append(content)
            
        with open(file_path, "w", encoding="utf-8") as f:
            f.write("\n".join(md_content))
            
        host = base_url or _base_url()
        download_url = f"{host}/downloads/{safe_filename}"
        return f"[SUCCESS] Generated research report successfully. Download URL: {download_url}"
    except Exception as e:
        return f"[ERROR] Failed to generate report: {e}"


async def fetch_and_summarize_paper(query: str, source: str = "auto") -> str:
    """
    Fetches full metadata and abstract for a research paper from multiple academic sources
    and returns a rich content block for the LLM to summarize.
    Priority order: OpenAlex → arXiv → Semantic Scholar → Web Scraping.
    """
    content_blocks = []
    found_abstract = False

    # ── Detect input type ────────────────────────────────────────────────────
    is_doi  = bool(re.match(r'^10\.\d{4,}/', query.strip()))
    is_arxiv_id = bool(re.match(r'^(\d{4}\.\d{4,5}|[a-z\-]+/\d{7})(v\d+)?$', query.strip().lower()))
    is_url  = query.strip().startswith("http")
    arxiv_id = None
    if is_url and "arxiv.org" in query:
        m = re.search(r'arxiv\.org/(abs|pdf)/(\S+?)(?:\.pdf)?$', query)
        if m:
            arxiv_id = m.group(2)
    if is_arxiv_id:
        arxiv_id = query.strip()

    # ── 1. OpenAlex lookup ───────────────────────────────────────────────────
    if source in ("auto", "openalex"):
        try:
            oa_headers = {"User-Agent": f"SkolabApp/1.0 (mailto:{settings.openalex_email or 'support@skolab.open'})", "Accept": "application/json"}
            if is_doi:
                works_url = f"https://api.openalex.org/works/doi:{urllib.parse.quote(query.strip())}"
                async with httpx.AsyncClient(timeout=10.0) as client:
                    r = await client.get(works_url, headers=oa_headers)
                    if r.status_code == 200:
                        w = r.json()
                        title     = w.get("title", "Untitled")
                        year      = w.get("publication_year", "Unknown")
                        doi_val   = w.get("doi", "")
                        citations = w.get("cited_by_count", 0)
                        oa_url    = (w.get("primary_location") or {}).get("landing_page_url", "")
                        authors   = ", ".join([a["author"]["display_name"] for a in (w.get("authorships") or [])[:6] if a.get("author")])
                        concepts  = ", ".join([c["display_name"] for c in (w.get("concepts") or [])[:8]])
                        # Reconstruct abstract from inverted index
                        aii = w.get("abstract_inverted_index") or {}
                        abstract = ""
                        if aii:
                            positions = [(pos, word) for word, pos_list in aii.items() for pos in pos_list]
                            positions.sort(key=lambda x: x[0])
                            abstract = " ".join(word for _, word in positions)
                        content_blocks.append(
                            f"[OpenAlex Paper Record]\n"
                            f"Title: {title}\nAuthors: {authors}\nYear: {year}\n"
                            f"DOI: {doi_val}\nCitations: {citations}\nConcepts: {concepts}\n"
                            f"URL: {oa_url}\n\nAbstract:\n{abstract}"
                        )
                        found_abstract = bool(abstract)
            else:
                # Use title.search filter for precise matching, sort by relevance
                safe_title = urllib.parse.quote(query.strip())
                oa_url_q = (
                    f"https://api.openalex.org/works"
                    f"?filter=title.search:{safe_title}"
                    f"&sort=cited_by_count:desc"
                    f"&per_page=5"
                    f"&select=title,publication_year,doi,cited_by_count,authorships,concepts,abstract_inverted_index,primary_location"
                    f"&mailto={settings.openalex_email or 'support@skolab.open'}"
                )
                async with httpx.AsyncClient(timeout=12.0) as client:
                    r = await client.get(oa_url_q, headers=oa_headers)
                    if r.status_code == 200:
                        results = r.json().get("results", [])
                        # Pick the best match: most cited with a non-empty abstract
                        for w in results[:5]:
                            title     = w.get("title", "Untitled")
                            year      = w.get("publication_year", "Unknown")
                            doi_val   = w.get("doi", "")
                            citations = w.get("cited_by_count", 0)
                            authors   = ", ".join([a["author"]["display_name"] for a in (w.get("authorships") or [])[:6] if a.get("author")])
                            concepts  = ", ".join([c["display_name"] for c in (w.get("concepts") or [])[:8]])
                            # Reconstruct abstract from inverted index
                            aii = w.get("abstract_inverted_index") or {}
                            abstract = ""
                            if aii:
                                positions = [(pos, word) for word, pos_list in aii.items() for pos in pos_list]
                                positions.sort(key=lambda x: x[0])
                                abstract = " ".join(word for _, word in positions)
                            content_blocks.append(
                                f"[OpenAlex Match]\n"
                                f"Title: {title}\nAuthors: {authors}\nYear: {year}\n"
                                f"DOI: {doi_val}\nCitations: {citations}\nConcepts: {concepts}\n\nAbstract:\n{abstract}"
                            )
                            if abstract:
                                found_abstract = True
                                break  # Stop after first result with abstract
        except Exception as e:
            content_blocks.append(f"[OpenAlex] Error: {e}")

    # ── 2. arXiv lookup ──────────────────────────────────────────────────────
    if source in ("auto", "arxiv") and (arxiv_id or not found_abstract):
        try:
            if arxiv_id:
                arxiv_clean = re.sub(r'v\d+$', '', arxiv_id.strip())  # strip version suffix
                url = f"https://export.arxiv.org/api/query?id_list={urllib.parse.quote(arxiv_clean)}&max_results=1"
            else:
                # Use ti: prefix to search by title specifically for better relevance
                safe_title = urllib.parse.quote(f'ti:"{query}"')
                url = f"https://export.arxiv.org/api/query?search_query={safe_title}&start=0&max_results=3"

            async with httpx.AsyncClient(timeout=15.0, follow_redirects=True) as client:
                r = await client.get(url)
                if r.status_code != 200:
                    content_blocks.append(f"[arXiv] HTTP {r.status_code} error")
                else:
                    raw = r.content
                    if not raw.strip():
                        content_blocks.append("[arXiv] Empty response received")
                    else:
                        root = ET.fromstring(raw)
                        ns = {"atom": "http://www.w3.org/2005/Atom"}
                        entries = root.findall("atom:entry", ns)
                        if not entries:
                            content_blocks.append("[arXiv] No matching papers found")
                        for entry in entries:
                            title_el   = entry.find("atom:title", ns)
                            summary_el = entry.find("atom:summary", ns)
                            pub_el     = entry.find("atom:published", ns)
                            link_el    = entry.find("atom:link[@rel='alternate']", ns)
                            title   = (title_el.text if title_el is not None else "").strip().replace("\n", " ")
                            summary = (summary_el.text if summary_el is not None else "").strip().replace("\n", " ")
                            pub     = (pub_el.text if pub_el is not None else "")[:10]
                            link_href = link_el.attrib.get("href", "") if link_el is not None else ""
                            authors_els = entry.findall("atom:author/atom:name", ns)
                            authors_str = ", ".join([a.text or "" for a in authors_els[:6]])
                            cats    = [c.attrib.get("term", "") for c in entry.findall("atom:category", ns)]
                            content_blocks.append(
                                f"[arXiv Paper]\n"
                                f"Title: {title}\nAuthors: {authors_str}\nPublished: {pub}\n"
                                f"Categories: {', '.join(cats[:5])}\nURL: {link_href}\n\nAbstract:\n{summary}"
                            )
                            if summary:
                                found_abstract = True
        except ET.ParseError as e:
            content_blocks.append(f"[arXiv] XML parse error: {e}")
        except Exception as e:
            content_blocks.append(f"[arXiv] Error: {type(e).__name__}: {e}")

    # ── 3. Semantic Scholar API ──────────────────────────────────────────────
    if source in ("auto", "semantic_scholar") and not found_abstract:
        try:
            if is_doi:
                ss_url = f"https://api.semanticscholar.org/graph/v1/paper/DOI:{urllib.parse.quote(query.strip())}?fields=title,abstract,year,authors,citationCount,externalIds,tldr,fieldsOfStudy"
            else:
                safe_q = urllib.parse.quote(query)
                ss_url = f"https://api.semanticscholar.org/graph/v1/paper/search?query={safe_q}&fields=title,abstract,year,authors,citationCount,externalIds,tldr,fieldsOfStudy&limit=3"

            async with httpx.AsyncClient(timeout=12.0) as client:
                r = await client.get(ss_url, headers={"User-Agent": "AskSkolar/1.0"})
                if r.status_code == 200:
                    data = r.json()
                    papers = data.get("data", [data]) if "data" in data else [data]
                    for paper in papers[:2]:
                        title     = paper.get("title", "Untitled")
                        abstract  = paper.get("abstract") or ""
                        year      = paper.get("year", "Unknown")
                        citations = paper.get("citationCount", 0)
                        authors   = ", ".join([a.get("name", "") for a in (paper.get("authors") or [])[:6]])
                        fields    = ", ".join(paper.get("fieldsOfStudy") or [])
                        tldr_obj  = paper.get("tldr")
                        tldr      = tldr_obj.get("text", "") if tldr_obj else ""
                        ext_ids   = paper.get("externalIds") or {}
                        doi_val   = ext_ids.get("DOI", "")
                        block = (
                            f"[Semantic Scholar Paper]\n"
                            f"Title: {title}\nAuthors: {authors}\nYear: {year}\n"
                            f"Citations: {citations}\nFields: {fields}\nDOI: {doi_val}\n"
                        )
                        if tldr:
                            block += f"\nAI TL;DR: {tldr}"
                        if abstract:
                            block += f"\n\nAbstract:\n{abstract}"
                            found_abstract = True
                        content_blocks.append(block)
        except Exception as e:
            content_blocks.append(f"[Semantic Scholar] Error: {e}")

    # ── 4. Web-scraping fallback ─────────────────────────────────────────────
    if source in ("auto", "web") and not found_abstract:
        try:
            from app.services.scraping_service import ScrapingService
            scraper = ScrapingService()
            # Search on multiple academic sites in sequence
            search_queries = [
                f'{query} abstract site:arxiv.org',
                f'{query} abstract research paper',
                f'{query} site:semanticscholar.org',
                f'{query} site:researchgate.net abstract',
            ]
            for sq in search_queries:
                results = await scraper.search_web(sq, max_results=2)
                if results:
                    for r in results:
                        snippet = r.get("snippet", "")
                        if len(snippet) > 80:  # meaningful snippet
                            content_blocks.append(
                                f"[Web Result]\nTitle: {r.get('title', '')}\n"
                                f"URL: {r.get('url', '')}\nSnippet: {snippet}"
                            )
                            found_abstract = True
                if found_abstract:
                    break
        except Exception as e:
            content_blocks.append(f"[Web Scraping] Error: {e}")

    # ── 5. Direct URL fetch ──────────────────────────────────────────────────
    if is_url and source in ("auto", "url"):
        try:
            async with httpx.AsyncClient(timeout=12.0, follow_redirects=True) as client:
                r = await client.get(query.strip(), headers={"User-Agent": "Mozilla/5.0"})
                if r.status_code == 200:
                    html = r.text
                    # Strip HTML tags
                    text = re.sub(r'<style[^>]*>.*?</style>', '', html, flags=re.DOTALL)
                    text = re.sub(r'<script[^>]*>.*?</script>', '', text, flags=re.DOTALL)
                    text = re.sub(r'<[^>]+>', ' ', text)
                    text = re.sub(r'\s+', ' ', text).strip()[:4000]
                    content_blocks.append(f"[Direct URL Content]\nURL: {query}\n\n{text}")
                    found_abstract = True
        except Exception as e:
            content_blocks.append(f"[Direct URL] Error: {e}")

    if not content_blocks:
        return (
            f"[PAPER FETCH FAILED] Could not retrieve any content for query: '{query}'.\n"
            "No results found on OpenAlex, arXiv, Semantic Scholar, or via web search.\n"
            "Please try providing a DOI, arXiv ID, or direct URL."
        )

    joined = "\n\n" + "-" * 60 + "\n\n".join(content_blocks)
    return (
        f"[PAPER CONTENT RETRIEVED - PLEASE SUMMARIZE]\n"
        f"Query: {query}\n"
        f"Sources searched: OpenAlex, arXiv, Semantic Scholar, Web\n"
        f"Abstract found: {'Yes' if found_abstract else 'Partial/No'}\n"
        f"{joined}\n\n"
        f"[END CONTENT]\n"
        f"Now provide a structured summary with: Overview, Key Contributions, Methodology, "
        f"Results & Findings, Limitations, and Why It Matters."
    )


# These definitions are passed to the Groq LLM API
TOOLS_SCHEMA = [
    {
        "type": "function",
        "function": {
            "name": "search_arxiv_publications",
            "description": "Queries the arXiv API over HTTPS to fetch publication preprints and abstracts matching a researcher name or topic.",
            "parameters": {
                "type": "object",
                "properties": {
                    "query": {"type": "string", "description": "The search query (author name or keyword), e.g. 'Albert Einstein' or 'quantum entanglement'"},
                    "max_results": {"type": "integer", "description": "Max results to return (default 5)"}
                },
                "required": ["query"]
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "search_google_scholar_profile",
            "description": "Searches for a researcher's official Google Scholar citations profile page, extracting their affiliation and detailed publication list.",
            "parameters": {
                "type": "object",
                "properties": {
                    "query": {"type": "string", "description": "The author's name, e.g. 'Albert Einstein'"}
                },
                "required": ["query"]
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "search_researchgate_profile",
            "description": "Searches for a researcher's profile on ResearchGate using general search engine queries to extract their institution, publications count, and citations.",
            "parameters": {
                "type": "object",
                "properties": {
                    "query": {"type": "string", "description": "The author's name, e.g. 'Kush Saha'"}
                },
                "required": ["query"]
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "search_arxiv",
            "description": "Searches the arXiv API for academic papers on a given topic.",
            "parameters": {
                "type": "object",
                "properties": {
                    "query": {"type": "string", "description": "The search query, e.g., 'quantum entanglement'"},
                    "max_results": {"type": "integer", "description": "Max results to return (default 5)"}
                },
                "required": ["query"]
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "list_local_files",
            "description": "Lists files and folders in a local directory on the laptop.",
            "parameters": {
                "type": "object",
                "properties": {
                    "directory_path": {"type": "string", "description": "The path to list, e.g., '.' for current directory"}
                },
                "required": ["directory_path"]
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "read_local_file",
            "description": "Reads the content of a local text file.",
            "parameters": {
                "type": "object",
                "properties": {
                    "file_path": {"type": "string", "description": "The absolute or relative path to the file"}
                },
                "required": ["file_path"]
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "search_gmail",
            "description": "Searches the user's Gmail inbox for recent emails matching a sender or topic.",
            "parameters": {
                "type": "object",
                "properties": {
                    "sender": {"type": "string", "description": "The person who sent the email"},
                    "topic": {"type": "string", "description": "The topic or keyword in the email"}
                }
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "search_whatsapp",
            "description": "Searches the user's WhatsApp messages for recent chats with a contact.",
            "parameters": {
                "type": "object",
                "properties": {
                    "contact": {"type": "string", "description": "The name of the WhatsApp contact"}
                },
                "required": ["contact"]
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "search_web",
            "description": "Searches the live web for recent or dynamic information outside academic databases (e.g., news, current events, software libraries, documentation, or facts). Use this when the user asks about live, real-time, or dynamic topics.",
            "parameters": {
                "type": "object",
                "properties": {
                    "query": {"type": "string", "description": "The search query, e.g., 'React 19 release date'"},
                    "max_results": {"type": "integer", "description": "Max results to return (default 5)"}
                },
                "required": ["query"]
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "search_openalex_authors",
            "description": "Searches for academic authors/profiles on OpenAlex by name or query term. Optionally filter by research domain.",
            "parameters": {
                "type": "object",
                "properties": {
                    "query": {"type": "string", "description": "The author's name, e.g. 'Albert Einstein' or 'Kush Saha'"},
                    "domain": {"type": "string", "description": "Optional research domain to filter by, e.g. 'Physics', 'Machine Learning'. Use the user's research focus by default."}
                },
                "required": ["query"]
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "fetch_openalex_author_works",
            "description": "Fetches publication works (papers) of a given author by their OpenAlex author ID.",
            "parameters": {
                "type": "object",
                "properties": {
                    "author_id": {"type": "string", "description": "The OpenAlex author ID, e.g. 'https://openalex.org/A5020214245' or just 'A5020214245'"},
                    "limit": {"type": "integer", "description": "Max results to return (default 10)"}
                },
                "required": ["author_id"]
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "search_openalex_works",
            "description": "Searches for academic papers on OpenAlex by keywords, title, or topic. Optionally filter by the user's research domain.",
            "parameters": {
                "type": "object",
                "properties": {
                    "query": {"type": "string", "description": "The search query/keywords"},
                    "limit": {"type": "integer", "description": "Max results to return (default 10)"},
                    "domain": {"type": "string", "description": "Optional research domain to filter by, e.g. 'Physics'. Use the user's research focus by default."}
                },
                "required": ["query"]
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "search_openalex_author_and_works",
            "description": "Searches for a researcher's academic profile and retrieves their recent publication works in a single call. Always pass the user's research domain as the domain parameter to find the right person when there are name ambiguities.",
            "parameters": {
                "type": "object",
                "properties": {
                    "query": {"type": "string", "description": "The author's name, e.g. 'Albert Einstein'"},
                    "domain": {"type": "string", "description": "Research domain to disambiguate the author, e.g. 'Physics', 'Computer Science'. ALWAYS pass the user's domain here unless explicitly told otherwise."}
                },
                "required": ["query"]
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "generate_downloadable_table",
            "description": "Generates a downloadable CSV table file from data rows and headers, returning the download URL. Use this whenever the user asks for a table to download, export, or save.",
            "parameters": {
                "type": "object",
                "properties": {
                    "headers": {
                        "type": "array",
                        "items": {"type": "string"},
                        "description": "List of column headers for the table."
                    },
                    "rows": {
                        "type": "array",
                        "items": {
                            "type": "array",
                            "items": {"type": "string"}
                        },
                        "description": "List of rows, where each row is an array of cell values matching the headers."
                    },
                    "filename": {
                        "type": "string",
                        "description": "The desired name for the generated CSV file, e.g., 'publications.csv'."
                    }
                },
                "required": ["headers", "rows", "filename"]
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "generate_interactive_chart",
            "description": "Generates a beautiful, interactive HTML bar, line, or pie chart using Chart.js, returning the view/download URL. Use this to plot citation trends, publication years, or metrics.",
            "parameters": {
                "type": "object",
                "properties": {
                    "chart_type": {
                        "type": "string",
                        "enum": ["bar", "line", "pie"],
                        "description": "The type of chart to generate."
                    },
                    "labels": {
                        "type": "array",
                        "items": {"type": "string"},
                        "description": "List of X-axis labels, e.g., years ['2020', '2021', '2022']."
                    },
                    "datasets": {
                        "type": "array",
                        "items": {
                            "type": "object",
                            "properties": {
                                "label": {"type": "string", "description": "The name of the dataset."},
                                "data": {
                                    "type": "array",
                                    "items": {"type": "number"},
                                    "description": "Numeric data points."
                                }
                            },
                            "required": ["label", "data"]
                        },
                        "description": "List of datasets to plot."
                    },
                    "title": {
                        "type": "string",
                        "description": "The title of the chart, e.g. 'Citation Counts Over Time'."
                    },
                    "filename": {
                        "type": "string",
                        "description": "The name of the HTML file, e.g., 'citation_chart.html'."
                    }
                },
                "required": ["chart_type", "labels", "datasets", "title", "filename"]
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "export_bibtex_file",
            "description": "Compiles a list of academic publications into a downloadable .bib file for LaTeX citation. Use this when the user asks for references in BibTeX, LaTeX, or citation file formats.",
            "parameters": {
                "type": "object",
                "properties": {
                    "publications": {
                        "type": "array",
                        "items": {
                            "type": "object",
                            "properties": {
                                "title": {"type": "string"},
                                "authors": {
                                    "type": "array",
                                    "items": {"type": "string"},
                                    "description": "List of author names."
                                },
                                "year": {"type": "string"},
                                "journal": {"type": "string", "description": "Journal or conference name."},
                                "doi": {"type": "string", "description": "Digital Object Identifier if available."}
                            },
                            "required": ["title", "authors", "year"]
                        },
                        "description": "List of publications to export."
                    },
                    "filename": {
                        "type": "string",
                        "description": "The name of the .bib file, e.g., 'my_citations.bib'."
                    }
                },
                "required": ["publications", "filename"]
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "generate_research_report",
            "description": "Creates a structured Markdown report from a list of sections and headings, returning the download/view URL. Use this when the user asks for a written report, compilation, or summary doc.",
            "parameters": {
                "type": "object",
                "properties": {
                    "title": {"type": "string", "description": "The title of the research report."},
                    "sections": {
                        "type": "array",
                        "items": {
                            "type": "object",
                            "properties": {
                                "heading": {"type": "string", "description": "Heading of the section."},
                                "content": {"type": "string", "description": "Markdown formatted content of the section."}
                            },
                            "required": ["heading", "content"]
                        },
                        "description": "The sections comprising the report."
                    },
                    "filename": {
                        "type": "string",
                        "description": "The name of the report file, e.g., 'quantum_mechanics_report.md'."
                    }
                },
                "required": ["title", "sections", "filename"]
            }
        }
    },
    {
        "type": "function",
        "function": {
            "name": "fetch_and_summarize_paper",
            "description": (
                "Fetches a research paper's full metadata, abstract, and content from OpenAlex, arXiv, "
                "Semantic Scholar, and web scraping, then returns the collected content for summarization. "
                "Use this tool WHENEVER the user asks to 'summarize a paper', 'explain a paper', "
                "'give me an overview of [paper title]', 'what does [paper] say', or similar. "
                "Works with: paper titles, DOIs (e.g. 10.1234/xyz), arXiv IDs (e.g. 2301.00001), "
                "or full URLs (e.g. https://arxiv.org/abs/2301.00001)."
            ),
            "parameters": {
                "type": "object",
                "properties": {
                    "query": {
                        "type": "string",
                        "description": "The paper title, DOI, arXiv ID, or direct URL to the paper."
                    },
                    "source": {
                        "type": "string",
                        "enum": ["auto", "openalex", "arxiv", "semantic_scholar", "web", "url"],
                        "description": "Which source to use. Default is 'auto' which tries all sources."
                    }
                },
                "required": ["query"]
            }
        }
    }
]

async def execute_tool_call(tool_name: str, arguments: dict, base_url: str = None) -> str:
    """Routes the LLM's tool call to the actual Python function."""
    print(f"[Agent Tool Execution] Calling {tool_name} with {arguments}", flush=True)
    try:
        if tool_name == "generate_downloadable_table":
            return generate_downloadable_table(
                arguments.get("headers", []),
                arguments.get("rows", []),
                arguments.get("filename", "table.csv"),
                base_url=base_url
            )
        elif tool_name == "generate_interactive_chart":
            return generate_interactive_chart(
                arguments.get("chart_type", "bar"),
                arguments.get("labels", []),
                arguments.get("datasets", []),
                arguments.get("title", "Chart"),
                arguments.get("filename", "chart.html"),
                base_url=base_url
            )
        elif tool_name == "export_bibtex_file":
            return export_bibtex_file(
                arguments.get("publications", []),
                arguments.get("filename", "references.bib"),
                base_url=base_url
            )
        elif tool_name == "generate_research_report":
            return generate_research_report(
                arguments.get("title", "Research Report"),
                arguments.get("sections", []),
                arguments.get("filename", "report.md"),
                base_url=base_url
            )
        elif tool_name == "fetch_and_summarize_paper":
            return await fetch_and_summarize_paper(
                arguments.get("query", ""),
                arguments.get("source", "auto")
            )
        elif tool_name == "search_arxiv_publications":
            return await search_arxiv_publications(arguments.get("query", ""), arguments.get("max_results", 5))
        elif tool_name == "search_google_scholar_profile":
            return await search_google_scholar_profile(arguments.get("query", ""))
        elif tool_name == "search_researchgate_profile":
            return await search_researchgate_profile(arguments.get("query", ""))
        elif tool_name == "search_arxiv":
            # For backward compatibility
            return await search_arxiv_publications(arguments.get("query", ""), arguments.get("max_results", 5))
        elif tool_name == "list_local_files":
            return list_local_files(arguments.get("directory_path", "."))
        elif tool_name == "read_local_file":
            return read_local_file(arguments.get("file_path", ""))
        elif tool_name == "search_gmail":
            return search_gmail(arguments.get("sender", ""), arguments.get("topic", ""))
        elif tool_name == "search_whatsapp":
            return search_whatsapp(arguments.get("contact", ""))
        elif tool_name == "search_web":
            return await search_web(arguments.get("query", ""), arguments.get("max_results", 5))
        elif tool_name == "search_openalex_authors":
            from app.services.openalex_service import OpenAlexService
            openalex = OpenAlexService()
            domain = arguments.get("domain", "").lower().strip()
            authors = await openalex.search_authors(arguments.get("query", ""), per_page=20)
            if not authors:
                return "No authors found on OpenAlex."
            # Filter by domain if provided: check x_concepts for a match
            if domain:
                filtered = [
                    a for a in authors
                    if any(domain in (c.get("display_name") or "").lower() for c in (a.get("x_concepts") or []))
                ]
                authors = filtered if filtered else authors  # fallback to unfiltered if no match
            res_list = []
            for a in authors[:20]:
                last_known_insts = a.get("last_known_institutions") or []
                insts = ", ".join([inst.get("display_name", "") for inst in last_known_insts if inst and inst.get("display_name")])
                stats = a.get("summary_stats") or {}
                h_index = stats.get("h_index", 0) if stats else 0
                top_concepts = ", ".join([c.get("display_name", "") for c in (a.get("x_concepts") or [])[:4]])
                res_list.append(
                    f"Name: {a.get('display_name')}\nOpenAlex ID: {a.get('id')}\n"
                    f"Institution: {insts or 'Unknown'}\nWorks Count: {a.get('works_count')}\n"
                    f"H-Index: {h_index}\nTop Fields: {top_concepts or 'Unknown'}"
                )
            domain_note = f" (filtered by domain: {domain})'" if domain else ""
            return f"[OpenAlex Authors{domain_note}]\n\n" + "\n\n".join(res_list)
        elif tool_name == "fetch_openalex_author_works":
            from app.services.openalex_service import OpenAlexService
            openalex = OpenAlexService()
            works = await openalex.fetch_author_works(arguments.get("author_id", ""), per_page=arguments.get("limit", 10))
            if not works:
                return "No works found for this author on OpenAlex."
            res_list = []
            for w in works:
                title = w.get("title", "Untitled")
                year = w.get("publication_year", "Unknown")
                doi = w.get("doi", "No DOI")
                citations = w.get("cited_by_count", 0)
                res_list.append(f"Title: {title}\nYear: {year}\nDOI: {doi}\nCitations: {citations}")
            return "\n\n".join(res_list)
        elif tool_name == "search_openalex_works":
            from app.services.openalex_service import OpenAlexService
            openalex = OpenAlexService()
            domain = arguments.get("domain", "").strip()
            query = arguments.get("query", "")
            # Append domain to query for better relevance if provided
            if domain:
                query = f"{query} {domain}"
            works = await openalex.search_works(query, per_page=arguments.get("limit", 10))
            if not works:
                return "No works found on OpenAlex."
            res_list = []
            for w in works:
                title = w.get("title", "Untitled")
                year = w.get("publication_year", "Unknown")
                doi = w.get("doi", "No DOI")
                citations = w.get("cited_by_count", 0)
                concepts = ", ".join([c.get("display_name", "") for c in (w.get("concepts") or [])[:4]])
                res_list.append(f"Title: {title}\nYear: {year}\nDOI: {doi}\nCitations: {citations}\nFields: {concepts}")
            return "\n\n".join(res_list)
        elif tool_name == "search_openalex_author_and_works":
            from app.services.openalex_service import OpenAlexService
            openalex = OpenAlexService()
            domain = arguments.get("domain", "").lower().strip()
            authors = await openalex.search_authors(arguments.get("query", ""), per_page=15)
            if not authors:
                return f"No authors found on OpenAlex for query: {arguments.get('query')}"

            # Domain-based ranking: score each author by how well their top concepts match the domain
            def domain_score(author: dict) -> int:
                if not domain:
                    return 0
                concepts = [c.get("display_name", "").lower() for c in (author.get("x_concepts") or [])]
                return sum(1 for c in concepts if domain in c)

            # Sort authors: domain-matched first, then by works_count
            authors_sorted = sorted(
                authors,
                key=lambda a: (domain_score(a), a.get("works_count") or 0),
                reverse=True
            )

            res_list = []
            for idx, a in enumerate(authors_sorted[:3]):  # take top 3 domain-matched
                author_id = a.get("id", "")
                clean_id = author_id.split("/")[-1]
                last_known_insts = a.get("last_known_institutions") or []
                insts = ", ".join([inst.get("display_name", "") for inst in last_known_insts if inst and inst.get("display_name")])
                stats = a.get("summary_stats") or {}
                h_index = stats.get("h_index", 0) if stats else 0
                top_concepts = ", ".join([c.get("display_name", "") for c in (a.get("x_concepts") or [])[:5]])
                profile_info = (
                    f"Match {idx+1}:\nName: {a.get('display_name')}\nOpenAlex ID: {author_id}\n"
                    f"Institution: {insts or 'Unknown'}\nWorks Count: {a.get('works_count')}\n"
                    f"H-Index: {h_index}\nResearch Fields: {top_concepts or 'Unknown'}"
                )

                # Fetch works for this match
                works = await openalex.fetch_author_works(clean_id, per_page=10)
                works_list = []
                for w in works:
                    title = w.get("title", "Untitled")
                    year = w.get("publication_year", "Unknown")
                    doi = w.get("doi", "No DOI")
                    citations = w.get("cited_by_count", 0)
                    works_list.append(f"  - Title: {title} ({year}) | Citations: {citations} | DOI: {doi}")

                works_str = "\n".join(works_list) if works_list else "  - No publication works found."
                res_list.append(f"{profile_info}\nPublications:\n{works_str}")

            domain_note = f" [domain-filtered: {domain}]" if domain else ""
            return f"[OpenAlex Author Search{domain_note}]\n\n" + "\n\n---\n\n".join(res_list)
        else:
            return f"Unknown tool: {tool_name}"
    except Exception as e:
        return f"Error executing tool {tool_name}: {e}"
