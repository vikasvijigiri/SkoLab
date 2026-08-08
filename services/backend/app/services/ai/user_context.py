"""
app/services/ai/user_context.py
=================================
Shared "who is this researcher, right now" signal: real display name, broad
concepts/topics, and recent-paper keywords. Multiple services (daily feed,
journal advisor, horizon predictions, grant/synergy matching) each need this
same signal to ground their output in the actual requesting researcher —
this module is the one place to derive it, instead of every caller
re-deriving it slightly differently (which is exactly how the journal
advisor's x_concepts bug happened earlier this session).
"""

from typing import Any, Dict, List, Tuple

from app.services.data.openalex_service import OpenAlexService, extract_field_and_expertise
from app.services.ai.llm_service import LLMService, is_llm_working


async def resolve_author_concepts_and_name(
    openalex_service: OpenAlexService, author_id: str
) -> Tuple[str, List[str]]:
    """Real display name + broad concepts/topics for a researcher, via OpenAlex.

    x_concepts is largely empty on current OpenAlex author objects (confirmed
    live, deprecated in favor of `topics`) — extract_field_and_expertise
    already handles the topics-first fallback correctly.
    """
    profile = await openalex_service.fetch_author_by_id(author_id)
    if not profile:
        return "Researcher", []
    author_name = profile.get("display_name", "Researcher")
    _, concepts = extract_field_and_expertise(profile, author_name)
    return author_name, concepts or []


async def extract_recent_paper_keywords(
    llm_service: LLMService, user_works: List[Dict[str, Any]]
) -> List[str]:
    """
    LLM-extracted technical keywords/phrases from a researcher's 5 most recent
    papers (title + abstract) — sharper and more specific than raw title/
    concept tokens alone (e.g. "spin-echo decoherence" rather than just
    "Physics"). Same extraction this session already proved out for the daily
    feed and journal advisor.
    """
    if not is_llm_working() or not user_works:
        return []

    def sort_key(w: Dict[str, Any]) -> str:
        return w.get("publication_date") or str(w.get("publication_year") or "0")

    recent = sorted(
        [w for w in user_works if w.get("title")], key=sort_key, reverse=True
    )[:5]
    if not recent:
        return []

    context_parts = []
    for w in recent:
        abstract = w.get("abstract") or w.get("_custom_abstract") or ""
        if not abstract and w.get("abstract_inverted_index"):
            abstract = reconstruct_abstract(w["abstract_inverted_index"])
        context_parts.append(f"Title: {w.get('title')}\nAbstract: {abstract[:600]}")
    context = "\n\n".join(context_parts)

    try:
        response = await llm_service.query(
            messages=[
                {
                    "role": "system",
                    "content": "You are a helpful assistant that outputs only valid raw JSON.",
                },
                {
                    "role": "user",
                    "content": (
                        "Extract 10-15 precise technical research keywords/phrases "
                        "(specific methods, models, phenomena, materials — not generic "
                        "field names like 'physics' or 'machine learning') that best "
                        "characterize this researcher's current work, based on their 5 "
                        "most recent papers below.\n\n"
                        f"{context}\n\n"
                        'Return raw JSON: {"keywords": ["...", "..."]}'
                    ),
                },
            ],
            temperature=0.2,
            max_tokens=300,
            response_format={"type": "json_object"},
        )
        if response.content:
            import json

            data = json.loads(response.content)
            keywords = data.get("keywords")
            if isinstance(keywords, list):
                return [str(k) for k in keywords if k][:15]
    except Exception as e:
        print(f"[UserContext] LLM keyword extraction failed: {e}", flush=True)
    return []


def reconstruct_abstract(inverted_index: Dict[str, List[int]]) -> str:
    try:
        word_positions: Dict[int, str] = {}
        for word, positions in inverted_index.items():
            for pos in positions:
                word_positions[pos] = word
        return " ".join(word_positions[i] for i in sorted(word_positions.keys()))
    except Exception:
        return ""


async def build_user_context(
    openalex_service: OpenAlexService,
    llm_service: LLMService,
    author_id: str,
) -> Dict[str, Any]:
    """
    One-stop researcher context: real name, broad concepts, and sharp
    recent-work keywords, deduplicated into a single query_terms list ready
    for search/embedding grounding.
    """
    author_name, concepts = await resolve_author_concepts_and_name(
        openalex_service, author_id
    )
    recent_keywords: List[str] = []
    try:
        works = await openalex_service.fetch_author_works(author_id, per_page=10)
        if works:
            recent_keywords = await extract_recent_paper_keywords(llm_service, works)
    except Exception as e:
        print(f"[UserContext] Recent-works fetch failed: {e}", flush=True)

    query_terms = list(dict.fromkeys(concepts + recent_keywords))
    return {
        "author_name": author_name,
        "concepts": concepts,
        "recent_keywords": recent_keywords,
        "query_terms": query_terms,
    }
