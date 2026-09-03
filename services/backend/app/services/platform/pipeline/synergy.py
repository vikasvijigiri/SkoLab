import asyncio
import json
from typing import Any, Dict, List, Optional

from sqlalchemy.future import select

from app.prompts import SYNERGY_COUNSELOR_PROMPT_TEMPLATE
from app.services.ai.llm_service import is_llm_working


class SynergyMixin:
    async def get_collaborator_synergy(
        self, author_id: str, collaborator_id: str
    ) -> Dict[str, Any]:
        """
        Generates specific joint proposals and strategic co-authorship pathways between two researchers.
        """
        clean_author = author_id.split("/")[-1]
        clean_collab = collaborator_id.split("/")[-1]
        doc_id = f"{clean_author}_{clean_collab}"
        cache_key = f"collaborator_synergy_{doc_id}"
        cached_data = await self._load_from_postgres(cache_key, ttl_seconds=7200)
        if cached_data:
            print(
                f"[Postgres Cache Hit] collaborator_synergy for doc_id={doc_id}",
                flush=True,
            )
            return cached_data
        _fs_cached = await self._firestore_get_safe(
            "collaborator_synergies", doc_id, timeout=5.0
        )
        if isinstance(_fs_cached, dict):
            print(
                f"[Firestore Cache Hit] collaborator_synergies for doc_id={doc_id}",
                flush=True,
            )
            _fs_cached.pop("last_synced", None)
            await self._save_to_postgres(cache_key, _fs_cached, ttl_seconds=7200)
            return _fs_cached
        profile1_task = self._fetch_author_profile(author_id)
        profile2_task = self._fetch_author_profile(collaborator_id)
        profile1, profile2 = await asyncio.gather(profile1_task, profile2_task)
        # Fallback database lookup for profiles if OpenAlex fails
        if not profile1:
            try:
                from app.models.researcher_models import ResearcherMetrics

                async with self._db_session() as session:
                    stmt = select(ResearcherMetrics).where(
                        ResearcherMetrics.openalex_id == clean_author
                    )
                    res = await session.execute(stmt)
                    rm = res.scalars().first()
                    if rm:
                        profile1 = {
                            "display_name": rm.display_name,
                            "x_concepts": [
                                {"display_name": c, "level": 1}
                                for c in rm.expertise or []
                            ],
                        }
            except Exception as e:
                print(
                    f"[CollaboratorSynergy] DB fallback lookup for author failed: {e}",
                    flush=True,
                )
        if not profile2:
            try:
                from app.models.researcher_models import ResearcherMetrics

                async with self._db_session() as session:
                    stmt = select(ResearcherMetrics).where(
                        ResearcherMetrics.openalex_id == clean_collab
                    )
                    res = await session.execute(stmt)
                    rm = res.scalars().first()
                    if rm:
                        profile2 = {
                            "display_name": rm.display_name,
                            "x_concepts": [
                                {"display_name": c, "level": 1}
                                for c in rm.expertise or []
                            ],
                        }
            except Exception as e:
                print(
                    f"[CollaboratorSynergy] DB fallback lookup for collab failed: {e}",
                    flush=True,
                )
        name1 = (
            profile1.get("display_name", "Researcher A") if profile1 else "Researcher A"
        )
        name2 = (
            profile2.get("display_name", "Researcher B") if profile2 else "Researcher B"
        )

        def extract_concepts(
            profile: Optional[Dict[str, Any]], fallback_name: str
        ) -> List[str]:
            """Extract clean concept list, filtering the researcher's own name (OpenAlex quirk)."""
            if not profile:
                return []
            name_lower = (profile.get("display_name") or "").lower()
            x = profile.get("x_concepts", []) or []
            # Filter self-name concepts
            valid = [
                c
                for c in x
                if c.get("display_name") and c.get("display_name").lower() != name_lower
            ]
            result = [
                c.get("display_name")
                for c in valid
                if c.get("level") in [1, 2] and c.get("display_name")
            ]
            if not result:
                result = [
                    c.get("display_name") for c in valid[:5] if c.get("display_name")
                ]
            if not result:
                topics = profile.get("topics", []) or []
                result = [
                    t.get("display_name") for t in topics[:5] if t.get("display_name")
                ]
            return result

        concepts1 = extract_concepts(profile1, "Quantum Mechanics") or [
            "Quantum Mechanics"
        ]
        concepts2 = extract_concepts(profile2, "Machine Learning") or [
            "Machine Learning"
        ]
        overlap_concepts = list(set(concepts1).intersection(set(concepts2)))

        # Ground the joint proposal/action plan in what each researcher is
        # actually publishing right now, not just their concept-tag lists —
        # the LLM was previously inventing a proposal from tags alone.
        async def _recent_titles(aid: str) -> str:
            try:
                works = await self.openalex_service.fetch_author_works(
                    aid, per_page=3, sort="publication_date:desc"
                )
                titles = [w.get("title") for w in works if w.get("title")]
                return "; ".join(titles) if titles else "No recent papers found."
            except Exception as e:
                print(
                    f"[CollaboratorSynergy] Recent works fetch failed: {e}", flush=True
                )
                return "No recent papers found."

        recent_works1, recent_works2 = await asyncio.gather(
            _recent_titles(clean_author), _recent_titles(clean_collab)
        )
        # Deterministic synergy score based on overlap — no random component
        synergy_score = 72 + min(
            len(overlap_concepts) * 5, 20
        )  # max 92 from overlap alone
        synergy_score = min(max(synergy_score, 70), 99)

        try:
            if not is_llm_working():
                raise Exception("LLM service is currently offline or rate-limited.")
            messages = [
                {
                    "role": "system",
                    "content": SYNERGY_COUNSELOR_PROMPT_TEMPLATE.format(
                        name1=name1,
                        concepts1=", ".join(concepts1[:4]),
                        recent_works1=recent_works1,
                        name2=name2,
                        concepts2=", ".join(concepts2[:4]),
                        recent_works2=recent_works2,
                    ),
                }
            ]
            response = await self.llm_service.query(
                messages=messages,
                models=[self.model],
                temperature=0.3,
                max_tokens=300,
                response_format={"type": "json_object"},
            )
            if response.content:
                data = json.loads(response.content.strip())
                joint_proposal_title = data["joint_proposal_title"]
                co_authorship_direction = data["co_authorship_direction"]
                strategic_action_plan = data["strategic_action_plan"]
            else:
                raise ValueError("LLM returned empty synergy analysis.")
        except Exception as e:
            print(
                f"[CollaboratorSynergy] LLM query failed: {e}. Generating high-quality local fallback...",
                flush=True,
            )
            joint_proposal_title = f"Synergistic Research Framework in {overlap_concepts[0] if overlap_concepts else 'Cross-Disciplinary Studies'}"
            co_authorship_direction = f"A collaborative study between {name1} and {name2} focusing on integrating their respective expertise in {', '.join(concepts1[:2])} and {', '.join(concepts2[:2])}."
            strategic_action_plan = [
                "Establish common datasets and shared repository of code.",
                "Co-draft a preliminary outline targeting a high-impact journal.",
                "Submit a joint seed-grant application to fund the collaborative research.",
            ]

        result = {
            "synergy_score": synergy_score,
            "joint_proposal_title": joint_proposal_title,
            "co_authorship_direction": co_authorship_direction,
            "strategic_action_plan": strategic_action_plan,
        }
        try:
            await self._save_to_postgres(cache_key, result, ttl_seconds=7200)
            print(
                f"[Postgres Cache Save] collaborator_synergy for doc_id={doc_id}",
                flush=True,
            )
        except Exception as e:
            print(
                f"[Postgres Cache Error] collaborator_synergy write failed: {e}",
                flush=True,
            )
        try:
            from firebase_admin import firestore as _fs

            await self._firestore_set_safe(
                "collaborator_synergies",
                doc_id,
                {**result, "last_synced": _fs.SERVER_TIMESTAMP},
            )
        except Exception as e:
            print(
                f"[Firestore Cache Error] collaborator_synergies write failed: {e}",
                flush=True,
            )
        return result
