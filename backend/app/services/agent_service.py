import httpx
import json
import re
import io
import pdfplumber
from app.services.pipeline_services import PipelineServices
from app.services.connectors import TOOLS_SCHEMA, execute_tool_call
from app.services.summarization_service import is_llm_working

pipeline_services = PipelineServices()

class AgentService:
    def __init__(self, history_summary_cache):
        self.history_summary_cache = history_summary_cache

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
            base_prompt = (
                f"You are Ask Skolar, an expert AI {req.mode} assistant for a senior researcher. Be concise, sharp, and proactive. "
                "You have access to tools that can fetch data from arXiv, Google Scholar, ResearchGate, OpenAlex, Semantic Scholar, and search/scrape the live web. "
                "CRITICAL RULES:\n"
                "0. PAPER SUMMARIZATION (HIGHEST PRIORITY): If the user asks to 'summarize a paper', 'explain a paper', "
                "'give me an overview of [paper/study]', 'what does [paper] say', 'TL;DR of [paper]', or any similar request, "
                "you MUST immediately call the 'fetch_and_summarize_paper' tool with the paper title, DOI, arXiv ID, or URL as the query. "
                "After receiving the tool result, write a structured summary with these sections: "
                "**Overview**, **Key Contributions**, **Methodology**, **Results & Findings**, **Limitations**, and **Why It Matters**. "
                "Do not attempt to summarize from memory alone — always fetch the paper first.\n"
                "1. AUTHOR/RESEARCHER SEARCH: You must try to fetch information from the OpenAlex API using 'search_openalex_author_and_works' or other OpenAlex tools. "
                "If results are returned, present the information immediately.\n"
                "2. FALLBACK SEARCH: If there are NO results from OpenAlex (e.g., the researcher is not indexed, the query fails, or the API is rate-limited/exhausted), "
                "you MUST immediately fall back to specific academic search tools:\n"
                "   - Use 'search_google_scholar_profile' to retrieve their Google Scholar name, affiliation, and publication list.\n"
                "   - Use 'search_researchgate_profile' to extract their ResearchGate matches, institution, and citation details.\n"
                "   - Use 'search_arxiv_publications' to search for preprints and abstracts.\n"
                "   - If these academic tools do not yield a complete profile, call 'search_web' to search LinkedIn, personal web pages, or general directories, scraping and parsing the results.\n"
                "3. TYPO RESOLUTION & SEMANTIC SEARCH: If the user types a query with incorrect spelling, typos, or unclear English, "
                "do not return empty results. Use academic tools or web search to search semantically, resolve the correct spelling, "
                "and if multiple ambiguous matches exist, list the top options and ask the user to clarify dynamically.\n"
                "4. DOMAIN SCOPING (VERY IMPORTANT): The user has a primary research domain (stated in [RESEARCHER PROFILE] below). "
                "For ALL searches — whether for people, papers, topics, or works — you MUST automatically scope results to the user's domain "
                "by passing the user's primary research field as the 'domain' argument to tools like 'search_openalex_author_and_works', "
                "'search_openalex_authors', and 'search_openalex_works'. "
                "Only deviate from this if the user EXPLICITLY mentions a different field or domain in their message. "
                "Example: if the user's domain is 'Physics' and they search for 'John Smith', "
                "pass domain='Physics' so the agent finds the physicist named John Smith, not a psychologist or engineer with the same name.\n"
                "Always present the final gathered profile details and publications structured clearly in a table."
            )

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
                
                import hashlib
                older_serialized = json.dumps(older_messages, sort_keys=True)
                older_hash = hashlib.sha256(older_serialized.encode()).hexdigest()
                
                summary_text = await self.history_summary_cache.get(older_hash)
                if not summary_text:
                    print(f"[AgentChat] Cache miss for history summary. Generating summary for {len(older_messages)} older messages...", flush=True)
                    summary_prompt = (
                        "Summarize the following past conversation between a researcher (user) and Ask Skolar (assistant) into a concise, detailed summary paragraph. "
                        "Retain all key facts, research topics discussed, and researcher preferences. Do not lose context:\n\n"
                    )
                    for h in older_messages:
                        role_label = "Researcher" if h.get("role") == "user" else "Ask Skolar"
                        summary_prompt += f"{role_label}: {h.get('content') or ''}\n"
                    
                    try:
                        async with httpx.AsyncClient(timeout=30.0) as client:
                            sum_res = await client.post(
                                pipeline_services.base_url,
                                headers={"Authorization": f"Bearer {pipeline_services.api_key}", "Content-Type": "application/json"},
                                json={
                                    "model": "llama-3.1-8b-instant",
                                    "messages": [
                                        {"role": "system", "content": "You are a highly efficient assistant that summarizes conversation logs."},
                                        {"role": "user", "content": summary_prompt}
                                    ],
                                    "temperature": 0.3,
                                    "max_tokens": 1024
                                },
                                timeout=20.0
                            )
                            if sum_res.status_code == 200:
                                summary_text = (sum_res.json()['choices'][0]['message'].get('content') or '').strip()
                                await self.history_summary_cache.set(older_hash, summary_text)
                                print("[AgentChat] Successfully generated and cached conversation summary.", flush=True)
                            else:
                                print(f"[AgentChat] Summarization LLM returned status {sum_res.status_code}: {sum_res.text}. Using fallback summary.", flush=True)
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

            if pipeline_services.api_key:
                async with httpx.AsyncClient(timeout=30.0) as client:
                    chosen_model = None
                    max_turns = 5
                    
                    for turn in range(max_turns):
                        print(f"[AgentChat] Turn {turn+1}/{max_turns}...", flush=True)
                        model_order = models
                        if chosen_model:
                            model_order = [chosen_model] + [m for m in models if m != chosen_model]
                        
                        response_msg = None
                        success = False
                        
                        for model in model_order:
                            try:
                                temp_messages = self.clean_messages(messages)
                                if turn == max_turns - 1:
                                    temp_messages.append({
                                        "role": "system",
                                        "content": "This is the final turn. You must provide your final answer to the user now. Do not call any tools."
                                    })
                                
                                req_json = {
                                    "model": model,
                                    "messages": temp_messages,
                                    "temperature": 0.5,
                                    "max_tokens": 1024,
                                    "tools": TOOLS_SCHEMA,
                                    "tool_choice": "auto"
                                }
                                
                                print(f"[AgentChat] Attempting turn {turn+1} with model: {model}", flush=True)
                                res = await client.post(
                                    pipeline_services.base_url,
                                    headers={"Authorization": f"Bearer {pipeline_services.api_key}", "Content-Type": "application/json"},
                                    json=req_json,
                                    timeout=20.0
                                )
                                if res.status_code == 200:
                                    msg = res.json()['choices'][0]['message']
                                    if not msg.get("tool_calls"):
                                        c = (msg.get("content") or "").strip()
                                        c = re.sub(r'<function=.*?>.*?</function>', '', c, flags=re.DOTALL)
                                        c = re.sub(r'<function=.*?>', '', c)
                                        c = re.sub(r'</function>', '', c)
                                        c = c.strip()
                                        if not c:
                                            print(f"[AgentChat] Model {model} returned empty response on turn {turn+1}. Trying next model...", flush=True)
                                            continue
                                    response_msg = msg
                                    chosen_model = model
                                    success = True
                                    print(f"[AgentChat] Turn {turn+1} succeeded with model: {model}", flush=True)
                                    break
                                else:
                                    print(f"[AgentChat] Model {model} failed on turn {turn+1} with status {res.status_code}: {res.text}. Trying next model...", flush=True)
                            except Exception as e:
                                print(f"[AgentChat] Model {model} raised exception on turn {turn+1}: {e}. Trying next model...", flush=True)
                        
                        if not success or not response_msg:
                            print(f"[AgentChat] Turn {turn+1} failed completely across all models.", flush=True)
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
                            
                            try:
                                with open("agent_messages_debug.json", "w", encoding="utf-8") as f:
                                    json.dump(messages, f, indent=2, default=str)
                            except Exception as ex:
                                print(f"Failed to write agent messages log: {ex}")
                                
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
                
            return {"filename": filename, "extracted_text": extracted_text.strip()}
        except Exception as e:
            raise Exception(str(e))
