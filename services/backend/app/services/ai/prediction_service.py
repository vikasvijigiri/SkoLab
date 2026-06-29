from typing import List
from app.services.ai.llm_service import is_llm_working
from app.prompts import PREDICTION_SYSTEM_PROMPT


class PredictionService:
    def __init__(self):
        from app.services.ai.llm_service import LLMService

        self.llm_service = LLMService()
        self.models = None

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
