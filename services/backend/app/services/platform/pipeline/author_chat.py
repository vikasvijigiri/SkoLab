import datetime
import re
from typing import Any, Dict, List, Optional

from sqlalchemy.future import select

from app.models.user_models import AgentChatHistory
from app.prompts import AUTHOR_CHAT_SYSTEM_PROMPT_TEMPLATE
from app.services.ai.llm_service import is_llm_working


class AuthorChatMixin:
    async def _upsert_researcher_profile(
        self, openalex_author: Dict[str, Any], ttl_days: int = 7
    ) -> None:
        """
        Upsert a researcher's profile into the ResearcherProfile table.
        Skips gracefully if the author dict is missing the 'id' key.
        """
        from app.models.user_models import ResearcherProfile

        auth_id = openalex_author.get("id")
        if not auth_id:
            return
        clean = auth_id.split("/")[-1]
        insts = openalex_author.get("last_known_institutions") or []
        inst_name = (
            insts[0].get("display_name", "Independent Researcher")
            if insts
            else "Independent Researcher"
        )
        from app.services.data.openalex_service import extract_field_and_expertise

        field, concepts = extract_field_and_expertise(
            openalex_author, openalex_author.get("display_name", "Researcher")
        )
        stats = openalex_author.get("summary_stats") or {}
        now = datetime.datetime.now(datetime.timezone.utc).replace(tzinfo=None)
        expires_at = now + datetime.timedelta(days=ttl_days)
        async with self._db_session() as session:
            try:
                stmt = select(ResearcherProfile).where(
                    ResearcherProfile.openalex_id == clean
                )
                result = await session.execute(stmt)
                row = result.scalars().first()
                if row:
                    row.display_name = openalex_author.get(
                        "display_name", row.display_name
                    )
                    row.institution = inst_name
                    row.field_of_study = field
                    row.h_index = stats.get("h_index")
                    row.works_count = openalex_author.get("works_count")
                    row.concepts = concepts
                    row.raw_profile = openalex_author
                    row.last_synced = now
                    row.expires_at = expires_at
                else:
                    row = ResearcherProfile(
                        openalex_id=clean,
                        display_name=openalex_author.get("display_name", "Unknown"),
                        institution=inst_name,
                        field_of_study=field,
                        h_index=stats.get("h_index"),
                        works_count=openalex_author.get("works_count"),
                        concepts=concepts,
                        raw_profile=openalex_author,
                        last_synced=now,
                        expires_at=expires_at,
                    )
                    session.add(row)
                await session.commit()
            except Exception as e:
                print(
                    f"[DB Upsert Error] ResearcherProfile for {clean}: {e}", flush=True
                )
                await session.rollback()

    async def chat_with_author(
        self,
        author_id: str,
        paper_title: str,
        user_message: str,
        history: List[Dict[str, str]],
        user_id: Optional[str] = None,
    ) -> Dict[str, Any]:
        """
        Simulates chatting with a specific researcher about their paper using Groq.

        user_id is the real, server-verified caller identity (Firebase uid),
        resolved by the endpoint — never trust a client-supplied value for
        this, since it gates which chat history gets read/written. Previously
        this was a hardcoded literal ("default_local_user") shared by every
        caller, so two different real users viewing the same author+paper
        could load and overwrite each other's persisted history. When there's
        no verified user (anonymous access), history is neither loaded from
        nor saved to the database — the client's own `history` array is used
        for that request only, rather than inventing a new shared bucket.
        """
        clean_author = author_id.split("/")[-1]
        sanitized_title = re.sub(r"[^a-zA-Z0-9]", "_", paper_title.lower())[:100]
        doc_id = f"chat_{clean_author}_{sanitized_title}"
        if user_id:
            async with self._db_session() as session:
                if not history:
                    try:
                        stmt = (
                            select(AgentChatHistory)
                            .where(
                                AgentChatHistory.user_id == user_id,
                                AgentChatHistory.context_id == doc_id,
                            )
                            .order_by(AgentChatHistory.timestamp.asc())
                        )
                        result = await session.execute(stmt)
                        db_msgs = result.scalars().all()
                        if db_msgs:
                            history = [
                                {"role": msg.role, "content": msg.content}
                                for msg in db_msgs
                            ]
                            print(
                                f"[Postgres Chat Load] Loaded {len(history)} messages for doc_id={doc_id}",
                                flush=True,
                            )
                    except Exception as e:
                        print(f"[Postgres Chat Load Error] failed: {e}", flush=True)
        profile = await self._fetch_author_profile(author_id)
        author_name = "Researcher"
        concepts = ["science"]
        institution = "Research Lab"
        if profile:
            author_name = profile.get("display_name", "Researcher")
            concepts = [
                c.get("display_name")
                for c in profile.get("x_concepts", [])
                if c.get("level") in [1, 2]
            ][:3]
            institution = profile.get("institution") or "Research Institution"
        system_prompt = AUTHOR_CHAT_SYSTEM_PROMPT_TEMPLATE.format(
            author_name=author_name,
            institution=institution,
            concepts=", ".join(concepts),
            paper_title=paper_title,
        )
        messages = [{"role": "system", "content": system_prompt}]
        # Append history
        for msg in history[-5:]:  # limit to last 5 messages for token economy
            role = msg.get("role", "user")
            if role in ["user", "assistant"]:
                messages.append({"role": role, "content": msg.get("content", "")})
        # Append current user message
        messages.append({"role": "user", "content": user_message})
        reply = f"Thank you for your question about my work. I believe the principles discussed in '{paper_title}' outline a strong foundation for this domain."
        if (
            is_llm_working()
        ):  # Decoupled: LLM moved to background addon to unblock core app
            try:
                response = await self.llm_service.query(
                    messages=messages,
                    models=[self.model],
                    temperature=0.6,
                    max_tokens=150,
                )
                if response.content:
                    reply = response.content.strip()
            except Exception as e:
                print(f"Author chat simulation failed: {e}", flush=True)
        if user_id:
            async with self._db_session() as session:
                try:
                    user_msg = AgentChatHistory(
                        user_id=user_id,
                        context_id=doc_id,
                        role="user",
                        content=user_message,
                    )
                    asst_msg = AgentChatHistory(
                        user_id=user_id,
                        context_id=doc_id,
                        role="assistant",
                        content=reply,
                    )
                    session.add(user_msg)
                    session.add(asst_msg)
                    await session.commit()
                    print(
                        f"[Postgres Chat Save] Saved to chat_history for doc_id={doc_id}",
                        flush=True,
                    )
                except Exception as e:
                    print(f"[Postgres Chat Save Error] failed: {e}", flush=True)
        return {"author_id": author_id, "author_name": author_name, "reply": reply}
