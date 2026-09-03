import asyncio
import datetime
import json
import re
import time
from typing import Any, Dict, List, Optional, Set

import httpx
import numpy as np

from app.core.config import settings
from app.db.pg_cache import PgBackedCache
from app.domains.recommendation.engine import (
    cosine_similarity as vector_cosine_similarity,
    mmr_diversify,
)
from app.prompts import (
    DAILY_FEED_ADVISOR_PROMPT_TEMPLATE,
    METADATA_EXTRACTION_PROMPT_TEMPLATE,
)
from app.services.ai.embedding_service import embed_query, embed_texts
from app.services.ai.llm_service import is_llm_working
from app.services.platform.pipeline.text_utils import (
    _pg_dismissed_recs_cache,
    extract_metadata_from_abstract,
    is_prestigious_journal,
)


class FeedMixin:
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

    def _find_similar_researchers(
        self,
        topic_matched_works: List[Dict[str, Any]],
        exclude_author_id: Optional[str],
    ) -> List[str]:
        """
        Derives a handful of OpenAlex author IDs from the authorships of papers
        that already matched the topic/keyword search — i.e. "who wrote the
        papers that are already known to be on-topic", not an independent
        author-name search (see decisions/0005-similar-researchers-via-authorship.md
        and the shared derive_similar_authors_from_works() helper this delegates
        to, also reused by the author-profile-page and Roadmap similar-researcher
        panels). Entirely generic: driven by whatever candidate works the
        caller's own topic search actually found, no per-user hardcoding.
        """
        from app.services.data.openalex_service import derive_similar_authors_from_works

        candidates = derive_similar_authors_from_works(
            topic_matched_works, exclude_author_id, limit=4
        )
        return [c["id"] for c in candidates]

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

    async def get_dismissed_recommendation_ids(
        self, author_id: Optional[str]
    ) -> Set[str]:
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
                print(
                    f"[Postgres Cache Hit] daily_feeds for doc_id={doc_id}", flush=True
                )
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
                print(
                    f"[Firestore Cache Hit] daily_feeds for doc_id={doc_id}", flush=True
                )
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
            "physics",
            "science",
            "biology",
            "chemistry",
            "mathematics",
            "engineering",
            "computer science",
            "medicine",
            "quantum mechanics",
            "materials science",
            "quantum",
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
            f"[DailyFeed][TIMING] main_gather={time.monotonic() - _t0:.1f}s tasks={len(tasks)} candidates={len(candidates)}",
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
            f"[DailyFeed][TIMING] similar_researchers={time.monotonic() - _t0:.1f}s peers={len(similar_researcher_ids)} candidates={len(candidates)}",
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
            f"[DailyFeed][TIMING] embedding={time.monotonic() - _t0:.1f}s candidates={len(candidate_ids)}",
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
            f"[DailyFeed][TIMING] process_paper_llm={time.monotonic() - _t0:.1f}s papers={len(feed_items)}",
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
