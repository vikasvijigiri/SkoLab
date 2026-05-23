"""
ResQit Paper Intelligence Engine
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
import os
import random
import json
import re
import io
import asyncio
from typing import Optional, List, Dict, Any, Tuple
import firebase_admin
from firebase_admin import firestore
from .metrics_service import MetricsService


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


# ── Master Prompt ─────────────────────────────────────────────────────────────
RESEARCH_INTELLIGENCE_SYSTEM_PROMPT = r"""
You are a **Research Intelligence Agent** — the world's most precise scientific paper analyst.
Your task is to deeply read the FULL TEXT of a research paper provided by the user and extract
structured intelligence across 9 dimensions. You have access to the actual paper content, not just
the abstract — use this to be MAXIMALLY accurate and specific.

━━━ WHAT TO EXTRACT (9 DIMENSIONS) ━━━

1. **tldr** (string, ≤35 words)
   Plain English. What was achieved? What's the core contribution? Write for a smart non-expert.
   Base this on the paper's own conclusions section, not just the abstract.

2. **key_findings** (list of 4–6 strings)
   The most important discoveries, results, or contributions. For each:
   - Include ACTUAL NUMBERS from the paper (accuracy %, speedup ratios, p-values, etc.)
   - Use **bold** for key terms and concepts
   - Use LaTeX ($$...$$) for formulas, metrics, and variables
   - Start with a scientific emoji (⚛️🧬🔬🧠💡📊🔭🧪⚡🧲🔐🌡️)
   - Be specific — "achieved 97.3% accuracy on ImageNet" not "improved accuracy"

3. **techniques** (list of 4–8 strings)
   Specific methods, algorithms, architectures, statistical tests used IN THIS PAPER.
   Extract from the Methods/Methodology section primarily.
   Examples: "Transformer Self-Attention", "ANOVA Statistical Test", "CRISPR-Cas9 HDR",
   "Variational Autoencoder", "Monte Carlo Tree Search", "K-fold Cross-Validation"
   Plain text, no markdown.

4. **tools_and_software** (list of 2–6 strings)
   Named frameworks, libraries, datasets, instruments, databases, or experimental equipment.
   Examples: "PyTorch 2.0", "ImageNet-21K", "AlphaFold Database", "LIGO Interferometer",
   "Python 3.11", "Jupyter Notebooks", "HuggingFace Transformers", "fMRI (3T Siemens)"
   Extract from Implementation Details / Experimental Setup sections.
   If none are named, write "Not specified".

5. **core_concepts** (list of 3–6 strings)
   Scientific concepts that UNDERPIN this paper — what the reader must know BEFORE reading it.
   These are PREREQUISITES, not what the paper introduces.
   Examples: "Quantum Entanglement", "Gradient Descent", "Hardy-Weinberg Equilibrium",
   "Transformer Architecture", "Protein Folding Energy Landscape"

6. **formulas** (list of 1–4 strings)
   Key mathematical expressions CENTRAL to this paper's contribution.
   CRITICAL JSON RULES FOR LATEX:
   - Wrap in DOUBLE dollar signs: $$E = mc^2$$
   - Use DOUBLE backslashes for ALL LaTeX commands in JSON: $$\\\\sigma$$, $$\\\\nabla L$$
   - Example: "$$\\\\hat{y} = \\\\sigma(W^T x + b)$$"
   If no significant formulas: return empty list.

7. **limitations** (list of 3–4 strings)
   Honest, specific limitations — assumptions made, constraints, things NOT tested.
   Extract from the paper's own Discussion/Limitations section when available.
   Don't be generic ("future work is needed") — be specific to THIS paper.

8. **real_world_impact** (string, 2–4 sentences)
   What concrete problem in the real world does this solve?
   Name specific industries, diseases, engineering challenges, or societal impacts.
   Be honest about timeline and readiness — if it's early-stage research, say so.

9. **future_directions** (list of 3–4 strings)
   Specific experiments, extensions, or open questions raised by THIS paper's findings.
   Extract from the paper's Conclusion/Future Work section when available.

━━━ ACCURACY RULES ━━━
- Use ONLY information present in the paper text provided
- Do NOT hallucinate numbers, results, or tool names not in the text
- If a section is unclear or missing from the text, clearly note "Not mentioned in paper"
- The confidence field reflects how complete the paper text was: High/Medium/Low

━━━ JSON FORMATTING RULES ━━━
- Return VALID JSON only — no markdown, no preamble, no text after the closing brace
- All LaTeX backslashes MUST be DOUBLED inside JSON strings: \\ becomes \\\\
- Wrap all math in double dollar signs $$...$$

━━━ OUTPUT SCHEMA ━━━
{
  "tldr": "string",
  "key_findings": ["string", ...],
  "techniques": ["string", ...],
  "tools_and_software": ["string", ...],
  "core_concepts": ["string", ...],
  "formulas": ["$$...$$", ...],
  "limitations": ["string", ...],
  "real_world_impact": "string",
  "future_directions": ["string", ...],
  "confidence": "High" | "Medium" | "Low"
}
"""


# Shared module-level in-memory cache
_global_intelligence_cache: Dict[str, Dict[str, Any]] = {}
LLM_LIMIT_EXCEEDED = False

def is_llm_working() -> bool:
    global LLM_LIMIT_EXCEEDED
    return bool(os.getenv("GROQ_API")) and not LLM_LIMIT_EXCEEDED

def set_llm_limit_exceeded(exceeded: bool):
    global LLM_LIMIT_EXCEEDED
    LLM_LIMIT_EXCEEDED = exceeded
    print(f"[LLM] LLM_LIMIT_EXCEEDED set to {exceeded}", flush=True)

class SummarizationService:
    def __init__(self):
        self.api_key = os.getenv("GROQ_API")
        self.base_url = "https://api.groq.com/openai/v1/chat/completions"
        self.model = "llama-3.3-70b-versatile"
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
        cache_key = openalex_id or doi or title
        if cache_key in self._intelligence_cache:
            print(f"[analyze_paper] Cache hit for: {cache_key[:60]}", flush=True)
            return self._intelligence_cache[cache_key]

        # Determine unique document ID for this paper to cache in Firestore
        doc_id = None
        if openalex_id:
            doc_id = openalex_id.split("/")[-1]
        elif doi:
            doc_id = self._clean_doi(doi).replace("/", "_")
        else:
            import hashlib
            doc_id = "title_" + hashlib.md5(title.lower().strip().encode("utf-8")).hexdigest()

        # Check Firestore cache first if available
        db = None
        firestore_available = False
        try:
            from researcher_worker import FIRESTORE_AVAILABLE
            firestore_available = FIRESTORE_AVAILABLE
        except Exception:
            firestore_available = bool(firebase_admin._apps)

        if firestore_available and firebase_admin._apps:
            try:
                db = firestore.client()
            except Exception as e:
                print(f"[analyze_paper] Firestore client init failed: {e}", flush=True)

        if db and doc_id:
            try:
                doc_ref = db.collection("paper_intelligence").document(doc_id)
                doc = doc_ref.get(timeout=5.0)
                if doc.exists:
                    result = doc.to_dict()
                    self._intelligence_cache[cache_key] = result
                    print(f"[analyze_paper] Firestore cache hit for: {doc_id}", flush=True)
                    return result
            except Exception as e:
                print(f"[analyze_paper] Firestore cache read error for {doc_id}: {e}", flush=True)

        # ── Step 1: Fetch OpenAlex metadata (always) ──────────────────────────
        print(f"[analyze_paper] Fetching metadata for: {title[:60]}", flush=True)
        meta = await self._fetch_openalex_meta(doi=doi, openalex_id=openalex_id)

        # ── Step 2: Attempt to get the full paper text ────────────────────────
        if not is_llm_working():
            print("[analyze_paper] LLM out of limit/unconfigured. Skipping text extraction and context building.", flush=True)
            result = self._intelligence_fallback(title, meta, "metadata_only")
        else:
            full_text, text_source = await self._fetch_full_paper_text(
                doi=doi,
                openalex_id=openalex_id,
                oa_url=meta.get("oa_url"),
                arxiv_id=meta.get("arxiv_id"),
            )
            # ── Step 3: Build context for LLM ────────────────────────────────────
            context = self._build_context(title, meta, full_text, text_source)
            # ── Step 4: Run LLM ───────────────────────────────────────────────────
            result = await self._run_intelligence_llm(context, title, meta, text_source)

        self._intelligence_cache[cache_key] = result

        # Save to Firestore cache
        if db and doc_id and result:
            try:
                db.collection("paper_intelligence").document(doc_id).set(result, timeout=5.0)
                print(f"[analyze_paper] Cached result to Firestore: {doc_id}", flush=True)
            except Exception as e:
                print(f"[analyze_paper] Firestore cache write error for {doc_id}: {e}", flush=True)

        return result

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
        pdf_url: Optional[str] = None
        source_label = "abstract_only"

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
            import pdfplumber
        except ImportError:
            print("[PDF] pdfplumber not installed. Run: pip install pdfplumber", flush=True)
            return None

        try:
            headers = {
                "User-Agent": (
                    "ResQitApp/1.0 (mailto:vikki.4me@gmail.com) "
                    "Academic research tool - reading open-access papers"
                ),
                "Accept": "application/pdf,*/*",
            }
            async with httpx.AsyncClient(
                headers=headers,
                timeout=httpx.Timeout(2.0, connect=2.0),
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
                    print(f"[PDF] File too small ({len(pdf_bytes)} bytes), skipping", flush=True)
                    return None

            # Extract text in a thread pool to avoid blocking the event loop
            text = await asyncio.get_event_loop().run_in_executor(
                None, self._extract_text_pdfplumber, pdf_bytes
            )

            if not text or len(text.strip()) < 200:
                print("[PDF] Extracted text too short — likely image-based PDF", flush=True)
                return None

            print(f"[PDF] Extracted {len(text.split())} words from PDF", flush=True)
            return text

        except httpx.TimeoutException:
            print(f"[PDF] Download timed out: {url[:60]}", flush=True)
            return None
        except Exception as e:
            # Encode error message safely — PDF titles may contain non-ASCII chars (em-dashes etc.)
            safe_err = str(e).encode('ascii', errors='replace').decode('ascii')
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
                full_text = " ".join(words[:MAX_PAPER_WORDS]) + "\n\n[TEXT TRUNCATED AT 30,000 WORDS]"

            return full_text
        except Exception as e:
            safe_err = str(e).encode('ascii', errors='replace').decode('ascii')
            print(f"[pdfplumber] Extraction error: {safe_err}", flush=True)
            return None

    # ══════════════════════════════════════════════════════════════════════════
    # PDF URL SOURCES
    # ══════════════════════════════════════════════════════════════════════════

    async def _get_unpaywall_pdf_url(self, doi: str) -> Optional[str]:
        """Queries Unpaywall to find a legal open-access PDF URL for a DOI."""
        try:
            clean_doi = self._clean_doi(doi)
            url = f"https://api.unpaywall.org/v2/{clean_doi}?email=vikki.4me@gmail.com"
            async with httpx.AsyncClient(timeout=10.0, follow_redirects=True) as client:
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
            async with httpx.AsyncClient(timeout=10.0) as client:
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
                "User-Agent": "ResQitApp/1.0 (mailto:vikki.4me@gmail.com)",
                "Accept": "application/json",
            }
            async with httpx.AsyncClient(headers=headers, timeout=15.0) as client:
                resp = await client.get(url)
                if resp.status_code != 200:
                    return {}
                data = resp.json()

            abstract = self._reconstruct_abstract(data.get("abstract_inverted_index"))

            # best_oa_location gives the most likely free PDF
            best_oa = data.get("best_oa_location") or {}
            oa_url = best_oa.get("pdf_url") or best_oa.get("landing_page_url")

            # arXiv detection from DOIs or IDs
            arxiv_id = None
            raw_doi = data.get("doi", "") or doi or ""
            arxiv_id = self._extract_arxiv_id(raw_doi)

            topics = [
                t.get("display_name", "")
                for t in data.get("topics", [])[:12]
                if t.get("display_name")
            ]
            concepts = [
                c.get("display_name", "")
                for c in data.get("concepts", [])[:15]
                if c.get("display_name")
            ]
            authors = [
                a.get("author", {}).get("display_name", "")
                for a in data.get("authorships", [])[:8]
                if a.get("author", {}).get("display_name")
            ]

            journal = (
                (data.get("primary_location") or {})
                .get("source", {})
                .get("display_name", "")
            )

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
                "is_open_access": data.get("open_access", {}).get("is_oa", False),
                "journal": journal,
                "doi": data.get("doi", ""),
            }
        except Exception as e:
            print(f"[OpenAlex meta] Error: {e}", flush=True)
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
        """Calls Groq with the Research Intelligence Agent prompt."""
        payload = {
            "model": self.model,
            "messages": [
                {"role": "system", "content": RESEARCH_INTELLIGENCE_SYSTEM_PROMPT},
                {"role": "user", "content": context},
            ],
            "temperature": 0.15,   # Very low — precision over creativity
            "max_tokens": 2048,
            "response_format": {"type": "json_object"},
        }

        try:
            async with httpx.AsyncClient(
                timeout=httpx.Timeout(45.0, connect=8.0)
            ) as client:
                resp = await client.post(
                    self.base_url,
                    headers={
                        "Authorization": f"Bearer {self.api_key}",
                        "Content-Type": "application/json",
                    },
                    json=payload,
                )

            if resp.status_code == 200:
                raw = resp.json()["choices"][0]["message"]["content"]
                print(f"[LLM] Response received ({len(raw)} chars)", flush=True)
                return self._parse_and_normalise(raw, title, meta, text_source)
            else:
                print(f"[LLM] Error {resp.status_code}: {resp.text[:300]}", flush=True)
                if resp.status_code in [401, 403, 429]:
                    set_llm_limit_exceeded(True)

        except httpx.TimeoutException:
            print("[LLM] Groq request timed out", flush=True)
        except Exception as e:
            print(f"[LLM] Exception: {e}", flush=True)

        return self._intelligence_fallback(title, meta, text_source)

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
                    r'\\\\',
                    raw,
                )
                content = json.loads(fixed)
            except Exception:
                print(f"[LLM] JSON parse failed: {raw[:400]}", flush=True)
                return self._intelligence_fallback(title, meta, text_source)

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
        confidence = confidence_map.get(text_source, content.get("confidence", "Medium"))

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
        paper_data = await self._fetch_openalex_meta(doi=doi) if doi else {"title": title}

        metrics = self.metrics_service.calculate_metrics(paper_data)
        top_skills = self.metrics_service.extract_top_skills(
            paper_data.get("concepts", [])
        )

        if not is_llm_working():
            fallback = self._generate_fallback_data(title)
            fallback["top_skills"] = top_skills
            return fallback

        context = f"Title: {title}\n"
        if paper_data.get("abstract"):
            context += f"Abstract: {paper_data['abstract']}\n"
        if paper_data.get("concepts"):
            context += f"Concepts: {', '.join(paper_data['concepts'][:10])}\n"

        payload = {
            "model": self.model,
            "messages": [
                {
                    "role": "system",
                    "content": r"""You are a world-class scientific communicator.
Summarize the provided paper into 4-5 high-impact, technical bullet points.

RULES:
- Use **bold** for key terms.
- Use LaTeX $$...$$ for formulas (double backslash in JSON).
- Start each bullet with a scientific emoji.
- Only use information provided. Do NOT invent numbers.

Return JSON: { "bullets": ["⚛️ ...", ...] }""",
                },
                {"role": "user", "content": context},
            ],
            "temperature": 0.3,
            "response_format": {"type": "json_object"},
        }

        try:
            async with httpx.AsyncClient() as client:
                resp = await client.post(
                    self.base_url,
                    headers={
                        "Authorization": f"Bearer {self.api_key}",
                        "Content-Type": "application/json",
                    },
                    json=payload,
                    timeout=20.0,
                )
            if resp.status_code == 200:
                raw = resp.json()["choices"][0]["message"]["content"]
                try:
                    content = json.loads(raw)
                except json.JSONDecodeError:
                    fixed = re.sub(
                        r'(?<!\\)\\(?!["\\\/bfnrt]|u[0-9a-fA-F]{4})',
                        r'\\\\',
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
        except Exception:
            pass

        fallback = self._generate_fallback_data(title)
        fallback["top_skills"] = top_skills
        return fallback

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

        payload = {
            "model": self.model,
            "messages": [
                {
                    "role": "system",
                    "content": r"""You are an expert academic presenter.
Convert the paper DNA into a professional 7-slide outline.
STRUCTURE: Title, Problem, Methodology, Key Discovery, Complexity, Application, Future.
Each slide: 'title' + 3-4 'bullets'. Use $$LaTeX$$ for formulas.
Return JSON: { "slides": [{ "title": "...", "bullets": ["..."] }] }""",
                },
                {"role": "user", "content": context},
            ],
            "temperature": 0.4,
            "response_format": {"type": "json_object"},
        }
        try:
            async with httpx.AsyncClient() as client:
                resp = await client.post(
                    self.base_url,
                    headers={
                        "Authorization": f"Bearer {self.api_key}",
                        "Content-Type": "application/json",
                    },
                    json=payload,
                    timeout=30.0,
                )
            if resp.status_code == 200:
                return json.loads(resp.json()["choices"][0]["message"]["content"])
        except Exception:
            pass
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
                return doi[len(prefix):]
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
        inverted_index: Optional[Dict[str, List[int]]]
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

    def _intelligence_fallback(
        self,
        title: str,
        meta: Dict[str, Any],
        text_source: str = "abstract_only",
    ) -> Dict[str, Any]:
        concepts = meta.get("concepts", [])
        topics = meta.get("topics", [])
        year = meta.get("year", "")
        cited = meta.get("cited_by_count", 0)

        return {
            "tldr": f"This paper investigates {title.lower()}, contributing novel findings to the field.",
            "key_findings": [
                f"🔬 Presents novel research on **{title}** ({year}).",
                f"📊 Accumulated **{cited}** citations, indicating field impact." if cited else
                "📊 Research impact metrics are being indexed.",
                "💡 Introduces a new theoretical or experimental framework.",
            ],
            "techniques": topics[:4] if topics else concepts[:4],
            "tools_and_software": [],
            "core_concepts": concepts[:5] or ["Scientific Research Methodology"],
            "formulas": [],
            "limitations": [
                "Full paper text not available for deep analysis.",
                "Analysis based on metadata only — accuracy may be limited.",
            ],
            "real_world_impact": (
                f"This research has potential applications in "
                f"{topics[0] if topics else 'the field'}, pending deeper analysis."
            ),
            "future_directions": [
                "Extending findings to broader experimental contexts.",
                "Replication with larger datasets.",
            ],
            "confidence": "Low",
            "text_source": text_source,
        }

    def _generate_fallback_data(self, title: str) -> Dict[str, Any]:
        return {
            "bullets": [
                f"🔬 Investigates the core dynamics of {title.lower()}.",
                "📐 Proposes a specialized framework for theoretical modeling.",
                "📊 Establishes new baselines for experimental verification.",
                "💡 Highlights critical implications for the field's trajectory.",
            ],
            "metrics": {
                "creativity": 0,
                "complexity": 0,
                "skill_set_score": 0,
            },
            "top_skills": ["Theoretical Physics", "Advanced Mathematics"],
        }
