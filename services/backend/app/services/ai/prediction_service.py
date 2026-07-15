import json
import asyncio
from typing import List, Dict, Any, Optional
from app.services.ai.llm_service import is_llm_working
from app.prompts import PREDICTION_SYSTEM_PROMPT
from app.prompts.horizon_prompts import HORIZON_SYSTEM_PROMPT, NEXUS_CHAT_SYSTEM_PROMPT
from app.services.data.openalex_service import OpenAlexService


class PredictionService:
    def __init__(self):
        from app.services.ai.llm_service import LLMService

        self.llm_service = LLMService()
        self.models = None
        self.openalex_service = OpenAlexService()

    async def predict_next_problem(
        self, author_name: str, expertise: List[str], works: List[dict]
    ) -> str:
        """
        Uses Groq's LLM to predict an extremely meticulous, highly viable, and specific
        next research paper/problem and required tools, grounded perfectly in the
        author's current skillset, past work, and abstracts of their publications.
        """
        if not works:
            raise ValueError(
                "Cannot predict next problem: no publications found for this researcher."
            )
        if not is_llm_working():
            raise Exception("LLM services are currently unavailable or rate-limited.")

        # Format past publications with title, year, abstract, and metrics/summaries
        works_context_parts = []
        for i, w in enumerate(works[:10]):  # Up to 10 works
            title = w.get("title", "Untitled")
            year = w.get("year") or w.get("publication_year", "N/A")
            citations = w.get("citations", 0)

            # Build rich/grounded info if summary elements are present
            tldr = w.get("tldr")
            techniques = w.get("techniques")
            tools = w.get("tools_and_software")
            core_concepts = w.get("core_concepts")

            part = f"Paper #{i + 1}: {title} ({year})\nCitations: {citations}\n"
            if tldr:
                part += f"Summary/TLDR: {tldr}\n"
            else:
                abstract = w.get("abstract", "")
                if len(abstract) > 300:
                    abstract = abstract[:300] + "..."
                part += f"Focus/Abstract: {abstract}\n"

            if techniques:
                part += f"Techniques/Methods used: {', '.join(techniques)}\n"
            if tools:
                part += f"Tools/Software used: {', '.join(tools)}\n"
            if core_concepts:
                part += f"Core Concepts: {', '.join(core_concepts)}\n"

            works_context_parts.append(part)

        works_context = "\n".join(works_context_parts)
        expertise_str = ", ".join(expertise)

        user_content = (
            f"Researcher Name: {author_name}\n"
            f"Expertise/Skillset: {expertise_str}\n\n"
            f"Selected Publications (Latest/Top):\n{works_context}"
        )

        messages = [
            {"role": "system", "content": PREDICTION_SYSTEM_PROMPT},
            {"role": "user", "content": user_content},
        ]

        response = await self.llm_service.query(
            messages=messages, models=self.models, temperature=0.3, max_tokens=250
        )

        if not response.content:
            raise Exception("LLM prediction failed to generate a response.")

        return response.content.strip()

    async def predict_next_big_thing(
        self, field: str, focus_area: Optional[str] = None
    ) -> Dict[str, Any]:
        """
        Fetches pioneering and latest research in the field, reads their abstracts,
        and uses the LLM to predict the next discovery and business value.
        """
        if not is_llm_working():
            return {
                "breakthrough_name": "Service Unavailable",
                "description": "The AI service is temporarily offline.",
                "scientific_logic": "N/A",
                "business_application": "N/A",
                "time_horizon": "N/A",
                "feasibility": "Low",
                "roadmap_steps": [],
                "pioneering_papers": [],
                "latest_papers": [],
            }

        search_query = field
        if focus_area:
            search_query += f" {focus_area}"

        # Fetch pioneering and latest works in parallel
        pioneer_task = self.openalex_service.search_works(
            query=search_query, per_page=8, sort="cited_by_count:desc"
        )
        latest_task = self.openalex_service.search_works(
            query=search_query, per_page=8, sort="publication_date:desc"
        )

        pioneer_raw, latest_raw = await asyncio.gather(pioneer_task, latest_task)

        # Helper to format paper content for OpenAlex results
        def process_paper(w: dict, source_type: str) -> dict:
            title = w.get("title") or "Untitled Work"
            authors = [
                auth.get("author", {}).get("display_name")
                for auth in w.get("authorships", [])
                if auth.get("author", {}).get("display_name")
            ]
            pub_year = w.get("publication_year") or 0
            cited_count = w.get("cited_by_count") or 0
            doi = w.get("doi") or ""

            # Reconstruct abstract
            abstract_inverted = w.get("abstract_inverted_index")
            abstract = ""
            if abstract_inverted:
                try:
                    word_positions = {}
                    for word, positions in abstract_inverted.items():
                        for pos in positions:
                            word_positions[pos] = word
                    abstract = " ".join([word_positions[i] for i in sorted(word_positions.keys())])
                except Exception:
                    pass

            if not abstract:
                abstract = "Abstract not available."

            return {
                "id": w.get("id", ""),
                "title": title,
                "authors": authors[:3],
                "year": pub_year,
                "cited_by_count": cited_count,
                "doi": doi,
                "abstract": abstract,
                "source_type": source_type
            }

        pioneer_works = [process_paper(p, "pioneering") for p in pioneer_raw]
        latest_works = [process_paper(l, "latest") for l in latest_raw]

        # Construct LLM context
        context_lines = []
        context_lines.append(f"TARGET FIELD: {field}")
        if focus_area:
            context_lines.append(f"FOCUS AREA: {focus_area}")
        context_lines.append("\n=== PIONEERING PAPERS ===")
        for i, paper in enumerate(pioneer_works):
            context_lines.append(
                f"[{i+1}] Title: {paper['title']}\n"
                f"Year: {paper['year']} | Citations: {paper['cited_by_count']}\n"
                f"Abstract: {paper['abstract'][:800]}...\n"
            )
        context_lines.append("\n=== LATEST PAPERS ===")
        for i, paper in enumerate(latest_works):
            context_lines.append(
                f"[{i+1}] Title: {paper['title']}\n"
                f"Year: {paper['year']} | Citations: {paper['cited_by_count']}\n"
                f"Abstract: {paper['abstract'][:800]}...\n"
            )

        context = "\n".join(context_lines)

        messages = [
            {"role": "system", "content": HORIZON_SYSTEM_PROMPT},
            {"role": "user", "content": context},
        ]

        try:
            response = await self.llm_service.query(
                messages=messages,
                temperature=0.2,
                max_tokens=2048,
                response_format={"type": "json_object"},
            )

            result = json.loads(response.content)
            # Add sources to the result response for display on frontend
            result["pioneering_papers"] = [
                {
                    "id": p["id"],
                    "title": p["title"],
                    "authors": p["authors"],
                    "year": p["year"],
                    "cited_by_count": p["cited_by_count"],
                    "doi": p["doi"],
                }
                for p in pioneer_works
            ]
            result["latest_papers"] = [
                {
                    "id": l["id"],
                    "title": l["title"],
                    "authors": l["authors"],
                    "year": l["year"],
                    "cited_by_count": l["cited_by_count"],
                    "doi": l["doi"],
                }
                for l in latest_works
            ]
            return result

        except Exception as e:
            print(f"[PredictionService] LLM query failed: {e}", flush=True)
            return {
                "breakthrough_name": f"Next-Gen {field} Breakthrough",
                "description": f"An emerging trend synthesis in {field} indicates a transition towards cross-domain hybrid structures.",
                "scientific_logic": "Failed to generate precise logic due to LLM error.",
                "business_application": "Failed to generate business applications.",
                "time_horizon": "5-10 years",
                "feasibility": "Medium",
                "roadmap_steps": ["Establish baseline research parameters", "Investigate cross-domain integration"],
                "pioneering_papers": [
                    {
                        "id": p["id"],
                        "title": p["title"],
                        "authors": p["authors"],
                        "year": p["year"],
                        "cited_by_count": p["cited_by_count"],
                        "doi": p["doi"],
                    }
                    for p in pioneer_works
                ],
                "latest_papers": [
                    {
                        "id": l["id"],
                        "title": l["title"],
                        "authors": l["authors"],
                        "year": l["year"],
                        "cited_by_count": l["cited_by_count"],
                        "doi": l["doi"],
                    }
                    for l in latest_works
                ],
            }

    async def nexus_chat(
        self, papers: List[Dict[str, Any]], messages: List[Dict[str, str]]
    ) -> str:
        """
        Runs a chat query where the LLM answers questions using context from a collection of papers.
        """
        if not is_llm_working():
            return "The AI system is currently unavailable."

        # Build context from papers
        context_lines = ["Here are the papers currently in the workspace collection:\n"]
        for i, paper in enumerate(papers):
            context_lines.append(
                f"--- PAPER {i+1} ---\n"
                f"Title: {paper.get('title')}\n"
                f"Authors: {', '.join(paper.get('authors', []))}\n"
                f"Abstract: {paper.get('abstract', 'No abstract available.')}\n"
            )
        context = "\n".join(context_lines)

        # Inject selected papers as system instruction
        system_content = f"{NEXUS_CHAT_SYSTEM_PROMPT}\n\n{context}"

        chat_messages = [{"role": "system", "content": system_content}]
        # Append message history (excluding system messages if any, and formatting properly)
        for msg in messages:
            chat_messages.append({"role": msg["role"], "content": msg["content"]})

        try:
            response = await self.llm_service.query(
                messages=chat_messages,
                temperature=0.3,
                max_tokens=1024,
            )
            return response.content or "No response received."
        except Exception as e:
            return f"Error querying synthesis engine: {str(e)}"
