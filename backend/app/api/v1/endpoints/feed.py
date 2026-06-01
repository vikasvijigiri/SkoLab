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

    # Step 1: Resolve author
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
    except Exception:
        pass

    # Build a field-aware fallback conjecture based on author's research area
    fallback_category = "Physics"
    fallback_title = "The Qubit Coherence Paradox"
    fallback_hypothesis = "A researcher prepares a qubit in superposition and subjects it to continuous measurements. According to the quantum Zeno effect, what happens to the state's evolution?"
    fallback_options = [
        "The state rapidly collapses into a mixed state.",
        "The state's evolution is effectively frozen in its initial superposition.",
        "The measurement decoheres the state into |0> only.",
        "The state oscillates rapidly between |0> and |1>."
    ]
    fallback_correct = 1
    fallback_explanation = "The quantum Zeno effect: frequent measurement freezes quantum evolution, keeping the state near its initial superposition."

    if author_data:
        author_name_lower = (author_data.get("display_name") or "").lower()
        concepts = author_data.get("x_concepts") or []
        topics = author_data.get("topics") or []
        field_names = [c.get("display_name", "").lower() for c in concepts
                       if c.get("display_name") and c.get("display_name").lower() != author_name_lower]
        field_names += [t.get("display_name", "").lower() for t in topics if t.get("display_name")]
        fld = " ".join(field_names)

        if any(kw in fld for kw in ["machine learn", "artificial intel", "neural", "deep learn", "nlp", "reinforcement"]):
            fallback_category = "Machine Learning"
            fallback_title = "The Gradient Vanishing Dilemma"
            fallback_hypothesis = "A deep network with L=50 sigmoid layers runs backpropagation. What happens to the gradient as it propagates from layer L to layer 1?"
            fallback_options = [
                "Stays near constant due to sigmoid's bounded range.",
                "Grows exponentially (exploding gradient).",
                "Shrinks exponentially, approaching zero (vanishing gradient).",
                "Oscillates between positive and negative."
            ]
            fallback_correct = 2
            fallback_explanation = "Sigmoid derivative is at most 0.25. Multiplied 50 times it approaches zero — the vanishing gradient problem, solved by ReLU and ResNets."
        elif any(kw in fld for kw in ["biol", "genet", "medicine", "neuro", "protein", "genom"]):
            fallback_category = "Molecular Biology"
            fallback_title = "The Central Dogma Inversion"
            fallback_hypothesis = "A retrovirus integrates its RNA genome using reverse transcriptase. Which step of the central dogma does this process reverse?"
            fallback_options = [
                "DNA to RNA transcription — RNA is used as template.",
                "RNA to DNA — reversing normal transcription direction.",
                "Translation — bypassing ribosomes entirely.",
                "None — this is a standard eukaryotic process."
            ]
            fallback_correct = 1
            fallback_explanation = "Retroviruses use reverse transcriptase to copy RNA to DNA, directly reversing the canonical transcription direction of the central dogma."
        elif any(kw in fld for kw in ["relativity", "gravit", "cosmol", "particle", "high energy", "astrophys"]):
            fallback_category = "General Relativity"
            fallback_title = "The Gravitational Time Dilation Puzzle"
            fallback_hypothesis = "Clock A is at sea level; Clock B atop Everest (h=8848 m). After one year, which shows more elapsed time?"
            fallback_options = [
                "Clock A — stronger gravity speeds up time.",
                "Clock B — higher gravitational potential means time flows faster.",
                "Both identical; altitude has no effect.",
                "Clock B runs slower due to faster rotation at altitude."
            ]
            fallback_correct = 1
            fallback_explanation = "General relativity: clocks at higher gravitational potential tick faster. The difference is approximately 22 microseconds per year for Everest altitude."

    fallback_conjecture = ConjectureResponse(
        id="fallback-1",
        category=fallback_category,
        title=fallback_title,
        hypothesis=fallback_hypothesis,
        options=fallback_options,
        correctOptionIndex=fallback_correct,
        explanation=fallback_explanation
    )

    try:
        if not author_data or not resolved_id:
            return fallback_conjecture
        # Fetch recent works of the author
        works = await openalex_service.fetch_author_works(resolved_id, per_page=5)
        if not works:
            return fallback_conjecture
    except Exception:
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

from app.db.database import get_db
from sqlalchemy.ext.asyncio import AsyncSession

@router.get("/industry_opportunities")
async def get_industry_opportunities(
    focus: str = Query("AI"),
    name: Optional[str] = Query(None),
    openalex_service: OpenAlexService = Depends(get_openalex_service),
    db: AsyncSession = Depends(get_db)
):
    try:
        opportunities = await fetch_industry_opportunities(focus, name=name, openalex_service=openalex_service, db=db)
        return opportunities
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@router.get("/assistant_professor_roadmap")
async def get_assistant_professor_roadmap(
    author_id: Optional[str] = Query(None),
    name: Optional[str] = Query(None),
    focus: str = Query("AI"),
    openalex_service: OpenAlexService = Depends(get_openalex_service)
):
    try:
        user_name = name or "Vikas Vijigiri"
        h_index = 15
        works_count = 24
        citations = 280
        disruption_score = 0.85

        clean_id = author_id.split("/")[-1] if author_id else None
        
        # Resolve real author statistics from OpenAlex if possible
        if clean_id:
            author_data = await openalex_service.fetch_author_by_id(clean_id)
        elif name:
            results = await openalex_service.search_authors(name, per_page=1)
            author_data = results[0] if results else None
        else:
            author_data = None

        if author_data:
            user_name = author_data.get("display_name") or user_name
            h_index = author_data.get("summary_stats", {}).get("h_index") or h_index
            works_count = author_data.get("works_count") or works_count
            citations = author_data.get("summary_stats", {}).get("cited_by_count") or citations
            disruption_score = 0.85

        is_physics = "phys" in focus.lower()
        target_h = 18 if is_physics else 20
        target_works = 30 if is_physics else 28
        target_citations = 350 if is_physics else 400
        target_disruption = 0.80 if is_physics else 0.78
        
        milestones = [
            {"title": "Ph.D. Defense", "status": "Completed", "date": "2024", "description": "Successfully completed and defended doctoral thesis."},
            {"title": "Postdoctoral Fellowship", "status": "Current", "date": "2025-2026", "description": "Active research and publication cycle at current institution."},
            {"title": "Senior Research Fellow", "status": "Upcoming", "date": "2027", "description": "Targeted leadership role of lab projects/grants."},
            {"title": "Assistant Professor (Tenure-Track)", "status": "Target", "date": "2028", "description": "Applying to leading academic openings."}
        ]
        
        checklist = [
            {"task": "Publish 2+ first-author publications in Q1 journals", "status": "In Progress", "priority": "High"},
            {"task": "Co-author a paper with a Tier-1 Global Institution (e.g., MIT/Stanford)", "status": "Completed", "priority": "Medium"},
            {"task": "Act as an invited peer reviewer for top journals/conferences", "status": "Completed", "priority": "Low"},
            {"task": "Submit a major national research fellowship proposal (NSF/ERC)", "status": "Pending", "priority": "High"},
            {"task": "Complete teaching credits or deliver guest lecture series", "status": "Pending", "priority": "Medium"}
        ]
        
        coauthors = [
            {"name": "Dr. Sarah Jenkins", "institution": "Stanford University", "field": focus, "match": "94%"},
            {"name": "Dr. Alexei Romanov", "institution": "MIT Neural Systems Lab", "field": focus, "match": "88%"},
            {"name": "Dr. Priya Patel", "institution": "Oxford Research Group", "field": focus, "match": "85%"}
        ]

        templates = [
            {"name": "Research Statement Template", "description": "3-page narrative describing your research vision, key contributions, and funding plans.", "downloadUrl": "https://skolab.open/templates/research_statement.pdf"},
            {"name": "Teaching Statement Outline", "description": "Statement details on pedagogy, student mentoring, and diversity/equity strategies.", "downloadUrl": "https://skolab.open/templates/teaching_statement.pdf"},
            {"name": "Tenure-Track CV Template", "description": "Curated academic CV structure tailored for assistant professor applications.", "downloadUrl": "https://skolab.open/templates/academic_cv.docx"}
        ]

        return {
            "userName": user_name,
            "researchFocus": focus,
            "userMetrics": {
                "hIndex": h_index,
                "worksCount": works_count,
                "citationCount": citations,
                "disruptionScore": disruption_score
            },
            "targetMetrics": {
                "hIndex": target_h,
                "worksCount": target_works,
                "citationCount": target_citations,
                "disruptionScore": target_disruption
            },
            "milestones": milestones,
            "checklist": checklist,
            "peerCoauthors": coauthors,
            "templates": templates
        }
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


