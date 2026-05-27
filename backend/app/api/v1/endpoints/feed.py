import os
from typing import Optional
from fastapi import APIRouter, Depends, Query, HTTPException
import httpx

from app.schemas.core import ConjectureResponse
from app.services.pipeline_services import PipelineServices
from app.services.industry_service import fetch_industry_opportunities
from app.services.summarization_service import is_llm_working
from app.services.openalex_service import OpenAlexService
from app.api.dependencies import (
    get_pipeline_services,
    get_openalex_service
)

router = APIRouter()

@router.get("/daily_feed")
async def get_daily_feed(
    author_id: Optional[str] = None,
    query_fallback: Optional[str] = None,
    pipeline_services: PipelineServices = Depends(get_pipeline_services)
):
    try:
        data = await pipeline_services.get_daily_feed(author_id, query_fallback=query_fallback)
        return data
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@router.get("/daily_conjecture", response_model=ConjectureResponse)
async def get_daily_conjecture(
    author_id: Optional[str] = Query(None),
    name: Optional[str] = Query(None),
    openalex_service: OpenAlexService = Depends(get_openalex_service)
):
    author_data = None
    resolved_id = None
    
    clean_id = author_id.split("/")[-1] if author_id else None
    
    fallback_conjecture = ConjectureResponse(
        id="fallback-1",
        category="Quantum Computing",
        title="The Qubit Coherence Paradox",
        hypothesis="A researcher prepares a qubit in the state $$\\frac{1}{\\sqrt{2}}(|0\\rangle + |1\\rangle)$$ and subjects it to continuous, strong projective measurements in the computational basis. According to the quantum Zeno effect, what should happen to the state's evolution?",
        options=[
            "The state rapidly collapses into a mixed state with equal probabilities.",
            "The state's evolution is effectively 'frozen', remaining in its initial superposition.",
            "The measurement completely decoheres the state into $|0\\rangle$ only.",
            "The state undergoes rapid oscillation between $|0\\rangle$ and $|1\\rangle$."
        ],
        correctOptionIndex=1,
        explanation="The quantum Zeno effect describes how frequent observation 'freezes' the evolution of a quantum system, preventing it from changing state."
    )
    
    try:
        if clean_id:
            author_data = await openalex_service.fetch_author_by_id(clean_id)
            if author_data:
                resolved_id = clean_id
        elif name:
            results = await openalex_service.search_authors(name, per_page=1)
            if results:
                author_data = results[0]
                resolved_id = author_data["id"].split("/")[-1]
        
        if not author_data or not resolved_id:
            return fallback_conjecture

        # Fetch recent works of the author
        works = await openalex_service.fetch_author_works(resolved_id, per_page=5)
        if not works:
            return fallback_conjecture
    except Exception as e:
        return fallback_conjecture

    # Build works context for LLM
    works_context = "\n".join([
        f"- Paper: {w.get('title')}\n  Abstract: {w.get('abstract_inverted_index') or ''}"
        for w in works[:3]
    ])
    
    prompt = {
        "model": "llama-3.3-70b-versatile",
        "messages": [
            {
                "role": "system",
                "content": """You are an elite scientific advisor. Your task is to generate a highly academic, rigorous scientific "conjecture/puzzle" grounded in the research areas of the provided publications.
The conjecture should describe a specific hypothetical scenario or calculation, present a mathematical or conceptual fallacy, and ask the user to identify the correct explanation or fallacy.
Format your output as a raw JSON object matching this schema:
{
  "id": "1",
  "category": "High-level discipline name (e.g. Quantum Computing, Genomics, Condensed Matter Physics)",
  "title": "A short, catchy, professional title",
  "hypothesis": "The technical scenario description, including equations in LaTeX format using $$...$$ for block or $...$ for inline equations.",
  "options": [
    "Option A description",
    "Option B description",
    "Option C description",
    "Option D description"
  ],
  "correctOptionIndex": 0,
  "explanation": "A detailed 1-2 sentence explanation of why this option is correct, including any relevant scientific theory."
}
Only output the JSON object, do not wrap it in markdown or comments. Ensure it is valid JSON."""
            },
            {
                "role": "user",
                "content": f"Researcher Name: {author_data.get('display_name')}\nSelected Publications:\n{works_context}"
            }
        ],
        "temperature": 0.3,
        "response_format": {"type": "json_object"}
    }

    if not is_llm_working():
        return fallback_conjecture
        
    groq_key = os.getenv("GROQ_API")
    if not groq_key:
        return fallback_conjecture
        
    try:
        async with httpx.AsyncClient(timeout=30.0) as client:
            res = await client.post(
                "https://api.groq.com/openai/v1/chat/completions",
                headers={"Authorization": f"Bearer {groq_key}", "Content-Type": "application/json"},
                json=prompt,
                timeout=20.0
            )
            if res.status_code == 200:
                import json
                raw_content = res.json()["choices"][0]["message"]["content"].strip()
                conjecture_data = json.loads(raw_content)
                return ConjectureResponse(**conjecture_data)
            else:
                return fallback_conjecture
    except Exception as e:
        return fallback_conjecture

@router.get("/industry_opportunities")
async def get_industry_opportunities(
    focus: str = Query("AI"),
    openalex_service: OpenAlexService = Depends(get_openalex_service)
):
    try:
        opportunities = await fetch_industry_opportunities(focus, openalex_service=openalex_service)
        return opportunities
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

