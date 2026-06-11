"""
Skolab Paper Intelligence Engine
================================
Reads the FULL text of scientific papers (via open-access PDF) and runs a
Research Intelligence Agent to extract 9 structured insight dimensions.

PDF sourcing priority:
  1. OpenAlex open_access.oa_url  (best_oa_location)
  2. Unpaywall API                (doi.org → unpaywall)
  3. Semantic Scholar PDF url     (when DOI is available)
  4. arXiv direct PDF             (auto-detected from DOI / ID)
  5. Abstract + metadata fallback (no PDF available)
"""

import httpx
import json
import re
import io
import asyncio
from typing import Optional, List, Dict, Any, Tuple
from app.services.platform.metrics_service import MetricsService
from app.prompts import (
    RESEARCH_INTELLIGENCE_SYSTEM_PROMPT,
    PAPER_COMMUNICATOR_PROMPT_TEMPLATE,
    PRESENTATION_PRESENTER_PROMPT_TEMPLATE,
)
from app.core.config import settings
from app.services.ai.llm_service import is_llm_working


# ── LLM Context Budget ────────────────────────────────────────────────────────
# Groq Llama-3.3-70B context window = 128k tokens
# 1 token ≈ 0.75 words  →  ~96 000 words max
# We cap at 30 000 words (~40 000 tokens) to leave room for prompt + output.
MAX_PAPER_WORDS = 30_000

# Sections to strip from PDF text to reduce noise
NOISE_PATTERNS = re.compile(
    r"(references|bibliography|acknowledgements?|appendix|supplementary|"
    r"author\s+contributions?|conflict\s+of\s+interest|funding\s+sources?|"
    r"data\s+availability|ethics\s+approval)",
    re.IGNORECASE,
)


# ── Master Prompt ──
# Imported from app.prompts.summarization_prompts


# Shared module-level in-memory cache
_global_intelligence_cache: Dict[str, Dict[str, Any]] = {}


class SummarizationService:
    def __init__(self):
        from app.services.ai.llm_service import LLMService

        self.llm_service = LLMService()
        self.models = None
        self.metrics_service = MetricsService()

        # In-memory cache: DOI / OpenAlex ID → intelligence result (session-scoped)
        self._intelligence_cache = _global_intelligence_cache

    # ══════════════════════════════════════════════════════════════════════════
    # PUBLIC — Deep Analysis (new /analyze_paper endpoint)
    # ══════════════════════════════════════════════════════════════════════════

    async def analyze_paper(
        self,
        title: str,
        doi: Optional[str] = None,
        openalex_id: Optional[str] = None,
    ) -> Dict[str, Any]:
        """
        Reads the FULL TEXT of the paper (PDF when available, abstract as fallback)
        and extracts 9-dimensional structured intelligence via the LLM.
        """
        # Caching disabled as per complete cache removal requirement

        # ── Step 1: Fetch OpenAlex metadata (always) ──────────────────────────
        print(f"[analyze_paper] Fetching metadata for: {title[:60]}", flush=True)
        meta = await self._fetch_openalex_meta(doi=doi, openalex_id=openalex_id)

        # ── Step 2: Attempt to get the full paper text ────────────────────────
        if not is_llm_working():
            return {
                "tldr": "",
                "key_findings": [],
                "techniques": [],
                "tools_and_software": [],
                "core_concepts": [],
                "formulas": [],
                "limitations": [],
                "real_world_impact": "",
                "future_directions": [],
                "confidence": "Low",
                "text_source": "unavailable",
                "status": "degraded",
                "message": "LLM service is temporarily unavailable.",
            }

        full_text, text_source = await self._fetch_full_paper_text(
            doi=doi,
            openalex_id=openalex_id,
            oa_url=meta.get("oa_url"),
            arxiv_id=meta.get("arxiv_id"),
        )
        # ── Step 3: Build context for LLM ────────────────────────────────────
        context = self._build_context(title, meta, full_text, text_source)
        # ── Step 4: Run LLM ───────────────────────────────────────────────────
        try:
            return await self._run_intelligence_llm(context, title, meta, text_source)
        except Exception as exc:
            raise Exception(f"Failed to query LLM to analyze paper: {str(exc)}")

    # ══════════════════════════════════════════════════════════════════════════
    # PDF FETCHING & TEXT EXTRACTION
    # ══════════════════════════════════════════════════════════════════════════

    async def _fetch_full_paper_text(
        self,
        doi: Optional[str],
        openalex_id: Optional[str],
        oa_url: Optional[str],
        arxiv_id: Optional[str],
    ) -> Tuple[Optional[str], str]:
        """
        Tries multiple sources to obtain the full paper text.
        Returns (text, source_label).

        Priority:
          1. OpenAlex best_oa_location URL (PDF or HTML)
          2. arXiv direct PDF (if arxiv_id detected)
          3. Unpaywall API (doi-based)
          4. Semantic Scholar S2 PDF link
          ↳ Fallback: None (caller uses abstract)
        """

        # ── Source 1: OpenAlex oa_url ─────────────────────────────────────────
        if oa_url:
            print(f"[PDF] Trying OpenAlex OA URL: {oa_url[:80]}", flush=True)
            text = await self._download_and_extract_pdf(oa_url)
            if text:
                return text, "full_text_oa"

        # ── Source 2: arXiv direct PDF ────────────────────────────────────────
        if not arxiv_id and doi:
            arxiv_id = self._extract_arxiv_id(doi)
        if arxiv_id:
            arxiv_pdf = f"https://arxiv.org/pdf/{arxiv_id}.pdf"
            print(f"[PDF] Trying arXiv PDF: {arxiv_pdf}", flush=True)
            text = await self._download_and_extract_pdf(arxiv_pdf)
            if text:
                return text, "full_text_arxiv"

        # ── Source 3: Unpaywall ───────────────────────────────────────────────
        if doi:
            unpaywall_url = await self._get_unpaywall_pdf_url(doi)
            if unpaywall_url:
                print(f"[PDF] Trying Unpaywall: {unpaywall_url[:80]}", flush=True)
                text = await self._download_and_extract_pdf(unpaywall_url)
                if text:
                    return text, "full_text_unpaywall"

        # ── Source 4: Semantic Scholar ────────────────────────────────────────
        if doi:
            s2_url = await self._get_semantic_scholar_pdf_url(doi)
            if s2_url:
                print(f"[PDF] Trying Semantic Scholar: {s2_url[:80]}", flush=True)
                text = await self._download_and_extract_pdf(s2_url)
                if text:
                    return text, "full_text_s2"

        print("[PDF] No full text found — will use abstract only.", flush=True)
        return None, "abstract_only"

    async def _download_and_extract_pdf(self, url: str) -> Optional[str]:
        """Downloads a PDF from url and extracts clean text. Returns None on failure."""
        try:
            import pdfplumber  # noqa: F401
        except ImportError:
            print(
                "[PDF] pdfplumber not installed. Run: pip install pdfplumber",
                flush=True,
            )
            return None

        try:
            headers = {
                "User-Agent": (
                    f"SkolabApp/1.0 (mailto:{settings.openalex_email or 'support@skolab.open'}) "
                    "Academic research tool - reading open-access papers"
                ),
                "Accept": "application/pdf,*/*",
            }
            async with httpx.AsyncClient(
                headers=headers,
                timeout=httpx.Timeout(settings.http_timeout_seconds, connect=2.0),
                follow_redirects=True,
            ) as client:
                resp = await client.get(url)
                if resp.status_code != 200:
                    print(f"[PDF] HTTP {resp.status_code} for {url[:60]}", flush=True)
                    return None

                content_type = resp.headers.get("content-type", "")
                if "pdf" not in content_type and not url.endswith(".pdf"):
                    print(f"[PDF] Not a PDF (content-type: {content_type})", flush=True)
                    return None

                pdf_bytes = resp.content
                if len(pdf_bytes) < 1000:
                    print(
                        f"[PDF] File too small ({len(pdf_bytes)} bytes), skipping",
                        flush=True,
                    )
                    return None

            # Extract text in a thread pool to avoid blocking the event loop
            text = await asyncio.get_event_loop().run_in_executor(
                None, self._extract_text_pdfplumber, pdf_bytes
            )

            if not text or len(text.strip()) < 200:
                print(
                    "[PDF] Extracted text too short — likely image-based PDF",
                    flush=True,
                )
                return None

            print(f"[PDF] Extracted {len(text.split())} words from PDF", flush=True)
            return text

        except httpx.TimeoutException:
            print(f"[PDF] Download timed out: {url[:60]}", flush=True)
            return None
        except Exception as e:
            # Encode error message safely — PDF titles may contain non-ASCII chars (em-dashes etc.)
            safe_err = str(e).encode("ascii", errors="replace").decode("ascii")
            print(f"[PDF] Download/extraction error: {safe_err}", flush=True)
            return None

    @staticmethod
    def _extract_text_pdfplumber(pdf_bytes: bytes) -> Optional[str]:
        """Synchronous PDF text extraction — run in executor."""
        try:
            import pdfplumber

            pages_text: List[str] = []
            references_hit = False
            word_count = 0

            with pdfplumber.open(io.BytesIO(pdf_bytes)) as pdf:
                for page_num, page in enumerate(pdf.pages):
                    if word_count >= MAX_PAPER_WORDS:
                        break

                    text = page.extract_text(x_tolerance=2, y_tolerance=3) or ""
                    if not text.strip():
                        continue

                    # Stop at References section to avoid noise
                    lines = text.split("\n")
                    clean_lines = []
                    for line in lines:
                        if NOISE_PATTERNS.match(line.strip()):
                            references_hit = True
                            break
                        clean_lines.append(line)

                    page_text = "\n".join(clean_lines).strip()
                    if page_text:
                        pages_text.append(page_text)
                        word_count += len(page_text.split())

                    if references_hit:
                        break

            full_text = "\n\n".join(pages_text)
            # Basic cleanup: remove excessive whitespace
            full_text = re.sub(r"\n{3,}", "\n\n", full_text)
            full_text = re.sub(r"[ \t]{2,}", " ", full_text)

            # Enforce word limit
            words = full_text.split()
            if len(words) > MAX_PAPER_WORDS:
                full_text = (
                    " ".join(words[:MAX_PAPER_WORDS])
                    + "\n\n[TEXT TRUNCATED AT 30,000 WORDS]"
                )

            return full_text
        except Exception as e:
            safe_err = str(e).encode("ascii", errors="replace").decode("ascii")
            print(f"[pdfplumber] Extraction error: {safe_err}", flush=True)
            return None

    # ══════════════════════════════════════════════════════════════════════════
    # PDF URL SOURCES
    # ══════════════════════════════════════════════════════════════════════════

    async def _get_unpaywall_pdf_url(self, doi: str) -> Optional[str]:
        """Queries Unpaywall to find a legal open-access PDF URL for a DOI."""
        try:
            clean_doi = self._clean_doi(doi)
            url = f"https://api.unpaywall.org/v2/{clean_doi}?email={settings.openalex_email or 'support@skolab.open'}"
            async with httpx.AsyncClient(
                timeout=settings.http_timeout_seconds, follow_redirects=True
            ) as client:
                resp = await client.get(url)
                if resp.status_code == 200:
                    data = resp.json()
                    best_oa = data.get("best_oa_location") or {}
                    pdf_url = best_oa.get("url_for_pdf")
                    if pdf_url:
                        return pdf_url
                    # Fall back to url (may be landing page, but try)
                    return best_oa.get("url")
        except Exception as e:
            print(f"[Unpaywall] Error: {e}", flush=True)
        return None

    async def _get_semantic_scholar_pdf_url(self, doi: str) -> Optional[str]:
        """Fetches PDF URL from Semantic Scholar's paper API."""
        try:
            clean_doi = self._clean_doi(doi)
            url = f"https://api.semanticscholar.org/graph/v1/paper/{clean_doi}?fields=openAccessPdf"
            async with httpx.AsyncClient(
                timeout=settings.http_timeout_seconds
            ) as client:
                resp = await client.get(url)
                if resp.status_code == 200:
                    data = resp.json()
                    oa_pdf = data.get("openAccessPdf") or {}
                    return oa_pdf.get("url")
        except Exception as e:
            print(f"[SemanticScholar] Error: {e}", flush=True)
        return None

    # ══════════════════════════════════════════════════════════════════════════
    # OPENALEX METADATA
    # ══════════════════════════════════════════════════════════════════════════

    async def _fetch_openalex_meta(
        self,
        doi: Optional[str] = None,
        openalex_id: Optional[str] = None,
    ) -> Dict[str, Any]:
        """Fetches rich metadata from OpenAlex including best_oa_location."""
        try:
            url = self._build_openalex_url(doi=doi, openalex_id=openalex_id)
            if not url:
                return {}

            headers = {
                "User-Agent": f"SkolabApp/1.0 (mailto:{settings.openalex_email or 'support@skolab.open'})",
                "Accept": "application/json",
            }

            if settings.openalex_api_key:
                headers["api_key"] = settings.openalex_api_key
            async with httpx.AsyncClient(
                headers=headers, timeout=settings.http_timeout_seconds
            ) as client:
                resp = await client.get(url)
                if resp.status_code != 200:
                    return {}
                data = resp.json()

            if not data or not isinstance(data, dict):
                return {}

            abstract = self._reconstruct_abstract(data.get("abstract_inverted_index"))

            # best_oa_location gives the most likely free PDF
            best_oa = data.get("best_oa_location")
            if not isinstance(best_oa, dict):
                best_oa = {}
            oa_url = best_oa.get("pdf_url") or best_oa.get("landing_page_url")

            # arXiv detection from DOIs or IDs
            arxiv_id = None
            raw_doi = data.get("doi", "") or doi or ""
            arxiv_id = self._extract_arxiv_id(raw_doi)

            topics = []
            for t in (data.get("topics") or [])[:12]:
                if isinstance(t, dict):
                    name = t.get("display_name")
                    if name:
                        topics.append(name)

            concepts = []
            for c in (data.get("concepts") or [])[:15]:
                if isinstance(c, dict):
                    name = c.get("display_name")
                    if name:
                        concepts.append(name)

            authors = []
            for a in (data.get("authorships") or [])[:8]:
                if isinstance(a, dict):
                    author_obj = a.get("author")
                    if isinstance(author_obj, dict):
                        name = author_obj.get("display_name")
                        if name:
                            authors.append(name)

            primary_loc = data.get("primary_location")
            if not isinstance(primary_loc, dict):
                primary_loc = {}
            source_obj = primary_loc.get("source")
            if not isinstance(source_obj, dict):
                source_obj = {}
            journal = source_obj.get("display_name", "")

            # open_access dict check
            oa_obj = data.get("open_access")
            if not isinstance(oa_obj, dict):
                oa_obj = {}

            return {
                "abstract": abstract,
                "oa_url": oa_url,
                "arxiv_id": arxiv_id,
                "topics": topics,
                "concepts": concepts,
                "authors": authors,
                "year": data.get("publication_year"),
                "cited_by_count": data.get("cited_by_count", 0),
                "referenced_works_count": data.get("referenced_works_count", 0),
                "is_open_access": oa_obj.get("is_oa", False),
                "journal": journal,
                "doi": data.get("doi", ""),
            }
        except Exception as e:
            print(f"[OpenAlex meta] Error: {e}", flush=True)
            import traceback

            traceback.print_exc()
            return {}

    # ══════════════════════════════════════════════════════════════════════════
    # LLM
    # ══════════════════════════════════════════════════════════════════════════

    def _build_context(
        self,
        title: str,
        meta: Dict[str, Any],
        full_text: Optional[str],
        text_source: str,
    ) -> str:
        """Assembles the full user message for the LLM."""
        lines: List[str] = [
            "=" * 60,
            "PAPER METADATA",
            "=" * 60,
            f"TITLE: {title}",
        ]
        if meta.get("year"):
            lines.append(f"YEAR: {meta['year']}")
        if meta.get("authors"):
            lines.append(f"AUTHORS: {', '.join(meta['authors'][:6])}")
        if meta.get("cited_by_count") is not None:
            lines.append(f"CITATIONS: {meta['cited_by_count']}")
        if meta.get("journal"):
            lines.append(f"JOURNAL/VENUE: {meta['journal']}")
        if meta.get("is_open_access") is not None:
            lines.append(f"OPEN ACCESS: {'Yes' if meta['is_open_access'] else 'No'}")
        if meta.get("referenced_works_count"):
            lines.append(f"REFERENCES CITED: {meta['referenced_works_count']}")
        if meta.get("topics"):
            lines.append(f"TOPICS: {'; '.join(meta['topics'][:8])}")

        lines += ["", "=" * 60]

        if full_text:
            lines += [
                f"FULL PAPER TEXT ({text_source.replace('_', ' ').upper()})",
                "=" * 60,
                full_text,
            ]
        else:
            lines += [
                "ABSTRACT (full PDF not available — analyse from abstract + metadata)",
                "=" * 60,
                meta.get("abstract") or "Abstract not available.",
            ]

        return "\n".join(lines)

    async def _run_intelligence_llm(
        self,
        context: str,
        title: str,
        meta: Dict[str, Any],
        text_source: str,
    ) -> Dict[str, Any]:
        """Calls LLMService with the Research Intelligence Agent prompt."""
        messages = [
            {"role": "system", "content": RESEARCH_INTELLIGENCE_SYSTEM_PROMPT},
            {"role": "user", "content": context},
        ]

        response = await self.llm_service.query(
            messages=messages,
            models=self.models,
            temperature=0.15,
            max_tokens=2048,
            response_format={"type": "json_object"},
        )

        if not response.content:
            raise Exception("LLM response was empty.")

        return self._parse_and_normalise(response.content, title, meta, text_source)

    def _parse_and_normalise(
        self,
        raw: str,
        title: str,
        meta: Dict[str, Any],
        text_source: str,
    ) -> Dict[str, Any]:
        """Parses JSON and normalises LaTeX escaping."""
        try:
            content = json.loads(raw)
        except json.JSONDecodeError:
            try:
                # Fix unescaped backslashes that confuse JSON parser
                fixed = re.sub(
                    r'(?<!\\)\\(?!["\\\/bfnrt]|u[0-9a-fA-F]{4})',
                    r"\\\\",
                    raw,
                )
                content = json.loads(fixed)
            except Exception as parse_err:
                print(f"[LLM] JSON parse failed: {raw[:400]}", flush=True)
                raise Exception(f"Failed to parse LLM response: {parse_err}")

        def fix_latex(s: str) -> str:
            """Normalise double-escaped backslashes back to single for display."""
            return s.replace("\\\\", "\\") if isinstance(s, str) else s

        def fix_list(lst: Any) -> List[str]:
            if not isinstance(lst, list):
                return []
            return [fix_latex(x) for x in lst if isinstance(x, str)]

        # Override confidence based on text source
        confidence_map = {
            "full_text_oa": "High",
            "full_text_arxiv": "High",
            "full_text_unpaywall": "High",
            "full_text_s2": "High",
            "abstract_only": "Medium",
        }
        confidence = confidence_map.get(
            text_source, content.get("confidence", "Medium")
        )

        return {
            "tldr": fix_latex(content.get("tldr", "")),
            "key_findings": fix_list(content.get("key_findings", [])),
            "techniques": fix_list(content.get("techniques", [])),
            "tools_and_software": fix_list(content.get("tools_and_software", [])),
            "core_concepts": fix_list(content.get("core_concepts", [])),
            "formulas": fix_list(content.get("formulas", [])),
            "limitations": fix_list(content.get("limitations", [])),
            "real_world_impact": fix_latex(content.get("real_world_impact", "")),
            "future_directions": fix_list(content.get("future_directions", [])),
            "confidence": confidence,
            "text_source": text_source,
        }

    # ══════════════════════════════════════════════════════════════════════════
    # LEGACY — /summarize_work (kept for backward compat)
    # ══════════════════════════════════════════════════════════════════════════

    async def summarize_paper(
        self, title: str, doi: Optional[str] = None
    ) -> Dict[str, Any]:
        """Legacy: returns bullets + metrics + top_skills (used by /summarize_work)."""
        paper_data = (
            await self._fetch_openalex_meta(doi=doi) if doi else {"title": title}
        )

        metrics = self.metrics_service.calculate_metrics(paper_data)
        top_skills = self.metrics_service.extract_top_skills(
            paper_data.get("concepts", [])
        )

        if not is_llm_working():
            return {
                "bullets": [],
                "metrics": metrics,
                "top_skills": top_skills,
                "status": "degraded",
                "message": "LLM service is temporarily unavailable.",
            }

        context = f"Title: {title}\n"
        if paper_data.get("abstract"):
            context += f"Abstract: {paper_data['abstract']}\n"
        if paper_data.get("concepts"):
            context += f"Concepts: {', '.join(paper_data['concepts'][:10])}\n"

        try:
            response = await self.llm_service.query(
                messages=[
                    {
                        "role": "system",
                        "content": PAPER_COMMUNICATOR_PROMPT_TEMPLATE,
                    },
                    {"role": "user", "content": context},
                ],
                models=self.models,
                temperature=0.3,
                response_format={"type": "json_object"},
            )

            if response.content:
                raw = response.content
                try:
                    content = json.loads(raw)
                except json.JSONDecodeError:
                    fixed = re.sub(
                        r'(?<!\\)\\(?!["\\\/bfnrt]|u[0-9a-fA-F]{4})',
                        r"\\\\",
                        raw,
                    )
                    content = json.loads(fixed)
                if "bullets" in content:
                    content["bullets"] = [
                        b.replace("\\\\", "\\") for b in content["bullets"]
                    ]
                content["metrics"] = metrics
                content["top_skills"] = top_skills
                return content
        except Exception as e:
            print(f"[summarize_paper] LLM query failed: {e}", flush=True)
            return {
                "bullets": [],
                "metrics": metrics,
                "top_skills": top_skills,
                "status": "degraded",
                "message": "LLM service is temporarily unavailable.",
            }

        return {
            "bullets": [],
            "metrics": metrics,
            "top_skills": top_skills,
            "status": "degraded",
            "message": "LLM service is temporarily unavailable.",
        }

    async def generate_presentation(
        self, title: str, doi: Optional[str] = None
    ) -> Dict[str, Any]:
        """Generates a 7-slide presentation outline (unchanged)."""
        paper_data = await self._fetch_openalex_meta(doi=doi) if doi else {}
        context = f"Title: {title}\n"
        if paper_data.get("abstract"):
            context += f"Abstract: {paper_data['abstract']}\n"
        if paper_data.get("concepts"):
            context += f"Concepts: {', '.join(paper_data['concepts'][:8])}\n"

        try:
            response = await self.llm_service.query(
                messages=[
                    {
                        "role": "system",
                        "content": PRESENTATION_PRESENTER_PROMPT_TEMPLATE,
                    },
                    {"role": "user", "content": context},
                ],
                models=self.models,
                temperature=0.4,
                response_format={"type": "json_object"},
            )
            if response.content:
                return json.loads(response.content)
        except Exception as e:
            print(f"[generate_presentation] Query failed: {e}", flush=True)

        return {"slides": []}

    # ══════════════════════════════════════════════════════════════════════════
    # UTILITIES
    # ══════════════════════════════════════════════════════════════════════════

    def _build_openalex_url(
        self,
        doi: Optional[str] = None,
        openalex_id: Optional[str] = None,
    ) -> Optional[str]:
        if openalex_id:
            work_id = openalex_id.split("/")[-1]
            return f"https://api.openalex.org/works/{work_id}"
        if doi:
            if doi.startswith("http"):
                return f"https://api.openalex.org/works/{doi}"
            return f"https://api.openalex.org/works/https://doi.org/{doi}"
        return None

    @staticmethod
    def _clean_doi(doi: str) -> str:
        """Strips URL prefix from DOI."""
        for prefix in ["https://doi.org/", "http://doi.org/", "doi:"]:
            if doi.startswith(prefix):
                return doi[len(prefix) :]
        return doi

    @staticmethod
    def _extract_arxiv_id(doi_or_url: str) -> Optional[str]:
        """Extracts arXiv ID from a DOI or URL, e.g. '10.48550/arXiv.1706.03762' → '1706.03762'."""
        match = re.search(
            r"arxiv[./: ](\d{4}\.\d{4,5}(?:v\d+)?)",
            doi_or_url,
            re.IGNORECASE,
        )
        return match.group(1) if match else None

    @staticmethod
    def _reconstruct_abstract(
        inverted_index: Optional[Dict[str, List[int]]],
    ) -> Optional[str]:
        """Reconstructs abstract text from OpenAlex inverted index format."""
        if not inverted_index:
            return None
        word_positions: Dict[int, str] = {}
        for word, positions in inverted_index.items():
            for pos in positions:
                word_positions[pos] = word
        if not word_positions:
            return None
        return " ".join(word_positions[i] for i in sorted(word_positions.keys()))
