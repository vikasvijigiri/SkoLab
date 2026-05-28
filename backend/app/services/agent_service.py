import hashlib
import datetime
import httpx
import json
import re
import io
import pdfplumber
from app.services.connectors import TOOLS_SCHEMA, execute_tool_call
from app.services.llm_service import is_llm_working
from app.prompts import (
    AGENT_BASE_PROMPT,
    AGENT_SUMMARY_PROMPT,
    AGENT_SUMMARY_PERSONA,
    AGENT_FINAL_TURN_PROMPT,
)


# ── PostgreSQL helpers for agent history summaries ────────────────────────────

async def _pg_get_summary(cache_key: str):
    """Load an agent history summary from PostgreSQL. Returns None on miss/expiry."""
    from app.db.database import AsyncSessionLocal
    from app.models.agent_models import AgentHistorySummary
    from sqlalchemy.future import select
    now = datetime.datetime.utcnow()
    async with AsyncSessionLocal() as session:
        try:
            stmt = select(AgentHistorySummary).where(
                AgentHistorySummary.cache_key == cache_key,
                AgentHistorySummary.expires_at > now,
            )
            result = await session.execute(stmt)
            row = result.scalars().first()
            return row.summary if row else None
        except Exception as exc:
            print(f"[AgentSummaryDB] GET error: {exc}", flush=True)
    return None


async def _pg_set_summary(cache_key: str, summary: str, ttl_hours: int = 12):
    """Persist an agent history summary to PostgreSQL with a TTL."""
    from app.db.database import AsyncSessionLocal
    from app.models.agent_models import AgentHistorySummary
    from sqlalchemy.future import select
    now = datetime.datetime.utcnow()
    expires_at = now + datetime.timedelta(hours=ttl_hours)
    async with AsyncSessionLocal() as session:
        try:
            stmt = select(AgentHistorySummary).where(AgentHistorySummary.cache_key == cache_key)
            result = await session.execute(stmt)
            row = result.scalars().first()
            if row:
                row.summary = summary
                row.expires_at = expires_at
            else:
                row = AgentHistorySummary(
                    cache_key=cache_key,
                    summary=summary,
                    created_at=now,
                    expires_at=expires_at,
                )
                session.add(row)
            await session.commit()
        except Exception as exc:
            print(f"[AgentSummaryDB] SET error: {exc}", flush=True)
            await session.rollback()


class AgentService:
    def __init__(self, history_summary_cache):
        # history_summary_cache param kept for API compat; PG is used directly.
        self.history_summary_cache = history_summary_cache
        from app.services.llm_service import LLMService
        self.llm_service = LLMService()

    def estimate_tokens(self, text: str) -> int:
        return len(text or "") // 4

    def clean_messages(self, msgs_list):
        cleaned = []
        for msg in msgs_list:
            clean_msg = {k: v for k, v in msg.items() if k != "reasoning"}
            cleaned.append(clean_msg)
        return cleaned

    async def process_agent_chat(self, req, base_url: str = None) -> dict:
        try:
            base_prompt = AGENT_BASE_PROMPT.format(mode=req.mode)

            # Inject user memory block if present
            memory_block = ""
            domain_directive = ""  # injected as a high-priority scoping instruction
            if req.user_memory:
                m = req.user_memory
                parts = []
                top_topics = m.get("topTopics") or m.get("top_topics") or []
                last_topic = m.get("lastActiveTopic") or m.get("last_active_topic") or ""
                reading_pace = m.get("readingPace") or m.get("reading_pace") or ""
                collaborators = m.get("frequentCollaborators") or m.get("frequent_collaborators") or []
                unfinished = m.get("unfinishedPapers") or m.get("unfinished_papers") or []
                searches = m.get("frequentSearchTerms") or m.get("frequent_search_terms") or []
                streak = m.get("streakDays") or m.get("streak_days") or 0
                avg_read = m.get("avgReadMinutes") or m.get("avg_read_minutes") or 0

                # Build a prominent domain directive that forces domain-scoped searches
                if top_topics:
                    primary_domain = top_topics[0]  # most prominent research field
                    all_domains = ", ".join(top_topics[:4])
                    domain_directive = (
                        f"\n\n[DOMAIN SCOPING DIRECTIVE]\n"
                        f"This researcher works in: {all_domains}. "
                        f"PRIMARY domain: {primary_domain}. "
                        f"MANDATORY: For EVERY tool call that searches for a person or paper, you MUST pass "
                        f"domain='{primary_domain}' (or the closest matching field) unless the user explicitly "
                        f"asks about a different field. This ensures correct disambiguation of common names "
                        f"and domain-relevant results. Never search without a domain unless told otherwise."
                        f"\n[END DOMAIN DIRECTIVE]\n"
                    )

                if top_topics:
                    parts.append(f"Research focus: {', '.join(top_topics[:4])}")
                if last_topic:
                    parts.append(f"Currently exploring: {last_topic}")
                if reading_pace:
                    parts.append(f"Reading style: {reading_pace} (~{int(avg_read)} min/paper avg)")
                if collaborators:
                    parts.append(f"Key collaborators: {', '.join(collaborators[:3])}")
                if unfinished:
                    parts.append(f"Unfinished papers: {'; '.join(unfinished[:2])}")
                if searches:
                    parts.append(f"Recent searches: {', '.join(searches[:3])}")
                if streak:
                    parts.append(f"Research streak: {streak} days")

                if parts:
                    memory_block = "\n\n[RESEARCHER PROFILE]\n" + "\n".join(parts) + "\n[END PROFILE]\n\nUse this profile to personalise your responses. Reference unfinished work or relevant topics proactively when natural."

            system_prompt = base_prompt + domain_directive + memory_block

            # Calculate estimated total tokens in raw chat history
            total_history_tokens = sum(self.estimate_tokens(h.get("content") or "") for h in req.history)
            
            messages = [{"role": "system", "content": system_prompt}]
            
            if total_history_tokens < 10000:
                # Under 10k: send all raw history messages directly
                for h in req.history:
                    if h.get("role") in ["user", "assistant", "tool"]:
                        messages.append({"role": h["role"], "content": h.get("content") or ""})
            else:
                # Over 10k: dynamic summarization check. 
                recent_messages = []
                older_messages = []
                accumulated_tokens = 0
                
                for h in reversed(req.history):
                    msg_tokens = self.estimate_tokens(h.get("content") or "")
                    if accumulated_tokens + msg_tokens <= 3500:
                        recent_messages.insert(0, h)
                        accumulated_tokens += msg_tokens
                    else:
                        idx = req.history.index(h)
                        older_messages = req.history[:idx+1]
                        break
                
                older_serialized = json.dumps(older_messages, sort_keys=True)
                older_hash = hashlib.sha256(older_serialized.encode()).hexdigest()

                # Try PostgreSQL first (survives restarts), then fall back to in-mem cache
                summary_text = await _pg_get_summary(older_hash)
                if not summary_text:
                    summary_text = await self.history_summary_cache.get(older_hash)
                if not summary_text:
                    print(f"[AgentChat] Cache miss for history summary. Generating summary for {len(older_messages)} older messages...", flush=True)
                    summary_prompt = AGENT_SUMMARY_PROMPT
                    for h in older_messages:
                        role_label = "Researcher" if h.get("role") == "user" else "Ask Skolar"
                        summary_prompt += f"{role_label}: {h.get('content') or ''}\n"
                    
                    try:
                        res_obj = await self.llm_service.query(
                            messages=[
                                {"role": "system", "content": AGENT_SUMMARY_PERSONA},
                                {"role": "user", "content": summary_prompt}
                            ],
                            models=["llama-3.1-8b-instant"],
                            temperature=0.3,
                            max_tokens=1024
                        )
                        if res_obj.content:
                            summary_text = res_obj.content.strip()
                            # Persist to PostgreSQL (survives restarts) AND in-memory cache
                            await _pg_set_summary(older_hash, summary_text)
                            await self.history_summary_cache.set(older_hash, summary_text)
                            print("[AgentChat] Successfully generated and cached conversation summary.", flush=True)
                        else:
                            print("[AgentChat] Summarization LLM returned empty content. Using fallback summary.", flush=True)
                            summary_text = "Summary fallback: A long conversation about deep learning research and publication analysis."
                    except Exception as e:
                        print(f"[AgentChat] Failed to generate conversation summary: {e}. Using fallback.", flush=True)
                        summary_text = "Summary fallback: A long conversation about deep learning research and publication analysis."
                
                messages.append({
                    "role": "system",
                    "content": f"[SUMMARY OF OLDER CONVERSATION]\n{summary_text}\n[END SUMMARY]"
                })
                
                for h in recent_messages:
                    if h.get("role") in ["user", "assistant", "tool"]:
                        messages.append({"role": h["role"], "content": h.get("content") or ""})
                        
            messages.append({"role": "user", "content": req.message})
            
            models = [
                "openai/gpt-oss-120b",
                "llama-3.3-70b-versatile",
                "llama-3.1-8b-instant",
                "qwen/qwen3-32b"
            ]
            if is_llm_working():
                chosen_model = None
                max_turns = 5
                
                for turn in range(max_turns):
                    print(f"[AgentChat] Turn {turn+1}/{max_turns}...", flush=True)
                    model_order = models
                    if chosen_model:
                        model_order = [chosen_model] + [m for m in models if m != chosen_model]
                    
                    response_msg = None
                    success = False
                    
                    try:
                        temp_messages = self.clean_messages(messages)
                        if turn == max_turns - 1:
                            temp_messages.append({
                                        "role": "system",
                                        "content": AGENT_FINAL_TURN_PROMPT
                                    })
                        
                        print(f"[AgentChat] Requesting LLM query via LLMService on turn {turn+1}...", flush=True)
                        response = await self.llm_service.query(
                            messages=temp_messages,
                            models=model_order,
                            temperature=0.5,
                            max_tokens=1024,
                            tools=TOOLS_SCHEMA if turn < max_turns - 1 else None,
                            tool_choice="auto" if turn < max_turns - 1 else None
                        )
                        
                        if response.content or response.tool_calls:
                            response_msg = {
                                "role": "assistant",
                                "content": response.content or "",
                                "tool_calls": response.tool_calls
                            }
                            chosen_model = response.model_used
                            success = True
                            print(f"[AgentChat] Turn {turn+1} succeeded with model: {chosen_model}", flush=True)
                    except Exception as e:
                        print(f"[AgentChat] Error on turn {turn+1}: {e}", flush=True)
 
                    if not success or not response_msg:
                        print(f"[AgentChat] Turn {turn+1} failed completely across all models and OpenRouter.", flush=True)
                        return {"reply": "Agent failed to generate a response. All fallback models exhausted."}
                    
                    tool_calls = response_msg.get("tool_calls")
                    if not tool_calls and "content" in response_msg and response_msg["content"] and "<function=" in response_msg["content"]:
                        content = response_msg["content"]
                        match = re.search(r'<function=(\w+)>(.*?)</function>', content)
                        if match:
                            func_name = match.group(1)
                            try:
                                args = json.loads(match.group(2))
                                tool_calls = [{
                                    "id": "call_" + func_name,
                                    "function": {"name": func_name, "arguments": json.dumps(args)}
                                }]
                            except:
                                pass
                    
                    if tool_calls and turn < max_turns - 1:
                        if not response_msg.get("tool_calls"):
                            response_msg["tool_calls"] = tool_calls
                        messages.append(response_msg)
                        for tc in tool_calls:
                            func_name = tc["function"]["name"]
                            args_str = tc["function"].get("arguments", "{}")
                            try:
                                args = json.loads(args_str) if isinstance(args_str, str) else args_str
                            except Exception as e:
                                print(f"[AgentChat] Error parsing tool arguments: {e}", flush=True)
                                args = {}
                                
                            tool_result = await execute_tool_call(func_name, args, base_url=base_url)
                            messages.append({
                                "role": "tool",
                                "tool_call_id": tc["id"],
                                "name": func_name,
                                "content": str(tool_result)
                            })
                    else:
                        reply = (response_msg.get('content') or '').strip()
                        reply = re.sub(r'<function=.*?>.*?</function>', '', reply, flags=re.DOTALL)
                        reply = re.sub(r'<function=.*?>', '', reply)
                        reply = re.sub(r'</function>', '', reply)
                        reply = reply.strip()
                        
                        if not reply:
                            if tool_calls:
                                func_name = tool_calls[0]["function"]["name"]
                                reply = f"I attempted to search for this information using {func_name}, but reached the maximum response depth. Please try again with a more specific query."
                            else:
                                reply = "I'm sorry, I could not find a definitive answer to your query. Please try phrasing it differently."

                        # No debug file writes — all logging goes to server stdout only.
                        return {"reply": reply}
            else:
                return {"reply": "⚠️ The Groq API key is not configured on the backend. Please set GROQ_API in your backend/.env file."}
        except Exception as e:
            print(f"Agent chat failed: {e}")
            return {"reply": "An error occurred while processing your query. Please try again."}

    async def process_upload_document(self, content: bytes, filename: str, content_type: str) -> dict:
        try:
            extracted_text = ""
            if content_type == "application/pdf" or filename.endswith(".pdf"):
                with pdfplumber.open(io.BytesIO(content)) as pdf:
                    for page in pdf.pages:
                        text = page.extract_text()
                        if text:
                            extracted_text += text + "\n"
            else:
                extracted_text = content.decode("utf-8", errors="replace")
                
            from app.db.database import AsyncSessionLocal
            from app.models.agent_models import AgentDocumentUpload
            import datetime

            now = datetime.datetime.utcnow()
            expires_at = now + datetime.timedelta(hours=24)
            file_size_kb = len(content) // 1024

            async with AsyncSessionLocal() as session:
                doc = AgentDocumentUpload(
                    filename=filename,
                    content_type=content_type,
                    extracted_text=extracted_text.strip(),
                    file_size_kb=file_size_kb,
                    uploaded_at=now,
                    expires_at=expires_at
                )
                session.add(doc)
                await session.commit()
                await session.refresh(doc)
                doc_id = doc.id

            return {"id": doc_id, "filename": filename, "extracted_text": extracted_text.strip()}
        except Exception as e:
            raise Exception(str(e))
