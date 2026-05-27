"""
researcher_worker.py
====================
Background worker that enriches researcher profiles with:
  - All 10 Modern Research Metrics (via MetricsService)
  - LLM-generated "next prediction" (via PredictionService)
  - Firestore persistence for fast subsequent reads

Public API consumed by main.py:
  - teleport_researcher(author_id: str) -> None   [async, background task]
  - check_connection_sync() -> bool               [sync, for startup health check]
  - set_firestore_available(val: bool) -> None
  - FIRESTORE_AVAILABLE: bool                     [module-level flag]
"""

from __future__ import annotations

import asyncio
import logging
import os
import time
from typing import Any, Dict, List, Optional

import httpx

logger = logging.getLogger(__name__)

# ── Firestore availability flag ───────────────────────────────────────────────
FIRESTORE_AVAILABLE: bool = False


def set_firestore_available(val: bool) -> None:
    """Called by the FastAPI startup event with the result of check_connection_sync."""
    global FIRESTORE_AVAILABLE
    FIRESTORE_AVAILABLE = val
    logger.info("[researcher_worker] FIRESTORE_AVAILABLE = %s", val)


def check_connection_sync() -> bool:
    """
    Synchronous Firestore connectivity probe — runs in a ThreadPoolExecutor
    so it does not block the asyncio event loop.

    Returns True if Firestore is reachable, False otherwise.
    """
    try:
        import firebase_admin
        from firebase_admin import firestore as _firestore

        # Ensure the default app is already initialised (done in main startup)
        if not firebase_admin._apps:
            cred_path = os.environ.get("GOOGLE_APPLICATION_CREDENTIALS", "")
            if not cred_path or not os.path.exists(cred_path):
                logger.warning("[researcher_worker] No Firebase credentials found — Firestore disabled.")
                return False
            from firebase_admin import credentials
            firebase_admin.initialize_app(credentials.Certificate(cred_path))

        db = _firestore.client()
        # Lightweight probe: fetch a non-existent document — succeeds even if empty
        db.collection("_health_check").document("probe").get()
        logger.info("[researcher_worker] Firestore connection OK.")
        return True
    except Exception as exc:
        logger.warning("[researcher_worker] Firestore probe failed: %s", exc)
        return False


# ── Internal helpers ──────────────────────────────────────────────────────────

def _get_firestore_client() -> Optional[Any]:
    """Returns a live Firestore client or None if unavailable."""
    if not FIRESTORE_AVAILABLE:
        return None
    try:
        from firebase_admin import firestore as _firestore
        return _firestore.client()
    except Exception as exc:
        logger.warning("[researcher_worker] Could not get Firestore client: %s", exc)
        return None


def _openalex_headers() -> Dict[str, str]:
    """Standard headers for OpenAlex API calls."""
    from app.core.config import settings
    hdrs: Dict[str, str] = {
        "User-Agent": "SkolabApp/1.0 (mailto:vikki.4me@gmail.com)",
        "Accept": "application/json",
    }
    if getattr(settings, "openalex_api_key", None):
        hdrs["api_key"] = settings.openalex_api_key
    return hdrs


async def _fetch_author_from_openalex(author_id: str) -> Optional[Dict[str, Any]]:
    """Fetches the full author record from OpenAlex."""
    clean_id = author_id.split("/")[-1]
    url = f"https://api.openalex.org/authors/{clean_id}"
    try:
        async with httpx.AsyncClient(
            headers=_openalex_headers(),
            timeout=httpx.Timeout(15.0, connect=5.0),
        ) as client:
            resp = await client.get(url, params={"mailto": "vikki.4me@gmail.com"})
            if resp.status_code == 200:
                return resp.json()
            logger.warning("[researcher_worker] OpenAlex author %s → HTTP %s", clean_id, resp.status_code)
    except Exception as exc:
        logger.error("[researcher_worker] Failed to fetch author %s: %s", clean_id, exc)
    return None


async def _fetch_works_from_openalex(
    author_id: str, orcid: Optional[str] = None, max_results: int = 50
) -> List[Dict[str, Any]]:
    """Fetches the author's recent works from OpenAlex."""
    clean_id = author_id.split("/")[-1]
    filter_str = (
        f"authorships.author.orcid:{orcid}"
        if orcid
        else f"authorships.author.id:{clean_id}"
    )
    params = {
        "filter": filter_str,
        "per_page": max_results,
        "sort": "publication_year:desc",
        "mailto": "vikki.4me@gmail.com",
    }
    try:
        async with httpx.AsyncClient(
            headers=_openalex_headers(),
            timeout=httpx.Timeout(20.0, connect=5.0),
        ) as client:
            resp = await client.get("https://api.openalex.org/works", params=params)
            if resp.status_code == 200:
                return resp.json().get("results", [])
    except Exception as exc:
        logger.error("[researcher_worker] Failed to fetch works for %s: %s", clean_id, exc)
    return []


def _reconstruct_abstract(inv_idx: Optional[Dict[str, List[int]]]) -> str:
    """Converts OpenAlex abstract_inverted_index back into a plain string."""
    if not inv_idx or not isinstance(inv_idx, dict):
        return ""
    try:
        word_pos = [
            (pos, word)
            for word, positions in inv_idx.items()
            for pos in positions
        ]
        return " ".join(wp[1] for wp in sorted(word_pos))
    except Exception:
        return ""


def _build_yearly_citations(counts_by_year: List[Dict[str, Any]]) -> List[int]:
    """Returns a list of citation counts sorted by year ascending."""
    sorted_counts = sorted(counts_by_year, key=lambda x: x.get("year", 0))
    return [c.get("cited_by_count", 0) for c in sorted_counts]


def _build_work_dict(raw: Dict[str, Any]) -> Dict[str, Any]:
    """Converts a raw OpenAlex work object into a flat dict for metrics / prediction."""
    primary_loc = raw.get("primary_location") or {}
    source = primary_loc.get("source") or {}
    abstract = _reconstruct_abstract(raw.get("abstract_inverted_index"))
    countries = [
        inst.get("country_code", "")
        for auth in raw.get("authorships", [])
        for inst in auth.get("institutions", [])
        if inst.get("country_code")
    ]
    concepts = raw.get("concepts", [])
    topic_counts: Dict[str, int] = {}
    for c in concepts:
        name = c.get("display_name")
        if name:
            topic_counts[name] = topic_counts.get(name, 0) + 1

    return {
        "id": raw.get("id", ""),
        "title": raw.get("title") or "",
        "year": raw.get("publication_year"),
        "doi": raw.get("doi"),
        "journal": source.get("display_name"),
        "is_open_access": bool((raw.get("open_access") or {}).get("is_oa")),
        "citations": raw.get("cited_by_count", 0),
        "abstract": abstract,
        "countries": countries,
        "topic_counts": topic_counts,
        "concepts": [c.get("display_name") for c in concepts if c.get("display_name")],
        "oa": bool((raw.get("open_access") or {}).get("is_oa")),
        "preprint": bool((raw.get("primary_location") or {}).get("is_accepted") is False),
        # Fields populated later by LLM
        "creativity_score": 0.0,
        "complexity_score": 0.0,
        "impact_factor": round(float(source.get("2yr_mean_citedness") or 0.0), 2),
        "disruption_score": 0.0,
        "semantic_novelty": 0.0,
        "open_science_score": 0.0,
    }


# ── Core public function ──────────────────────────────────────────────────────

async def teleport_researcher(author_id: str) -> None:
    """
    Background task: fetches a researcher's full profile from OpenAlex,
    computes all 10 research metrics, generates an LLM "next prediction",
    and persists the enriched record to Firestore.

    Called from:
      - main.py /search_author  (queued via background_tasks)
      - main.py /refresh_author (forced re-run)

    Never raises — all errors are logged and swallowed so the background
    task does not crash the FastAPI worker.
    """
    start_ts = time.perf_counter()
    clean_id = author_id.split("/")[-1]
    logger.info("[teleport] Starting enrichment for author: %s", clean_id)

    try:
        # ── 1. Fetch author profile from OpenAlex ─────────────────────────────
        author_data = await _fetch_author_from_openalex(author_id)
        if not author_data:
            logger.warning("[teleport] No OpenAlex data for %s — aborting.", clean_id)
            return

        display_name: str = author_data.get("display_name") or "Unknown"
        orcid: Optional[str] = author_data.get("orcid")
        stats: Dict[str, Any] = author_data.get("summary_stats") or {}
        h_index: int = int(stats.get("h_index") or 0)
        i10_index: int = int(stats.get("i10_index") or 0)
        works_count: int = int(author_data.get("works_count") or 0)
        cited_by_count: int = int(author_data.get("cited_by_count") or 0)

        # Institution
        last_insts: List[Dict] = author_data.get("last_known_institutions") or []
        institution = "Independent Researcher"
        if last_insts and isinstance(last_insts[0], dict):
            institution = last_insts[0].get("display_name") or institution

        # Field & expertise
        concepts_raw: List[Dict] = author_data.get("x_concepts") or []
        field = next(
            (c.get("display_name") for c in concepts_raw if c.get("level") == 1),
            concepts_raw[0].get("display_name") if concepts_raw else "Multidisciplinary",
        )
        expertise = [
            c.get("display_name")
            for c in concepts_raw
            if c.get("level") in [1, 2] and c.get("display_name")
        ][:6]

        # Academic history
        affiliations: List[Dict] = author_data.get("affiliations") or []
        hist_map: Dict[str, List[int]] = {}
        for aff in affiliations:
            inst_obj = aff.get("institution") or {}
            inst_name = inst_obj.get("display_name")
            years = aff.get("years") or []
            if inst_name and years:
                existing = hist_map.get(inst_name)
                if existing is None:
                    hist_map[inst_name] = [min(years), max(years)]
                else:
                    hist_map[inst_name] = [
                        min(existing[0], min(years)),
                        max(existing[1], max(years)),
                    ]
        academic_history = [
            f"{n} ({y[0]}–{y[1]})" if y[0] != y[1] else f"{n} ({y[0]})"
            for n, y in sorted(hist_map.items(), key=lambda x: x[1][0])
        ]

        # Yearly citation trend (for metric calculations)
        counts_by_year: List[Dict] = author_data.get("counts_by_year") or []
        yearly_citations = _build_yearly_citations(counts_by_year)

        # ── 2. Fetch works ────────────────────────────────────────────────────
        raw_works = await _fetch_works_from_openalex(author_id, orcid=orcid, max_results=50)
        works = [_build_work_dict(w) for w in raw_works if (w.get("title") or "").strip()]

        # ── 3. Compute the 10 Modern Research Metrics ─────────────────────────
        from app.services.metrics_service import MetricsService
        ms = MetricsService()

        # Aggregate topic counts across all works
        all_topic_counts: Dict[str, int] = {}
        for w in works:
            for topic, cnt in w.get("topic_counts", {}).items():
                all_topic_counts[topic] = all_topic_counts.get(topic, 0) + cnt

        # All country codes across all works
        all_countries = [c for w in works for c in w.get("countries", [])]

        # N1 / N2 / N3 for disruption (requires citation graph — approximate from raw data)
        # N1 = papers that cite this author but NOT their references (disruptors)
        # N2 = papers that cite both this author AND their references (consolidators)
        # N3 = papers that only cite references but not this author (developers)
        # Without a full citation graph we approximate via works_count distribution
        total_citing = cited_by_count
        n1 = max(int(total_citing * 0.4), 1)
        n2 = max(int(total_citing * 0.35), 1)
        n3 = max(int(total_citing * 0.25), 1)

        # Journal h-index proxy for future_impact
        journal_score = float(stats.get("2yr_mean_citedness") or 2.0)

        disruption_score   = ms.calculate_disruption_score(n1, n2, n3)
        citation_accel     = ms.calculate_citation_acceleration(yearly_citations)
        future_impact      = ms.calculate_future_impact(
            early_citations=works[0].get("citations", 0) if works else 0,
            journal_score=journal_score,
            h_index=h_index,
        )
        interdisciplinary  = ms.calculate_interdisciplinary_index(all_topic_counts)
        policy_patent      = ms.calculate_policy_patent_score(
            policy_cites=0, patent_cites=0  # OpenAlex does not expose these directly
        )
        open_science       = ms.calculate_open_science_score(
            code=False,
            data=False,
            oa=bool((author_data.get("open_access") or {}) and cited_by_count > 0),
            preprint=any(w.get("preprint") for w in works),
        )
        collab_diversity   = ms.calculate_collaboration_diversity(all_countries)
        research_consist   = ms.calculate_research_consistency(yearly_citations)

        # Semantic novelty — requires embeddings; approximate via interdisciplinary spread
        semantic_novelty   = round(min(interdisciplinary * 0.9 + disruption_score * 10, 100.0), 1)

        # Network centrality — approximated via h-index percentile proxy
        network_centrality = round(min(h_index * 2.5, 100.0), 1)

        # Per-work metrics (creativity = novelty proxy, complexity = interdisciplinary)
        avg_creativity = semantic_novelty
        avg_complexity = interdisciplinary
        avg_skill      = open_science
        avg_impact     = round(min(float(cited_by_count) / max(works_count, 1), 100.0), 1)
        avg_activity   = round(min(float(works_count) / 10.0 * 100, 100.0), 1)

        # Innovation score (composite of all 10)
        innovation_score = round(
            (
                abs(disruption_score) * 10
                + min(abs(citation_accel), 10)
                + future_impact * 0.5
                + network_centrality * 0.3
                + semantic_novelty * 0.3
                + interdisciplinary * 0.2
                + collab_diversity * 0.2
                + research_consist * 0.1
                + open_science * 0.1
            ),
            1,
        )

        logger.info(
            "[teleport] Metrics computed for %s — disruption=%.3f, novelty=%.1f, impact=%.1f",
            display_name,
            disruption_score,
            semantic_novelty,
            future_impact,
        )

        # ── 4. LLM: next research prediction ─────────────────────────────────
        next_prediction: Optional[str] = None
        from app.services.summarization_service import is_llm_working

        if is_llm_working() and works:
            try:
                from app.services.prediction_service import PredictionService
                ps = PredictionService()
                # Convert works to the format PredictionService expects
                works_for_pred = [
                    {
                        "title": w.get("title", ""),
                        "year": w.get("year"),
                        "citations": w.get("citations", 0),
                        "abstract": w.get("abstract", ""),
                    }
                    for w in works[:10]
                ]
                next_prediction = await ps.predict_next_problem(
                    author_name=display_name,
                    expertise=expertise or ["Research"],
                    works=works_for_pred,
                )
                logger.info("[teleport] Next prediction generated for %s", display_name)
            except Exception as pred_exc:
                logger.warning("[teleport] Prediction failed for %s: %s", display_name, pred_exc)

        # ── 5. Persist to Firestore ───────────────────────────────────────────
        db = _get_firestore_client()
        if db is None:
            logger.info(
                "[teleport] Firestore unavailable — enrichment computed but not persisted for %s",
                clean_id,
            )
        else:
            # Serialise works (only plain-JSON-safe fields)
            works_payload = [
                {
                    "id":                w.get("id", ""),
                    "title":             w.get("title", ""),
                    "year":              w.get("year"),
                    "doi":               w.get("doi"),
                    "journal":           w.get("journal"),
                    "is_open_access":    w.get("is_open_access", False),
                    "citations":         w.get("citations", 0),
                    "creativity_score":  avg_creativity,
                    "complexity_score":  avg_complexity,
                    "impact_factor":     w.get("impact_factor", 0.0),
                    "disruption_score":  disruption_score,
                    "semantic_novelty":  semantic_novelty,
                    "open_science_score": open_science,
                }
                for w in works[:50]
            ]

            doc_payload: Dict[str, Any] = {
                # Identity
                "openalex_id":            author_data.get("id", author_id),
                "display_name":           display_name,
                "orcid":                  orcid,
                # Core stats
                "h_index":                h_index,
                "i10_index":              i10_index,
                "works_count":            works_count,
                "cited_by_count":         cited_by_count,
                "current_institution":    institution,
                "field_of_study":         field,
                "expertise":              expertise,
                "academic_history":       academic_history,
                "works":                  works_payload,
                # Aggregated averages
                "average_creativity":     avg_creativity,
                "average_complexity":     avg_complexity,
                "average_skill_score":    avg_skill,
                "average_impact":         avg_impact,
                "average_activity":       avg_activity,
                # The 10 Modern Metrics
                "disruption_score":       disruption_score,
                "citation_acceleration":  float(citation_accel),
                "future_impact_score":    future_impact,
                "network_centrality":     network_centrality,
                "semantic_novelty":       semantic_novelty,
                "interdisciplinary_index": interdisciplinary,
                "policy_patent_score":    float(policy_patent),
                "open_science_score":     float(open_science),
                "collaboration_diversity": collab_diversity,
                "research_consistency":   research_consist,
                # Composite
                "innovation_score":       innovation_score,
                # LLM
                "next_prediction":        next_prediction,
                "metrics_computed":       True,
                # Housekeeping
                "last_teleported":        time.time(),
            }

            try:
                from firebase_admin import firestore as _fs
                db.collection("global_researchers").document(clean_id).set(
                    doc_payload, merge=True
                )
                elapsed = round(time.perf_counter() - start_ts, 2)
                logger.info(
                    "[teleport] ✓ Persisted %s to Firestore in %.2fs", display_name, elapsed
                )
            except Exception as fs_exc:
                logger.error("[teleport] Firestore write failed for %s: %s", clean_id, fs_exc)

    except Exception as exc:
        logger.exception("[teleport] Unhandled error for author %s: %s", author_id, exc)
