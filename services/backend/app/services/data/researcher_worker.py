"""
researcher_worker.py
====================
Background worker that enriches researcher profiles with:
  - All 10 Modern Research Metrics (via MetricsService)
  - LLM-generated "next prediction" (via PredictionService)

Storage strategy (per-task):
  ┌─────────────────────────────────────────┬──────────────────────┐
  │ Data                                    │ Store                │
  ├─────────────────────────────────────────┼──────────────────────┤
  │ Researcher metadata (name, h-index,     │ PostgreSQL           │
  │   field, institution)                   │ researcher_metrics   │
  │   → fast name/field search + suggestion │                      │
  ├─────────────────────────────────────────┼──────────────────────┤
  │ Full enriched document (all 10 metrics  │ Firestore            │
  │   + works array + next_prediction)      │ global_researchers   │
  │   → large doc, cloud, unlimited space   │                      │
  └─────────────────────────────────────────┴──────────────────────┘

Read order for search_author:
  1. PG researcher_metrics  (local, sub-ms)
  2. Firestore global_researchers (cloud, enriched full doc)
  3. OpenAlex live compute (source of truth)
"""

from __future__ import annotations

import datetime
import logging
import time
from pathlib import Path
from typing import Any, Dict, List, Optional

logger = logging.getLogger(__name__)

# ── Firestore availability flag ───────────────────────────────────────────────
FIRESTORE_AVAILABLE: bool = False
_BACKEND_ROOT = Path(__file__).resolve().parents[2]


def _resolve_credentials_path(raw_path: str) -> Optional[Path]:
    if not raw_path:
        return None
    candidate = Path(raw_path).expanduser()
    if candidate.is_absolute() and candidate.exists():
        return candidate

    for base in (Path.cwd(), _BACKEND_ROOT, Path(__file__).resolve().parent):
        resolved = (base / candidate).resolve()
        if resolved.exists():
            return resolved
    return None


def set_firestore_available(val: bool) -> None:
    global FIRESTORE_AVAILABLE
    FIRESTORE_AVAILABLE = val
    logger.info("[researcher_worker] FIRESTORE_AVAILABLE = %s", val)


def check_connection_sync() -> bool:
    """
    Synchronous Firestore connectivity probe — runs in a ThreadPoolExecutor.
    Returns True if Firestore is reachable, False otherwise.
    """
    try:
        import firebase_admin
        from firebase_admin import firestore as _firestore

        if not firebase_admin._apps:
            import os

            cred_path = _resolve_credentials_path(
                os.environ.get("GOOGLE_APPLICATION_CREDENTIALS", "")
            )
            if cred_path is None:
                logger.warning(
                    "[researcher_worker] No Firebase credentials — Firestore disabled."
                )
                return False
            from firebase_admin import credentials

            firebase_admin.initialize_app(credentials.Certificate(str(cred_path)))

        db = _firestore.client()
        # Force a network request to verify credentials
        db.collection("daily_feeds").document("ping_test").get(timeout=2.0)
        logger.info("[researcher_worker] Firestore client initialized and verified.")
        return True
    except Exception as exc:
        logger.warning("[researcher_worker] Firestore probe failed: %s", exc)
        return False


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


# ── Internal helpers ──────────────────────────────────────────────────────────


async def _fetch_author_from_openalex(author_id: str) -> Optional[Dict[str, Any]]:
    from app.services.data.openalex_service import OpenAlexService

    try:
        return await OpenAlexService().fetch_author_by_id(author_id)
    except Exception as exc:
        logger.error(
            "[researcher_worker] Failed to fetch author %s: %s", author_id, exc
        )
    return None


async def _fetch_works_from_openalex(
    author_id: str, orcid: Optional[str] = None, max_results: int = 50
) -> List[Dict[str, Any]]:
    from app.services.data.openalex_service import OpenAlexService

    try:
        return await OpenAlexService().fetch_author_works(
            author_id=author_id, orcid=orcid, per_page=max_results
        )
    except Exception as exc:
        logger.error(
            "[researcher_worker] Failed to fetch works for %s: %s", author_id, exc
        )
    return []


def _reconstruct_abstract(inv_idx: Optional[Dict[str, List[int]]]) -> str:
    if not inv_idx or not isinstance(inv_idx, dict):
        return ""
    try:
        word_pos = [
            (pos, word) for word, positions in inv_idx.items() for pos in positions
        ]
        return " ".join(wp[1] for wp in sorted(word_pos))
    except Exception:
        return ""


def _build_yearly_citations(counts_by_year: List[Dict[str, Any]]) -> List[int]:
    return [
        c.get("cited_by_count", 0)
        for c in sorted(counts_by_year, key=lambda x: x.get("year", 0))
    ]


def _build_work_dict(raw: Dict[str, Any]) -> Dict[str, Any]:
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
        "preprint": bool(
            (raw.get("primary_location") or {}).get("is_accepted") is False
        ),
        "creativity_score": 0.0,
        "complexity_score": 0.0,
        "impact_factor": round(float(source.get("2yr_mean_citedness") or 0.0), 2),
        "disruption_score": 0.0,
        "semantic_novelty": 0.0,
        "open_science_score": 0.0,
    }


# ── PostgreSQL persistence — lightweight metadata for fast search ─────────────


async def _pg_upsert_researcher_metrics(clean_id: str, payload: Dict[str, Any]) -> None:
    """
    Store researcher metadata in PostgreSQL for fast local queries.
    Only searchable/filterable fields — NOT the large works array.
    Suitable for: author suggestion lookups, name search, field filtering.
    """
    from app.db.database import AsyncSessionLocal
    from app.models.researcher_models import ResearcherMetrics
    from sqlalchemy.future import select

    now = datetime.datetime.now(datetime.timezone.utc).replace(tzinfo=None)
    expires_at = now + datetime.timedelta(days=7)

    async with AsyncSessionLocal() as session:
        try:
            result = await session.execute(
                select(ResearcherMetrics).where(
                    ResearcherMetrics.openalex_id == clean_id
                )
            )
            row = result.scalars().first()
            fields = {k: v for k, v in payload.items()}
            fields["last_synced"] = now
            fields["expires_at"] = expires_at

            if row:
                for k, v in fields.items():
                    if hasattr(row, k):
                        setattr(row, k, v)
            else:
                row = ResearcherMetrics(openalex_id=clean_id, **fields)
                session.add(row)
            await session.commit()
            logger.info("[teleport] PG researcher_metrics saved for %s", clean_id)
        except Exception as exc:
            logger.error(
                "[teleport] PG researcher_metrics write failed for %s: %s",
                clean_id,
                exc,
            )
            await session.rollback()


async def _pg_upsert_researcher_works(
    clean_id: str, works: List[Dict[str, Any]]
) -> None:
    """
    Store researcher works in PostgreSQL researcher_works table.
    Deletes old entries and inserts new ones within a single transaction.
    """
    from app.db.database import AsyncSessionLocal
    from app.models.researcher_models import ResearcherWork
    from sqlalchemy import delete as sa_delete
    import datetime

    now = datetime.datetime.now(datetime.timezone.utc).replace(tzinfo=None)
    expires_at = now + datetime.timedelta(days=7)

    async with AsyncSessionLocal() as session:
        try:
            # Delete old works for this author
            await session.execute(
                sa_delete(ResearcherWork).where(
                    ResearcherWork.author_openalex_id == clean_id
                )
            )

            # Add new works
            for w in works[:50]:
                rw = ResearcherWork(
                    author_openalex_id=clean_id,
                    work_openalex_id=w.get("id", "").split("/")[-1] or w.get("id", ""),
                    title=w.get("title") or "Untitled",
                    publication_year=w.get("year"),
                    doi=w.get("doi"),
                    journal=w.get("journal"),
                    is_open_access=w.get("is_open_access", False),
                    citations=w.get("citations", 0),
                    abstract=w.get("abstract"),
                    concepts=w.get("concepts"),
                    countries=w.get("countries"),
                    impact_factor=w.get("impact_factor", 0.0),
                    creativity_score=w.get("creativity_score", 0.0),
                    complexity_score=w.get("complexity_score", 0.0),
                    disruption_score=w.get("disruption_score", 0.0),
                    semantic_novelty=w.get("semantic_novelty", 0.0),
                    open_science_score=w.get("open_science_score", 0.0),
                    expires_at=expires_at,
                )
                session.add(rw)
            await session.commit()
            logger.info("[teleport] PG researcher_works saved for %s", clean_id)
        except Exception as exc:
            logger.error(
                "[teleport] PG researcher_works write failed for %s: %s", clean_id, exc
            )
            await session.rollback()


# ── Firestore persistence — full enriched document (large, cloud, unlimited) ──


def _firestore_save_researcher(clean_id: str, doc_payload: Dict[str, Any]) -> None:
    """
    Persist the full enriched researcher document (including works array, all metrics,
    and LLM prediction) to Firestore.
    Best suited here because: large JSON doc, infrequent writes, cold reads,
    cloud-accessible, no disk space concern.
    """
    db = _get_firestore_client()
    if db is None:
        logger.info(
            "[teleport] Firestore unavailable — skipping cloud persist for %s", clean_id
        )
        return
    try:
        db.collection("global_researchers").document(clean_id).set(
            doc_payload, merge=True
        )
        logger.info("[teleport] Firestore global_researchers saved for %s", clean_id)
    except Exception as exc:
        logger.error("[teleport] Firestore write failed for %s: %s", clean_id, exc)


# ── Core public function ──────────────────────────────────────────────────────


async def teleport_researcher(author_id: str) -> None:
    """
    Background task: enriches a researcher profile and persists to BOTH stores:
      - PostgreSQL: searchable metadata (name, h-index, field, institution, scores)
      - Firestore:  full doc (works array, all 10 metrics, LLM prediction)

    Never raises — all errors are logged so the background task can't crash FastAPI.
    """
    start_ts = time.perf_counter()
    clean_id = author_id.split("/")[-1]
    logger.info("[teleport] Starting enrichment for author: %s", clean_id)

    try:
        # ── 1. Fetch from OpenAlex ────────────────────────────────────────────
        author_data = await _fetch_author_from_openalex(author_id)
        if not author_data:
            logger.warning("[teleport] No OpenAlex data for %s — aborting.", clean_id)
            return

        display_name: str = author_data.get("display_name") or ""
        if not display_name.strip() or display_name.lower().strip() in [
            "unknown",
            "anonymous",
        ]:
            logger.warning(
                "[teleport] Dropping academic profile %s: missing or placeholder display name.",
                clean_id,
            )
            return

        last_insts: List[Dict] = author_data.get("last_known_institutions") or []
        if not last_insts or not any(
            inst.get("display_name") for inst in last_insts if isinstance(inst, dict)
        ):
            logger.warning(
                "[teleport] Dropping academic profile %s: missing last known institution.",
                clean_id,
            )
            return

        institution = last_insts[0].get("display_name") or "Independent Researcher"
        orcid: Optional[str] = author_data.get("orcid")
        stats: Dict[str, Any] = author_data.get("summary_stats") or {}
        h_index: int = int(stats.get("h_index") or 0)
        i10_index: int = int(stats.get("i10_index") or 0)
        works_count: int = int(author_data.get("works_count") or 0)
        cited_by_count: int = int(author_data.get("cited_by_count") or 0)

        from app.services.data.openalex_service import extract_field_and_expertise

        field, expertise = extract_field_and_expertise(author_data, display_name)

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

        counts_by_year: List[Dict] = author_data.get("counts_by_year") or []
        yearly_citations = _build_yearly_citations(counts_by_year)

        # ── 2. Fetch works ────────────────────────────────────────────────────
        raw_works = await _fetch_works_from_openalex(
            author_id, orcid=orcid, max_results=50
        )
        works = [
            _build_work_dict(w) for w in raw_works if (w.get("title") or "").strip()
        ]

        # ── 3. Compute the 10 Modern Research Metrics ─────────────────────────
        from app.services.platform.metrics_service import MetricsService

        ms = MetricsService()

        all_topic_counts: Dict[str, int] = {}
        for w in works:
            for topic, cnt in w.get("topic_counts", {}).items():
                all_topic_counts[topic] = all_topic_counts.get(topic, 0) + cnt

        all_countries = [c for w in works for c in w.get("countries", [])]
        total_citing = cited_by_count
        n1 = max(int(total_citing * 0.4), 1)
        n2 = max(int(total_citing * 0.35), 1)
        n3 = max(int(total_citing * 0.25), 1)
        journal_score = float(stats.get("2yr_mean_citedness") or 2.0)

        disruption_score = ms.calculate_disruption_score(n1, n2, n3)
        citation_accel = ms.calculate_citation_acceleration(yearly_citations)
        future_impact = ms.calculate_future_impact(
            early_citations=works[0].get("citations", 0) if works else 0,
            journal_score=journal_score,
            h_index=h_index,
        )
        interdisciplinary = ms.calculate_interdisciplinary_index(all_topic_counts)
        # No policy/patent citation data source is integrated yet (would need e.g. Overton
        # or Lens.org) — this always evaluates to 0 for every researcher, not a real score.
        # Kept as a stub so the field exists for when that integration lands; the frontend
        # should not present this as a measured value in the meantime.
        policy_patent = ms.calculate_policy_patent_score(policy_cites=0, patent_cites=0)
        open_science = ms.calculate_open_science_score(
            code=False,
            data=False,
            oa=bool(cited_by_count > 0),
            preprint=any(w.get("preprint") for w in works),
        )
        collab_diversity = ms.calculate_collaboration_diversity(all_countries)
        research_consist = ms.calculate_research_consistency(yearly_citations)
        semantic_novelty = round(
            min(interdisciplinary * 0.9 + disruption_score * 10, 100.0), 1
        )
        network_centrality = round(min(h_index * 2.5, 100.0), 1)

        # These aren't independent measurements — they're aliases of the metrics above,
        # kept for API/schema backward-compatibility. Don't present them as distinct
        # signals in the UI (e.g. alongside "Novelty"/"Interdisciplinary") since they're
        # always numerically identical to those fields.
        avg_creativity = semantic_novelty
        avg_complexity = interdisciplinary
        avg_skill = open_science
        avg_impact = round(min(float(cited_by_count) / max(works_count, 1), 100.0), 1)
        avg_activity = round(min(float(works_count) / 10.0 * 100, 100.0), 1)

        innovation_score = round(
            abs(disruption_score) * 10
            + min(abs(citation_accel), 10)
            + future_impact * 0.5
            + network_centrality * 0.3
            + semantic_novelty * 0.3
            + interdisciplinary * 0.2
            + collab_diversity * 0.2
            + research_consist * 0.1
            + open_science * 0.1,
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
        from app.services.ai.summarization_service import is_llm_working

        if is_llm_working() and works:
            try:
                from app.services.ai.prediction_service import PredictionService

                ps = PredictionService()
                next_prediction = await ps.predict_next_problem(
                    author_name=display_name,
                    expertise=expertise or ["Research"],
                    works=[
                        {
                            "title": w.get("title", ""),
                            "year": w.get("year"),
                            "citations": w.get("citations", 0),
                            "abstract": w.get("abstract", ""),
                        }
                        for w in works[:10]
                    ],
                )
                logger.info("[teleport] Next prediction generated for %s", display_name)
            except Exception as pred_exc:
                logger.warning(
                    "[teleport] Prediction failed for %s: %s", display_name, pred_exc
                )

        # ── 5a. PostgreSQL — fast searchable metadata ─────────────────────────
        # Stored here so author suggestions and search_author can resolve instantly
        # without hitting the network (Firestore).
        await _pg_upsert_researcher_metrics(
            clean_id,
            {
                "display_name": display_name,
                "orcid": orcid,
                "h_index": h_index,
                "i10_index": i10_index,
                "works_count": works_count,
                "cited_by_count": cited_by_count,
                "current_institution": institution,
                "field_of_study": field,
                "expertise": expertise,
                "academic_history": academic_history,
                "average_creativity": avg_creativity,
                "average_complexity": avg_complexity,
                "average_skill_score": avg_skill,
                "average_impact": avg_impact,
                "average_activity": avg_activity,
                "disruption_score": disruption_score,
                "citation_acceleration": float(citation_accel),
                "future_impact_score": future_impact,
                "network_centrality": network_centrality,
                "semantic_novelty": semantic_novelty,
                "interdisciplinary_index": interdisciplinary,
                "policy_patent_score": float(policy_patent),
                "open_science_score": float(open_science),
                "collaboration_diversity": collab_diversity,
                "research_consistency": research_consist,
                "innovation_score": innovation_score,
                "next_prediction": next_prediction,
                "metrics_computed": True,
                "last_teleported": time.time(),
            },
        )

        # Update in-memory works dictionary with all computed metrics
        for w in works:
            w["creativity_score"] = avg_creativity
            w["complexity_score"] = avg_complexity
            w["disruption_score"] = disruption_score
            w["semantic_novelty"] = semantic_novelty
            w["open_science_score"] = open_science

        # ── 5c. PostgreSQL — fast local researcher works caching ──────────────
        await _pg_upsert_researcher_works(clean_id, works)

        # ── 5b. Firestore — full enriched document with works array ───────────
        # Stored here because the works array can be very large (50 items × fields).
        # Firestore handles large documents better; also cloud-accessible.
        works_payload = [
            {
                "id": w.get("id", ""),
                "title": w.get("title", ""),
                "year": w.get("year"),
                "doi": w.get("doi"),
                "journal": w.get("journal"),
                "is_open_access": w.get("is_open_access", False),
                "citations": w.get("citations", 0),
                "creativity_score": avg_creativity,
                "complexity_score": avg_complexity,
                "impact_factor": w.get("impact_factor", 0.0),
                "disruption_score": disruption_score,
                "semantic_novelty": semantic_novelty,
                "open_science_score": open_science,
            }
            for w in works[:50]
        ]

        _firestore_save_researcher(
            clean_id,
            {
                "openalex_id": author_data.get("id", author_id),
                "display_name": display_name,
                "orcid": orcid,
                "h_index": h_index,
                "i10_index": i10_index,
                "works_count": works_count,
                "cited_by_count": cited_by_count,
                "current_institution": institution,
                "field_of_study": field,
                "expertise": expertise,
                "academic_history": academic_history,
                "works": works_payload,
                "average_creativity": avg_creativity,
                "average_complexity": avg_complexity,
                "average_skill_score": avg_skill,
                "average_impact": avg_impact,
                "average_activity": avg_activity,
                "disruption_score": disruption_score,
                "citation_acceleration": float(citation_accel),
                "future_impact_score": future_impact,
                "network_centrality": network_centrality,
                "semantic_novelty": semantic_novelty,
                "interdisciplinary_index": interdisciplinary,
                "policy_patent_score": float(policy_patent),
                "open_science_score": float(open_science),
                "collaboration_diversity": collab_diversity,
                "research_consistency": research_consist,
                "innovation_score": innovation_score,
                "next_prediction": next_prediction,
                "metrics_computed": True,
                "last_teleported": time.time(),
            },
        )

        elapsed = round(time.perf_counter() - start_ts, 2)
        logger.info(
            "[teleport] ✓ Enrichment complete for %s in %.2fs", display_name, elapsed
        )

    except Exception as exc:
        logger.exception("[teleport] Unhandled error for author %s: %s", author_id, exc)
