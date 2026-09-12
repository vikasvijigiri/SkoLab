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


async def _compute_researcher_metrics_via_gateway(
    *,
    n1: int,
    n2: int,
    n3: int,
    yearly_citations: List[int],
    early_citations: int,
    journal_score: float,
    h_index: int,
    topic_counts: Dict[str, int],
    policy_cites: int,
    patent_cites: int,
    has_code: bool,
    has_data: bool,
    is_open_access: bool,
    has_preprint: bool,
    countries: List[str],
) -> Dict[str, Any]:
    """POST the Go gateway's /internal/compute_metrics for the 8 metrics this
    worker used to compute with its own duplicate of that same math
    (app/services/platform/metrics_service.py's MetricsService, retired from
    this call site in the 2026-09-12 no-slop audit -- MetricsService itself
    stays, since /analyze_paper's paper-level scoring still uses it).

    Falls back to safe zero-value defaults on any failure (gateway down,
    timeout, bad response) so a Go outage degrades this one enrichment pass
    rather than crashing the whole teleport worker -- matching this worker's
    existing degrade-and-log pattern for its other optional steps (LLM
    prediction, skills/tools extraction).
    """
    import httpx

    from app.core.config import settings

    fallback = {
        "disruption_score": 0.0,
        "citation_acceleration": 0,
        "future_impact_score": 0.0,
        "interdisciplinary_index": 0.0,
        "policy_patent_score": 0,
        "open_science_score": 0,
        "collaboration_diversity": 0.0,
        "research_consistency": 0.0,
    }
    headers = {}
    if settings.internal_api_token:
        headers["X-Internal-Token"] = settings.internal_api_token

    try:
        async with httpx.AsyncClient(timeout=10.0) as client:
            resp = await client.post(
                f"{settings.gateway_url.rstrip('/')}/internal/compute_metrics",
                json={
                    "n1": n1,
                    "n2": n2,
                    "n3": n3,
                    "yearly_citations": yearly_citations,
                    "early_citations": early_citations,
                    "journal_score": journal_score,
                    "h_index": h_index,
                    "topic_counts": topic_counts,
                    "policy_cites": policy_cites,
                    "patent_cites": patent_cites,
                    "has_code": has_code,
                    "has_data": has_data,
                    "is_open_access": is_open_access,
                    "has_preprint": has_preprint,
                    "countries": countries,
                },
                headers=headers,
            )
            resp.raise_for_status()
            return resp.json()
    except Exception as exc:
        logger.warning(
            "[teleport] Gateway metrics compute failed, using zero-value fallback: %s",
            exc,
        )
        return fallback


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
    from app.db.database import AsyncSessionLocal, execute_with_row_retry
    from app.models.researcher_models import ResearcherMetrics
    from sqlalchemy.future import select

    now = datetime.datetime.now(datetime.timezone.utc).replace(tzinfo=None)
    expires_at = now + datetime.timedelta(days=7)

    async with AsyncSessionLocal() as session:
        try:
            result = await execute_with_row_retry(
                session,
                select(ResearcherMetrics).where(
                    ResearcherMetrics.openalex_id == clean_id
                ),
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
                exc_info=True,
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


# ── pgvector persistence — similarity engine store ──────────────────────────
# work_embeddings / author_embeddings (see alembic b2c3d4e5f6a7). bge-small
# 384-d vectors, read by the Go gateway's /similar_papers and
# /similar_researchers. Postgres-only: on the SQLite dev/CI fallback the tables
# do not exist and every statement here is swallowed by the try/except, which
# is the intended degradation (the engine falls back to OpenAlex).


def _clean_oa_id(raw: str) -> str:
    """`https://openalex.org/W123` / `W123` → `W123`."""
    if not raw:
        return ""
    return raw.rstrip("/").split("/")[-1]


def _vec_literal(vec) -> str:
    """numpy row / list[float] → pgvector text literal `[0.1,0.2,...]`."""
    return "[" + ",".join(f"{float(x):.7f}" for x in vec) + "]"


async def _pg_upsert_embeddings(
    clean_id: str,
    works: List[Dict[str, Any]],
    raw_works: List[Dict[str, Any]],
    author_data: Dict[str, Any],
    field: str,
    expertise: List[str],
    institution: str,
    h_index: int,
    works_count: int,
) -> None:
    """Embed up to 25 recent works (title + abstract) and upsert
    `work_embeddings`; store the L2-normalised mean as the author vector in
    `author_embeddings`. Never raises."""
    import numpy as np
    from sqlalchemy import text as _sql

    from app.db.database import AsyncSessionLocal
    from app.services.ai.embedding_service import embed_texts

    # Pair each built work-dict with its raw OpenAlex record (for
    # referenced_works + authorships, which _build_work_dict drops).
    raw_by_id = {_clean_oa_id(r.get("id", "")): r for r in raw_works}
    picked: List[Dict[str, Any]] = []
    for w in works:
        wid = _clean_oa_id(w.get("id", ""))
        if not wid:
            continue
        text_blob = f"{w.get('title', '')} {w.get('abstract', '') or ''}".strip()
        if not text_blob:
            continue
        picked.append(
            {"wid": wid, "text": text_blob, "w": w, "raw": raw_by_id.get(wid, {})}
        )
        if len(picked) >= 25:
            break

    if not picked:
        logger.info(
            "[teleport] no embeddable works for %s — skipping vectors", clean_id
        )
        return

    try:
        vectors = await embed_texts([p["text"] for p in picked])
    except Exception as exc:
        logger.warning("[teleport] embed_texts failed for %s: %s", clean_id, exc)
        return

    vectors = np.asarray(vectors, dtype=np.float32)
    if vectors.ndim != 2 or vectors.shape[0] != len(picked):
        logger.warning(
            "[teleport] unexpected embedding shape for %s: %s", clean_id, vectors.shape
        )
        return

    # Author vector = L2-normalised mean of its work vectors. A zero mean
    # (all-miss embeddings) is left as-is; the reader treats it as no signal.
    mean_vec = vectors.mean(axis=0)
    norm = float(np.linalg.norm(mean_vec))
    if norm > 1e-9:
        mean_vec = mean_vec / norm

    coauthors: set[str] = set()
    for p in picked:
        for a in p["raw"].get("authorships") or []:
            aid = _clean_oa_id((a.get("author") or {}).get("id", ""))
            if aid and aid != clean_id:
                coauthors.add(aid)

    # Every param is explicitly CAST: asyncpg cannot infer the type of a bound
    # NULL or an empty list, and errors with "could not determine data type of
    # parameter" otherwise.
    work_sql = _sql(
        """
        INSERT INTO work_embeddings
            (work_id, embedding, title, concepts, referenced_works, publication_year, updated_at)
        VALUES
            (:work_id, CAST(:embedding AS vector), CAST(:title AS text),
             CAST(:concepts AS text[]), CAST(:referenced_works AS text[]),
             CAST(:year AS integer), now())
        ON CONFLICT (work_id) DO UPDATE SET
            embedding = EXCLUDED.embedding,
            title = EXCLUDED.title,
            concepts = EXCLUDED.concepts,
            referenced_works = EXCLUDED.referenced_works,
            publication_year = EXCLUDED.publication_year,
            updated_at = now()
        """
    )
    author_sql = _sql(
        """
        INSERT INTO author_embeddings
            (author_id, embedding, concepts, coauthor_ids, institution, works_count, h_index, updated_at)
        VALUES
            (:author_id, CAST(:embedding AS vector), CAST(:concepts AS text[]),
             CAST(:coauthor_ids AS text[]), CAST(:institution AS text),
             CAST(:works_count AS integer), CAST(:h_index AS integer), now())
        ON CONFLICT (author_id) DO UPDATE SET
            embedding = EXCLUDED.embedding,
            concepts = EXCLUDED.concepts,
            coauthor_ids = EXCLUDED.coauthor_ids,
            institution = EXCLUDED.institution,
            works_count = EXCLUDED.works_count,
            h_index = EXCLUDED.h_index,
            updated_at = now()
        """
    )

    async with AsyncSessionLocal() as session:
        try:
            for p, vec in zip(picked, vectors):
                w = p["w"]
                refs = [
                    _clean_oa_id(r)
                    for r in (p["raw"].get("referenced_works") or [])
                    if r
                ]
                await session.execute(
                    work_sql,
                    {
                        "work_id": p["wid"],
                        "embedding": _vec_literal(vec),
                        "title": (w.get("title") or "")[:2000],
                        "concepts": [c for c in (w.get("concepts") or []) if c][:25],
                        "referenced_works": refs[:200],
                        "year": w.get("year"),
                    },
                )
            await session.execute(
                author_sql,
                {
                    "author_id": clean_id,
                    "embedding": _vec_literal(mean_vec),
                    "concepts": ([field] if field else [])
                    + [e for e in (expertise or []) if e][:24],
                    "coauthor_ids": sorted(coauthors)[:200],
                    "institution": institution or None,
                    "works_count": int(works_count or 0),
                    "h_index": int(h_index or 0),
                },
            )
            await session.commit()
            logger.info(
                "[teleport] PG embeddings saved for %s (%d works)",
                clean_id,
                len(picked),
            )
        except Exception as exc:
            logger.warning(
                "[teleport] PG embeddings write failed for %s: %s", clean_id, exc
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
        # 8 of the 10 are pure math with no Python-only dependency (LLM, DB,
        # OpenAlex client) -- computed by the Go gateway's own port of this
        # same math instead of duplicating it here (2026-09-12 no-slop audit).
        # semantic_novelty and network_centrality below are this worker's own
        # inline proxy formulas, not MetricsService methods, so they're
        # untouched by this change either way.
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

        # No policy/patent citation data source is integrated yet (would need e.g. Overton
        # or Lens.org) — this always evaluates to 0 for every researcher, not a real score.
        # Kept as a stub so the field exists for when that integration lands; the frontend
        # should not present this as a measured value in the meantime.
        gw_metrics = await _compute_researcher_metrics_via_gateway(
            n1=n1,
            n2=n2,
            n3=n3,
            yearly_citations=yearly_citations,
            early_citations=works[0].get("citations", 0) if works else 0,
            journal_score=journal_score,
            h_index=h_index,
            topic_counts=all_topic_counts,
            policy_cites=0,
            patent_cites=0,
            has_code=False,
            has_data=False,
            is_open_access=bool(cited_by_count > 0),
            has_preprint=any(w.get("preprint") for w in works),
            countries=all_countries,
        )
        disruption_score = gw_metrics["disruption_score"]
        citation_accel = gw_metrics["citation_acceleration"]
        future_impact = gw_metrics["future_impact_score"]
        interdisciplinary = gw_metrics["interdisciplinary_index"]
        policy_patent = gw_metrics["policy_patent_score"]
        open_science = gw_metrics["open_science_score"]
        collab_diversity = gw_metrics["collaboration_diversity"]
        research_consist = gw_metrics["research_consistency"]
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

        # ── 4b. LLM: skills/tools — distinct from `expertise` (topic tags) ────
        # compute_author_metrics already derives this from paper titles/
        # concepts; previously only exposed via the orphaned GET /author_metrics
        # endpoint (Android-only) and never persisted. Computed once here,
        # alongside the other one-time-per-enrichment LLM calls, instead of
        # live per page load.
        skills: List[str] = []
        tools: List[str] = []
        if is_llm_working() and works:
            try:
                from app.services.platform.metrics_service import compute_author_metrics
                from app.services.data.openalex_service import OpenAlexService

                author_metrics = await compute_author_metrics(
                    clean_id, openalex_service=OpenAlexService()
                )
                skills = author_metrics.get("skills") or []
                tools = author_metrics.get("tools") or []
                logger.info("[teleport] Skills/tools computed for %s", display_name)
            except Exception as skills_exc:
                logger.warning(
                    "[teleport] Skills/tools computation failed for %s: %s",
                    display_name,
                    skills_exc,
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
                "skills": skills,
                "tools": tools,
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

        # ── 5d. pgvector — similarity engine store (best-effort) ──────────────
        await _pg_upsert_embeddings(
            clean_id,
            works,
            raw_works,
            author_data,
            field,
            expertise or [],
            institution,
            h_index,
            works_count,
        )

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
                "skills": skills,
                "tools": tools,
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
