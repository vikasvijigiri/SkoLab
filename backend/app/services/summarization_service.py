import httpx
import os
import random
import json
from typing import Optional, List, Dict, Any
from .metrics_service import MetricsService

class SummarizationService:
    def __init__(self):
        self.api_key = os.getenv("GROQ_API")
        self.base_url = "https://api.groq.com/openai/v1/chat/completions"
        self.model = "llama-3.3-70b-versatile"
        self.metrics_service = MetricsService()

    async def summarize_paper(self, title: str, doi: Optional[str] = None) -> Dict[str, Any]:
        """
        Summarizes a scientific paper using Groq and calculates metrics programmatically.
        """
        paper_data = await self._fetch_paper_data(doi) if doi else {"title": title}
        
        # Calculate Metrics Programmatically
        metrics = self.metrics_service.calculate_metrics(paper_data)
        top_skills = self.metrics_service.extract_top_skills(paper_data.get("concepts", []))

        if not self.api_key:
            fallback = self._generate_fallback_data(title)
            fallback["metrics"] = metrics
            fallback["top_skills"] = top_skills
            return fallback

        # Build a richer context based on the "Paper DNA"
        context = f"Title: {title}\n"
        if paper_data.get("abstract"):
            context += f"Abstract: {paper_data['abstract']}\n"
        if paper_data.get("concepts"):
            context += f"Concepts: {paper_data['concepts']}\n"
        
        prompt = {
            "model": self.model,
            "messages": [
                {
                    "role": "system",
                    "content": r"""You are a world-class scientific communicator. 
                    Summarize the provided paper DNA into 4-5 high-impact, technical bullet points.
                    
                    STYLING RULES:
                    - Use **LaTeX** for ALL mathematical formulas, constants, and variables.
                    - CRITICAL: Wrap ALL LaTeX in DOUBLE dollar signs (e.g., $E=mc^2$ is WRONG, use $$E=mc^2$$). 
                    - All LaTeX commands MUST start with a double backslash in JSON (e.g., $$\\lambda$$, $$\\sigma$$).
                    - For technical subscripts, use \\mathrm{...} (e.g., $$C_{\\mathrm{GME}}$$).
                    - Use bold markdown (e.g., **Quantum Coherence**) for key technical terms.
                    - Start each bullet with a relevant scientific emoji (e.g., ⚛️, 🧬).
                    - DATA INTEGRITY: Only use information provided in the Context. Do NOT invent numbers or results.
                    - Be extremely technical and specific.

                    Return a JSON object:
                    {
                        "bullets": [
                            "⚛️ Achieved **99.8% Fidelity** in gate operations using a new **Cryogenic Shielding** technique.",
                            "..."
                        ]
                    }
                    """
                },
                {
                    "role": "user",
                    "content": context
                }
            ],
            "temperature": 0.3,
            "response_format": {"type": "json_object"}
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
                    timeout=20.0
                )
                
                if response.status_code == 200:
                    data = response.json()
                    raw_content = data['choices'][0]['message']['content']
                    
                    # Pre-process raw string to handle common JSON/LaTeX escape issues
                    # JSON requires double backslashes for LaTeX which the LLM often forgets
                    processed_content = raw_content.replace("\\", "\\\\")
                    # But wait, if it already doubled them, we don't want quadruple. 
                    # Let's try a safer approach:
                    try:
                        content = json.loads(raw_content)
                    except json.JSONDecodeError:
                        # Fallback: simple regex fix for unescaped backslashes in strings
                        import re
                        fixed = re.sub(r'(?<!\\)\\(?!["\\/bfnrt]|u[0-9a-fA-F]{4})', r'\\\\', raw_content)
                        content = json.loads(fixed)
                    
                    if "bullets" in content:
                        content["bullets"] = [b.replace("\\\\", "\\") for b in content["bullets"]]

                    # Inject programmatic metrics
                    content["metrics"] = metrics
                    content["top_skills"] = top_skills
                    return content
                
                fallback = self._generate_fallback_data(title)
                fallback["metrics"] = metrics
                fallback["top_skills"] = top_skills
                return fallback
        except Exception:
            fallback = self._generate_fallback_data(title)
            fallback["metrics"] = metrics
            fallback["top_skills"] = top_skills
            return fallback

    async def _fetch_paper_data(self, doi: str) -> Dict[str, Any]:
        """Fetches rich metadata from OpenAlex to provide more context for the semantic analysis."""
        try:
            clean_doi = doi.split('/')[-1] if '/' in doi else doi
            # Handle cases where doi is a full URL
            if "doi.org/" in doi:
                url = f"https://api.openalex.org/works/{doi}"
            else:
                url = f"https://api.openalex.org/works/https://doi.org/{clean_doi}"

            headers = {
                "User-Agent": "ResQitApp/1.0 (mailto:vikki.4me@gmail.com)",
                "Accept": "application/json"
            }
            async with httpx.AsyncClient(headers=headers) as client:
                res = await client.get(url)
                if res.status_code == 200:
                    data = res.json()
                    abstract = self._reconstruct_abstract(data.get("abstract_inverted_index")) if data.get("abstract_inverted_index") else None
                    
                    # Enhanced concept extraction including 'level' (depth) and 'score' (relevance) 
                    # to help the LLM estimate complexity and field-bridging entropy
                    concepts = [
                        f"{c.get('display_name')} (Level: {c.get('level')}, Relevance: {c.get('score')})" 
                        for c in data.get("concepts", [])[:15]
                    ]
                    
                    return {
                        "abstract": abstract,
                        "concepts": concepts,
                        "cited_by_count": data.get("cited_by_count", 0)
                    }
            return {}
        except Exception:
            return {}

    async def generate_presentation(self, title: str, doi: Optional[str] = None) -> Dict[str, Any]:
        """
        Generates a structured presentation outline for a scientific paper.
        """
        paper_data = await self._fetch_paper_data(doi) if doi else {"title": title}
        
        context = f"Title: {title}\n"
        if paper_data.get("abstract"):
            context += f"Abstract: {paper_data['abstract']}\n"
        if paper_data.get("concepts"):
            context += f"Concepts: {paper_data['concepts']}\n"

        prompt = {
            "model": self.model,
            "messages": [
                {
                    "role": "system",
                    "content": r"""You are an expert academic presenter. 
                    Convert the provided paper DNA into a professional 7-slide presentation outline.
                    
                    STRUCTURE:
                    1. Title Slide (Impactful title, core theme)
                    2. The Problem (The gap in current knowledge)
                    3. Methodology (The 'How' - technical and precise)
                    4. Key Discovery (The 'Eureka' moment with data/results)
                    5. Complexity & Entropy (Why this work is non-trivial)
                    6. Real-world Application (Industrial or theoretical impact)
                    7. Future Frontier (What's next?)

                    STYLING RULES:
                    - Each slide must have a 'title' and 3-4 'bullets'.
                    - Use **LaTeX** for all formulas ($...$).
                    - Use bold markdown for technical terms.
                    - Keep it high-density and professional.

                    Return a JSON object:
                    {
                        "slides": [
                            { "title": "...", "bullets": ["...", "..."] },
                            ...
                        ]
                    }
                    """
                },
                {
                    "role": "user",
                    "content": context
                }
            ],
            "temperature": 0.4,
            "response_format": {"type": "json_object"}
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
                    timeout=30.0
                )
                if response.status_code == 200:
                    return json.loads(response.json()['choices'][0]['message']['content'])
        except Exception:
            pass
        return {"slides": []}

    def _generate_fallback_data(self, title: str) -> Dict[str, Any]:
        return {
            "bullets": [
                f" Investigates the core dynamics of {title.lower()}.",
                "• Proposes a specialized framework for theoretical modeling.",
                "• Establishes new baselines for experimental verification.",
                "• Highlights critical implications for the field's trajectory."
            ],
            "metrics": {
                "creativity": random.randint(70, 95),
                "complexity": random.randint(75, 98),
                "skill_set_score": random.randint(65, 90)
            },
            "top_skills": ["Theoretical Physics", "Advanced Mathematics"]
        }
