import httpx
import os
import random
from typing import List, Optional

class PredictionService:
    def __init__(self):
        self.api_key = os.getenv("GROQ_API")
        self.base_url = "https://api.groq.com/openai/v1/chat/completions"
        self.model = "llama-3.3-70b-versatile"

    async def predict_next_problem(self, last_works: List[str]) -> str:
        """
        Uses Groq's LLM to predict a viable next research problem and required tools 
        based on the author's most recent 3-5 publications.
        """
        if not self.api_key or not last_works:
            return "**Problem**: Advanced Quantum Dynamics\n**Tools**: Python (Qiskit), Tensor Networks"

        # Focus on the most recent works
        recent_context = "Recent Publications:\n" + "\n".join([f"- {title}" for title in last_works[:3]])
        
        prompt = {
            "model": self.model,
            "messages": [
                {
                    "role": "system",
                    "content": """You are a world-class scientific research strategist. 
                    Based on an author's recent publications, identify a VIABLE and SPECIFIC next research problem.
                    
                    FORMAT:
                    **Next Frontier**: [A technical 1-sentence problem statement]
                    **Toolkit**: [List 2-3 essential tools/skills, e.g., Python/JAX, LaTeX]
                    
                    Keep it extremely concise (max 30 words total).
                    """
                },
                {
                    "role": "user",
                    "content": recent_context
                }
            ],
            "temperature": 0.4,
            "max_tokens": 150
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
                    timeout=12.0
                )
                
                if response.status_code == 200:
                    data = response.json()
                    return data['choices'][0]['message']['content'].strip()
                
                return "**Problem**: Integrated Quantum Error Correction\n**Requirements**: Surface Codes, C++"
        except Exception:
            return self._generate_fallback_prediction(last_works)

    def _generate_fallback_prediction(self, last_works: List[str]) -> str:
        combined_titles = " ".join(last_works).lower()
        if "quantum" in combined_titles: return "Scalable Quantum Error Correction"
        if "gravity" in combined_titles: return "Quantum Gravity in de Sitter Space"
        if "entropy" in combined_titles: return "Holographic Complexity and Entanglement"
        return "Non-equilibrium dynamics of many-body systems"
