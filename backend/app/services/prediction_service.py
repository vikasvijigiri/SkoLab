import httpx
import os
import random
from typing import List, Optional

class PredictionService:
    def __init__(self):
        self.api_key = os.getenv("GROQ_API")
        self.base_url = "https://api.groq.com/openai/v1/chat/completions"
        self.model = "llama-3.3-70b-versatile"

    async def predict_next_problem(
        self,
        author_name: str,
        expertise: List[str],
        works: List[dict]
    ) -> str:
        """
        Uses Groq's LLM to predict an extremely meticulous, highly viable, and specific 
        next research paper/problem and required tools, grounded perfectly in the 
        author's current skillset, past work, and abstracts of their publications.
        """
        if not self.api_key or not works:
            return (
                "**Next Frontier**: Advanced Topological Fault-Tolerance in Monolithic Quantum Processors\n\n"
                "**Toolkit**: Qiskit, Surface Codes, C++\n\n"
                "**Logic**: This logic extrapolates previous research on quantum error correction codes, applying them to monolithic silicon architectures to overcome thermal decoherence limits."
            )

        # Format past publications with title, year, abstract, and metrics
        works_context_parts = []
        for i, w in enumerate(works[:5]):  # Top 5 works
            title = w.get("title", "Untitled")
            year = w.get("year") or w.get("publication_year", "N/A")
            abstract = w.get("abstract", "")
            if len(abstract) > 300:
                abstract = abstract[:300] + "..."
            citations = w.get("citations", 0)
            
            part = (
                f"Paper #{i+1}: {title} ({year})\n"
                f"Citations: {citations}\n"
                f"Focus/Abstract: {abstract}\n"
            )
            works_context_parts.append(part)
            
        works_context = "\n".join(works_context_parts)
        expertise_str = ", ".join(expertise)
        
        user_content = (
            f"Researcher Name: {author_name}\n"
            f"Expertise/Skillset: {expertise_str}\n\n"
            f"Selected Publications (Latest/Top):\n{works_context}"
        )
        
        prompt = {
            "model": self.model,
            "messages": [
                {
                    "role": "system",
                    "content": """You are an elite scientific research advisor and strategist. 
Your task is to predict the next SPECIFIC, highly viable research paper the author is most likely to write.
This prediction must be extremely meticulous, realistic, and strictly grounded in the author's existing skillset, mathematical/technical expertise, and the exact trajectory of their previous papers' findings/methods.

Provide your response in this exact format:
**Next Frontier**: [A technical, specific 1-sentence title/problem statement for their next possible paper]
**Toolkit**: [2-3 advanced, highly specific tools, programming languages, or mathematical frameworks needed, aligned with their skillset]
**Logic**: [A concise 1-2 sentence explanation of why this is the logical next step, directly connecting their past findings/methods to this new frontier]

Be precise, academic, and extremely professional. Do not use generic buzzwords.
"""
                },
                {
                    "role": "user",
                    "content": user_content
                }
            ],
            "temperature": 0.3,
            "max_tokens": 250
        }

        try:
            async with httpx.AsyncClient() as client:
                response = await client.post(
                    self.base_url,
                    headers={
                        "Authorization": f"Bearer {self.api_key}",
                        "Content-Type": "application/json"
                    },
                    json=prompt,
                    timeout=15.0
                )
                
                if response.status_code == 200:
                    data = response.json()
                    return data['choices'][0]['message']['content'].strip()
                
                return self._generate_fallback_prediction(author_name, expertise, works)
        except Exception:
            return self._generate_fallback_prediction(author_name, expertise, works)

    def _generate_fallback_prediction(
        self,
        author_name: str,
        expertise: List[str],
        works: List[dict]
    ) -> str:
        combined_titles = " ".join([w.get("title", "") for w in works]).lower()
        if "quantum" in combined_titles:
            return (
                "**Next Frontier**: Fault-Tolerant Logical Qubits in Superconducting Systems\n\n"
                "**Toolkit**: Python, Qiskit, C++\n\n"
                "**Logic**: Builds upon recent quantum simulation results to realize physical-to-logical qubit mapping under constraints of noise."
            )
        if "gravity" in combined_titles:
            return (
                "**Next Frontier**: Emergent Gravity from Quantum Entanglement in AdS/CFT\n\n"
                "**Toolkit**: Mathematica, Python, Tensor Networks\n\n"
                "**Logic**: Extends recent work on holographic complexity to compute bulk metric corrections from boundary entanglement."
            )
        
        # General default fallback
        exp_topic = expertise[0] if expertise else "Non-equilibrium Many-Body Dynamics"
        return (
            f"**Next Frontier**: Novel Computational Methods in {exp_topic}\n\n"
            "**Toolkit**: JAX, Python, High-Performance Computing\n\n"
            f"**Logic**: Logical extension of past work in {exp_topic} using modern machine learning accelerated tools."
        )
