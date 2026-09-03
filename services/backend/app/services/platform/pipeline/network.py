import asyncio
import datetime
from typing import Any, Dict, List, Optional, Set

import httpx
from sqlalchemy.future import select

from app.core.config import settings
from app.services.platform.pipeline.text_utils import is_field_semantically_relevant


class NetworkMixin:
    async def _resolve_author_id_by_name(self, name: str, field: str) -> Optional[str]:
        """Resolves an author name and discipline to an OpenAlex ID."""
        try:
            results = await self.openalex_service.search_authors(name, per_page=10)
            if not results:
                return None

            # Filter candidates that actually match the name tokens first
            name_matches = []
            query_tokens = [tok for tok in name.lower().split() if len(tok) > 2]
            for cand in results:
                cand_name = cand.get("display_name", "").lower()
                if not query_tokens or all(tok in cand_name for tok in query_tokens):
                    name_matches.append(cand)

            best_cand = None
            norm_field = field.lower() if field else ""
            if norm_field:
                for cand in name_matches:
                    concepts = cand.get("x_concepts", []) or []
                    concept_names = [
                        c.get("display_name", "").lower() for c in concepts
                    ]
                    if any(
                        norm_field in c_name or c_name in norm_field
                        for c_name in concept_names
                    ):
                        best_cand = cand
                        break

            if not best_cand and name_matches:
                best_cand = name_matches[0]
            if not best_cand:
                best_cand = results[0]

            return best_cand["id"] if best_cand else None
        except Exception as e:
            print(
                f"[Dynamic Resolve Error] Failed to resolve '{name}' on OpenAlex: {e}",
                flush=True,
            )
        return None

    def _compute_jaccard_similarity(self, list1: List[str], list2: List[str]) -> float:
        if not list1 or not list2:
            return 0.0
        set1 = {x.strip().lower() for x in list1 if x.strip()}
        set2 = {x.strip().lower() for x in list2 if x.strip()}
        if not set1 or not set2:
            return 0.0

        exact_intersection = set1.intersection(set2)
        partial_matches = 0.0
        for u in set1:
            if u in exact_intersection:
                continue
            for c in set2:
                if u in c or c in u:
                    partial_matches += 0.5
                    break

        overlap = len(exact_intersection) + partial_matches
        union_size = len(set1.union(set2))
        if union_size == 0:
            return 0.0
        return min(1.0, overlap / union_size)

    def _process_depth1_authorship(
        self,
        auth_ship: Dict[str, Any],
        work_title: str,
        work_field: str,
        work_concepts: List[str],
        exclude_set: Set[str],
        depth1_authors: Dict[str, Any],
    ) -> None:
        """Processes a single authorship entry for depth 1 collaborators."""
        author_meta = auth_ship.get("author", {})
        auth_id = author_meta.get("id")
        if not auth_id:
            return
        auth_clean = auth_id.split("/")[-1]
        if auth_clean in exclude_set:
            return
        name = author_meta.get("display_name", "Unknown")
        insts = auth_ship.get("institutions", [])
        inst_name = insts[0].get("display_name") if insts else "Independent Researcher"
        if auth_id not in depth1_authors:
            depth1_authors[auth_id] = {
                "id": auth_id,
                "name": name,
                "institution": inst_name,
                "field": work_field,
                "shared_paper": work_title,
                "joint_count": 1,
                "work_concepts": work_concepts,
            }
        else:
            depth1_authors[auth_id]["joint_count"] += 1

    def _process_depth1_work(
        self,
        work: Dict[str, Any],
        exclude_set: Set[str],
        depth1_authors: Dict[str, Any],
    ) -> None:
        """Processes a single publication to extract depth 1 co-authors."""
        work_title = work.get("title", "Research Paper")
        concepts = work.get("concepts", [])
        work_concepts = [
            c.get("display_name") for c in concepts if c.get("display_name")
        ]
        work_field = work_concepts[0] if work_concepts else "Researcher"
        for auth_ship in work.get("authorships", []):
            self._process_depth1_authorship(
                auth_ship=auth_ship,
                work_title=work_title,
                work_field=work_field,
                work_concepts=work_concepts,
                exclude_set=exclude_set,
                depth1_authors=depth1_authors,
            )

    def _process_depth2_authorship(
        self,
        d1: Dict[str, Any],
        auth_ship: Dict[str, Any],
        work_field: str,
        work_concepts: List[str],
        exclude_set: Set[str],
        depth1_authors: Dict[str, Any],
        depth2_authors: Dict[str, Any],
        primary_name: str,
    ) -> None:
        """Processes a single authorship entry for depth 2 collaborators."""
        author_meta = auth_ship.get("author", {})
        auth_id = author_meta.get("id")
        if not auth_id:
            return
        auth_clean = auth_id.split("/")[-1]
        if (
            auth_clean in exclude_set
            or auth_id in depth1_authors
            or auth_id in depth2_authors
        ):
            return
        name = author_meta.get("display_name", "Unknown")
        insts = auth_ship.get("institutions", [])
        inst_name = insts[0].get("display_name") if insts else "Independent Researcher"
        depth2_authors[auth_id] = {
            "id": auth_id,
            "name": name,
            "institution": inst_name,
            "field": work_field,
            "connection_path": f"Collaborates with {d1['name']} (connected via {primary_name})",
            "joint_count": 1,
            "d1_parent_name": d1["name"],
            "work_concepts": work_concepts,
        }

    def _process_depth2_work(
        self,
        d1: Dict[str, Any],
        work: Dict[str, Any],
        exclude_set: Set[str],
        depth1_authors: Dict[str, Any],
        depth2_authors: Dict[str, Any],
        primary_name: str,
    ) -> None:
        """Processes a single depth 2 publication to extract depth 2 co-authors."""
        concepts = work.get("concepts", [])
        work_concepts = [
            c.get("display_name") for c in concepts if c.get("display_name")
        ]
        work_field = work_concepts[0] if work_concepts else "Expert Collaborator"
        for auth_ship in work.get("authorships", []):
            self._process_depth2_authorship(
                d1=d1,
                auth_ship=auth_ship,
                work_field=work_field,
                work_concepts=work_concepts,
                exclude_set=exclude_set,
                depth1_authors=depth1_authors,
                depth2_authors=depth2_authors,
                primary_name=primary_name,
            )

    def _process_depth2_works(
        self,
        d1: Dict[str, Any],
        works: Any,
        exclude_set: Set[str],
        depth1_authors: Dict[str, Any],
        depth2_authors: Dict[str, Any],
        primary_name: str,
    ) -> None:
        """Processes works for a specific depth 1 collaborator to find depth 2 connections."""
        if not isinstance(works, list):
            return
        for work in works:
            self._process_depth2_work(
                d1=d1,
                work=work,
                exclude_set=exclude_set,
                depth1_authors=depth1_authors,
                depth2_authors=depth2_authors,
                primary_name=primary_name,
            )

    async def get_network_collaborators(
        self,
        author_id: str,
        limit: int = 10,
        offset: int = 0,
        exclude_ids: List[str] = None,
        field: str = "",
        name: str = "",
    ) -> List[Dict[str, Any]]:
        """
        Fetches Depth 1 and Depth 2 co-author connections.
        Cache strategy (fastest-first):
          1. ResearcherConnection table (PostgreSQL) — instant, with 24-hour TTL.
          2. CacheEntry blob (legacy key) — retained for backwards compat.
          3. Full OpenAlex computation — stores results in both above for next time.
        """
        clean_id = author_id.split("/")[-1]
        if (not clean_id or clean_id == "fallback_seed") and name:
            print(
                f"[Dynamic Resolve] Attempting dynamic resolution for '{name}' with discipline '{field}' on OpenAlex...",
                flush=True,
            )
            resolved_id = await self._resolve_author_id_by_name(name, field)
            if resolved_id:
                author_id = resolved_id
                clean_id = author_id.split("/")[-1]
                print(
                    f"[Dynamic Resolve] Successfully resolved '{name}' to OpenAlex ID: {author_id} ({clean_id})",
                    flush=True,
                )

        if not clean_id or clean_id == "fallback_seed":
            raise ValueError(
                "No valid author ID provided for collaborator network extraction."
            )
        from app.models.user_models import ResearcherConnection, ResearcherProfile
        from sqlalchemy import delete

        now = datetime.datetime.now(datetime.timezone.utc).replace(tzinfo=None)
        CONNECTION_TTL_HOURS = 24
        PROFILE_TTL_DAYS = 7
        # ── 1. Fast path: read from ResearcherConnection table ────────────────
        async with self._db_session() as session:
            try:
                stmt = (
                    select(ResearcherConnection)
                    .where(
                        ResearcherConnection.author_openalex_id == clean_id,
                        ResearcherConnection.expires_at > now,
                    )
                    .order_by(ResearcherConnection.relevance_score.desc())
                )
                result = await session.execute(stmt)
                cached_rows = result.scalars().all()
                if cached_rows:
                    print(
                        f"[DB Fast Path] ResearcherConnection hit: {len(cached_rows)} rows for {clean_id}",
                        flush=True,
                    )
                    exclude_set_fast = set(exclude_ids or [])
                    exclude_set_fast.add(clean_id)
                    all_rows = [
                        {
                            "id": row.connection_openalex_id,
                            "name": row.connection_name,
                            "institution": row.connection_institution
                            or "Independent Researcher",
                            "field": row.connection_field or "Researcher",
                            "connection_path": row.connection_path or "",
                            "relevance_score": row.relevance_score,
                            "papers_collaborated": row.papers_collaborated,
                            "total_publications": row.total_publications,
                            "h_index": row.h_index,
                        }
                        for row in cached_rows
                        if row.connection_openalex_id.split("/")[-1]
                        not in exclude_set_fast
                    ]
                    # Apply field filter if requested
                    if field:
                        all_rows = [
                            r
                            for r in all_rows
                            if is_field_semantically_relevant(
                                r["field"], r["connection_path"], field
                            )
                        ]
                    return all_rows[offset : offset + limit]
            except Exception as e:
                print(
                    f"[DB Fast Path Error] ResearcherConnection read failed: {e}",
                    flush=True,
                )
        # ── 2. Legacy CacheEntry blob (cache_key fallback) ────────────────────
        cache_key = f"network_collaborators_{clean_id}_{field}"
        cached_blob = await self._load_from_postgres(cache_key)
        if isinstance(cached_blob, dict) and "collaborators" in cached_blob:
            print(
                f"[Postgres Blob Hit] network_collaborators for author_id={clean_id}",
                flush=True,
            )
            collaborators = cached_blob["collaborators"]
            if exclude_ids:
                ex = set(exclude_ids)
                collaborators = [
                    c for c in collaborators if c["id"].split("/")[-1] not in ex
                ]
            return collaborators[offset : offset + limit]
        # ── 3. Full OpenAlex computation with robust fallback ─────────────────
        exclude_set = set(exclude_ids or [])
        exclude_set.add(clean_id)
        try:
            profile = await self._fetch_author_profile(author_id)
            if not profile:
                raise ValueError(f"Author with ID '{author_id}' not found on OpenAlex.")
            primary_name = profile.get("display_name", "Main Author")
            # Persist the primary author's profile
            await self._upsert_researcher_profile(profile, PROFILE_TTL_DAYS)
            target_fields: List[str] = []
            if field:
                target_fields = [
                    f.strip().lower() for f in field.split(",") if f.strip()
                ]
            else:
                target_fields = [
                    c.get("display_name", "").lower()
                    for c in profile.get("x_concepts", [])
                    if c.get("display_name")
                ]
                if not target_fields:
                    try:
                        from app.models.researcher_models import ResearcherMetrics

                        async with self._db_session() as session:
                            stmt = select(ResearcherMetrics).where(
                                ResearcherMetrics.openalex_id == clean_id
                            )
                            res = await session.execute(stmt)
                            rm = res.scalars().first()
                            if rm and rm.field_of_study:
                                target_fields = [rm.field_of_study.lower()]
                    except Exception:
                        pass

            def is_relevant_collaborator(candidate_fields: List[str]) -> bool:
                if not field:
                    return True
                for cf in candidate_fields:
                    if is_field_semantically_relevant(cf, "", field):
                        return True
                return False

            async def fetch_works_for_author(
                auth_clean_id: str, max_works: int = 20
            ) -> List[Dict[str, Any]]:
                try:
                    works = await self.openalex_service.fetch_author_works(
                        auth_clean_id, per_page=max_works
                    )
                    if field:
                        from app.services.data.openalex_service import (
                            is_work_relevant_to_discipline,
                        )

                        works = [
                            w for w in works if is_work_relevant_to_discipline(w, field)
                        ]
                    return works
                except Exception:
                    return []

            d1_works = await fetch_works_for_author(clean_id, 100)
            if not d1_works:
                raise ValueError(
                    f"No publications found for author '{primary_name}' ({clean_id}) on OpenAlex."
                )
            # Stably sort works by citations count, then by year
            d1_works = sorted(
                d1_works,
                key=lambda w: (
                    w.get("cited_by_count", 0),
                    w.get("publication_year", 0),
                ),
                reverse=True,
            )
            depth1_authors: Dict[str, Any] = {}
            for work in d1_works:
                self._process_depth1_work(work, exclude_set, depth1_authors)
            depth2_authors: Dict[str, Any] = {}
            # Stably sort top direct co-authors by joint collaboration count descending first
            d1_list = sorted(
                depth1_authors.values(), key=lambda x: x["joint_count"], reverse=True
            )[:10]
            d2_tasks = [
                fetch_works_for_author(d1["id"].split("/")[-1], 20) for d1 in d1_list
            ]
            d2_results = await asyncio.gather(*d2_tasks, return_exceptions=True)
            for d1, works in zip(d1_list, d2_results):
                self._process_depth2_works(
                    d1=d1,
                    works=works,
                    exclude_set=exclude_set,
                    depth1_authors=depth1_authors,
                    depth2_authors=depth2_authors,
                    primary_name=primary_name,
                )
            # Batch-fetch h-index and works_count for all discovered authors
            all_ids = [
                v["id"].split("/")[-1]
                for v in list(depth1_authors.values()) + list(depth2_authors.values())
            ]
            real_stats: Dict[str, Any] = {}
            if all_ids:
                try:

                    async def fetch_stats_chunk(chunk: List[str]) -> None:
                        filter_str = "openalex:" + "|".join(chunk)
                        async with httpx.AsyncClient(
                            timeout=settings.http_timeout_seconds
                        ) as client:
                            res = await client.get(
                                "https://api.openalex.org/authors",
                                params={
                                    "filter": filter_str,
                                    "per_page": 50,
                                    "mailto": self.openalex_service.email,
                                },
                            )
                            if res.status_code == 200:
                                for a in res.json().get("results", []):
                                    aid_short = a["id"].split("/")[-1]
                                    real_stats[aid_short] = {
                                        "works_count": a.get("works_count", 0),
                                        "h_index": a.get("summary_stats", {}).get(
                                            "h_index", 0
                                        ),
                                        "concepts": [
                                            c.get("display_name", "")
                                            for c in a.get("x_concepts", [])
                                            if c.get("display_name")
                                        ],
                                        "institution": (
                                            a.get("last_known_institutions") or [{}]
                                        )[0].get(
                                            "display_name", "Independent Researcher"
                                        ),
                                        "raw_profile": a,
                                    }

                    stat_tasks = [
                        fetch_stats_chunk(all_ids[i : i + 50])
                        for i in range(0, len(all_ids), 50)
                    ]
                    await asyncio.gather(*stat_tasks, return_exceptions=True)
                except Exception as e:
                    print(f"[Stats Batch Error] {e}", flush=True)
                # ── Fallback to database for missing stats ───────────────────
                missing_ids = [aid for aid in all_ids if aid not in real_stats]
                if missing_ids:
                    async with self._db_session() as session:
                        try:
                            profile_ids = missing_ids + [
                                f"https://openalex.org/{mid}" for mid in missing_ids
                            ]
                            stmt_profile = select(ResearcherProfile).where(
                                ResearcherProfile.openalex_id.in_(profile_ids)
                            )
                            res_p = await session.execute(stmt_profile)
                            for rp in res_p.scalars().all():
                                aid_short = rp.openalex_id.split("/")[-1]
                                real_stats[aid_short] = {
                                    "works_count": rp.works_count or 0,
                                    "h_index": rp.h_index or 0,
                                    "concepts": rp.concepts or [],
                                    "institution": rp.institution
                                    or "Independent Researcher",
                                    "raw_profile": rp.raw_profile,
                                }
                            still_missing = [
                                aid for aid in missing_ids if aid not in real_stats
                            ]
                            if still_missing:
                                from app.models.researcher_models import (
                                    ResearcherMetrics,
                                )

                                metrics_ids = still_missing + [
                                    f"https://openalex.org/{mid}"
                                    for mid in still_missing
                                ]
                                stmt_metrics = select(ResearcherMetrics).where(
                                    ResearcherMetrics.openalex_id.in_(metrics_ids)
                                )
                                res_m = await session.execute(stmt_metrics)
                                for rm in res_m.scalars().all():
                                    aid_short = rm.openalex_id.split("/")[-1]
                                    real_stats[aid_short] = {
                                        "works_count": rm.works_count or 0,
                                        "h_index": rm.h_index or 0,
                                        "concepts": rm.expertise or [],
                                        "institution": rm.current_institution
                                        or "Independent Researcher",
                                        "raw_profile": None,
                                    }
                        except Exception as db_exc:
                            print(f"[DB Stats Fallback Error] {db_exc}", flush=True)
            collaborators_pool: List[Dict[str, Any]] = []
            for auth_id, d1 in depth1_authors.items():
                auth_id_short = auth_id.split("/")[-1]
                stats = real_stats.get(auth_id_short, {})
                total_pubs = stats.get("works_count", 0)
                h_idx = stats.get("h_index", 0)
                author_concepts = stats.get("concepts", [])
                cand_concepts = d1.get("work_concepts", []) + author_concepts
                if not is_relevant_collaborator(cand_concepts):
                    continue
                if stats.get("raw_profile"):
                    await self._upsert_researcher_profile(
                        stats["raw_profile"], PROFILE_TTL_DAYS
                    )

                similarity = self._compute_jaccard_similarity(
                    target_fields, cand_concepts
                )
                relevance_val = min(
                    99, max(80, int(80 + similarity * 40 + (d1["joint_count"] * 2)))
                )
                rec = {
                    "id": auth_id,
                    "name": d1["name"],
                    "institution": d1["institution"],
                    "field": d1.get("field") or "Researcher",
                    "connection_path": f"Co-authored '{d1['shared_paper']}' with {primary_name}",
                    "relevance_score": relevance_val,
                    "papers_collaborated": d1["joint_count"],
                    "total_publications": total_pubs,
                    "h_index": h_idx,
                    "depth": 1,
                }
                collaborators_pool.append(rec)
            for auth_id, d2 in depth2_authors.items():
                auth_id_short = auth_id.split("/")[-1]
                stats = real_stats.get(auth_id_short, {})
                total_pubs = stats.get("works_count", 0)
                h_idx = stats.get("h_index", 0)
                author_concepts = stats.get("concepts", [])
                cand_concepts = d2.get("work_concepts", []) + author_concepts
                if not is_relevant_collaborator(cand_concepts):
                    continue
                if stats.get("raw_profile"):
                    await self._upsert_researcher_profile(
                        stats["raw_profile"], PROFILE_TTL_DAYS
                    )

                similarity = self._compute_jaccard_similarity(
                    target_fields, cand_concepts
                )
                relevance_val = min(99, max(60, int(60 + similarity * 100)))
                rec = {
                    "id": auth_id,
                    "name": d2["name"],
                    "institution": d2["institution"],
                    "field": d2["field"],
                    "connection_path": d2["connection_path"],
                    "relevance_score": relevance_val,
                    "papers_collaborated": 0,
                    "total_publications": total_pubs,
                    "h_index": h_idx,
                    "depth": 2,
                }
                collaborators_pool.append(rec)
            collaborators_pool.sort(key=lambda x: x["relevance_score"], reverse=True)
            if collaborators_pool:
                expires_at = now + datetime.timedelta(hours=CONNECTION_TTL_HOURS)
                async with self._db_session() as session:
                    try:
                        await session.execute(
                            delete(ResearcherConnection).where(
                                ResearcherConnection.author_openalex_id == clean_id
                            )
                        )
                        for rec in collaborators_pool:
                            row = ResearcherConnection(
                                author_openalex_id=clean_id,
                                connection_openalex_id=rec["id"],
                                connection_name=rec["name"],
                                connection_institution=rec["institution"],
                                connection_field=rec["field"],
                                depth=rec.get("depth", 2),
                                connection_path=rec["connection_path"],
                                relevance_score=rec["relevance_score"],
                                papers_collaborated=rec.get("papers_collaborated", 1),
                                total_publications=rec.get("total_publications"),
                                h_index=rec.get("h_index"),
                                last_synced=now,
                                expires_at=expires_at,
                            )
                            session.add(row)
                        await session.commit()
                        print(
                            f"[DB Save] {len(collaborators_pool)} ResearcherConnection rows saved for {clean_id}",
                            flush=True,
                        )
                    except Exception as e:
                        print(
                            f"[DB Save Error] ResearcherConnection write failed: {e}",
                            flush=True,
                        )
                        await session.rollback()
                await self._save_to_postgres(
                    cache_key, {"collaborators": collaborators_pool}
                )
                print(
                    f"[Postgres Blob Save] network_collaborators cached for {clean_id}",
                    flush=True,
                )
        except Exception as exc:
            print(
                f"[NetworkCollaborators] OpenAlex computation failed: {exc}", flush=True
            )
            raise ValueError(
                f"Failed to fetch network collaborators from OpenAlex: {exc}"
            ) from exc
        filtered_final = [
            c for c in collaborators_pool if c["id"].split("/")[-1] not in exclude_set
        ]
        return filtered_final[offset : offset + limit]
