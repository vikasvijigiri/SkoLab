from dotenv import load_dotenv

# MUST happen BEFORE any import that auto-initialises Firebase or reads env vars
load_dotenv()

import random
import httpx
import os
import asyncio
import io
import pdfplumber
from fastapi import FastAPI, Query, BackgroundTasks, UploadFile, File, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from typing import List, Optional, Union
from pydantic import BaseModel
from firebase_admin import firestore
from app.core.config import settings          # ← single source of truth
from app.services.prediction_service import PredictionService
from app.services.summarization_service import SummarizationService
from app.services.pipeline_services import PipelineServices
try:
    from app.services.researcher_worker import teleport_researcher, FIRESTORE_AVAILABLE
except ImportError:
    teleport_researcher = None
    FIRESTORE_AVAILABLE = False
from zeroconf import ServiceInfo
from zeroconf.asyncio import AsyncZeroconf
import socket
import time

def _get_openalex_headers() -> dict:
    headers = {
        "User-Agent": "SkolabApp/1.0 (mailto:vikki.4me@gmail.com)",
        "Accept": "application/json"
    }
    if settings.openalex_api_key:
        headers["api_key"] = settings.openalex_api_key
    return headers

class SimpleAsyncCache:
    def __init__(self, ttl_seconds: float, max_size: int = 200):
        self.ttl = ttl_seconds
        self.max_size = max_size
        self.cache = {}  # key -> (value, expiry_timestamp)
        self.lock = asyncio.Lock()

    async def get(self, key: str):
        async with self.lock:
            entry = self.cache.get(key)
            if entry is None:
                return None
            value, expiry = entry
            if time.time() > expiry:
                del self.cache[key]
                return None
            return value

    async def set(self, key: str, value):
        async with self.lock:
            print(f"[SimpleAsyncCache] set: '{key}'", flush=True)
            now = time.time()
            # Clean expired keys
            expired_keys = [k for k, (_, exp) in self.cache.items() if now > exp]
            for k in expired_keys:
                del self.cache[k]
            
            # Evict oldest if full
            if len(self.cache) >= self.max_size:
                oldest_key = next(iter(self.cache))
                del self.cache[oldest_key]
                
            self.cache[key] = (value, now + self.ttl)

    async def delete(self, key: str):
        async with self.lock:
            print(f"[SimpleAsyncCache] delete: '{key}'", flush=True)
            if key in self.cache:
                del self.cache[key]

    async def clear(self):
        async with self.lock:
            self.cache.clear()

suggestions_cache = SimpleAsyncCache(ttl_seconds=1800, max_size=300)
profile_cache = SimpleAsyncCache(ttl_seconds=3600, max_size=100)
# Intelligence results are expensive (PDF download + LLM) — cache for 6 hours
analyze_paper_cache = SimpleAsyncCache(ttl_seconds=21600, max_size=200)

daily_feed_cache = SimpleAsyncCache(ttl_seconds=3600, max_size=100)
match_grants_cache = SimpleAsyncCache(ttl_seconds=3600, max_size=100)
collaborator_synergy_cache = SimpleAsyncCache(ttl_seconds=7200, max_size=200)
citation_heatmap_cache = SimpleAsyncCache(ttl_seconds=3600, max_size=100)
journal_advisor_cache = SimpleAsyncCache(ttl_seconds=7200, max_size=100)
network_collaborators_cache = SimpleAsyncCache(ttl_seconds=3600, max_size=100)

app = FastAPI(
    title="Skolab API",
    description="The backend API for the Skolab platform",
    version="1.0.0",
)

# ── mDNS service advertisement ───────────────────────────────────────────────
# The Android app uses NSD (Network Service Discovery) to find this server
# automatically — no IP address is ever hardcoded on either side.
_zeroconf: AsyncZeroconf | None = None
_mdns_info: ServiceInfo | None = None


@app.on_event("startup")
async def init_postgres() -> None:
    from app.db.database import init_db
    print("[Postgres] Initializing local database schema...", flush=True)
    try:
        await init_db()
        print("[Postgres] Database initialization successful.", flush=True)
    except Exception as e:
        print(f"[Postgres] Database initialization failed: {e}", flush=True)

@app.on_event("startup")
async def verify_firestore() -> None:
    """Check if Firestore connection is responsive. If not, bypass to fallback."""
    from app.services.researcher_worker import check_connection_sync, set_firestore_available
    import concurrent.futures
    
    print("[Firestore] Verifying Firestore connection on startup...", flush=True)
    
    # Wipe stale caches on startup to force fresh loading of updated schemas/authors
    try:
        await suggestions_cache.clear()
        await profile_cache.clear()
        await daily_feed_cache.clear()
        await network_collaborators_cache.clear()
        print("[Cache] All startup caches cleared successfully!", flush=True)
    except Exception as e:
        print(f"[Cache] Startup clear failed: {e}", flush=True)

    loop = asyncio.get_event_loop()
    try:
        executor = concurrent.futures.ThreadPoolExecutor(max_workers=1)
        future = loop.run_in_executor(executor, check_connection_sync)
        success = await asyncio.wait_for(future, timeout=3.0)
        set_firestore_available(success)
        executor.shutdown(wait=False)
    except asyncio.TimeoutError:
        print("[Firestore] Connection check timed out after 3.0s. Firestore is disabled.", flush=True)
        set_firestore_available(False)
    except Exception as e:
        print(f"[Firestore] Connection check failed: {e}. Firestore is disabled.", flush=True)
        set_firestore_available(False)


@app.on_event("startup")
async def register_mdns() -> None:
    """Advertise this server on the LAN so mobile clients can discover it."""
    global _zeroconf, _mdns_info
    import traceback
    try:
        ips = []
        try:
            for info in socket.getaddrinfo(socket.gethostname(), None):
                ip = info[4][0]
                if "." in ip and not ip.startswith("127.") and not ip.startswith("169.254"):
                    if ip not in ips:
                        ips.append(ip)
        except Exception as e:
            print(f"[mDNS] Failed to get IPs via getaddrinfo: {e}", flush=True)
        
        if not ips:
            ips = [settings.lan_ip]

        addresses = [socket.inet_aton(ip) for ip in ips]
        print(f"[mDNS] Advertising backend on IPs: {ips}", flush=True)

        _mdns_info = ServiceInfo(
            type_=settings.mdns_service_type,
            name=settings.mdns_fqdn,
            addresses=addresses,
            port=settings.port,
            properties={"path": "/", "version": "1"},
        )
        _zeroconf = AsyncZeroconf()
        await _zeroconf.async_register_service(_mdns_info, allow_name_change=True)
        print(
            f"[mDNS] '{settings.mdns_service_name}' registered at "
            f"{ips}:{settings.port}"
        )
    except Exception as exc:
        print(f"[mDNS] Registration failed: {exc}")
        traceback.print_exc()


@app.on_event("shutdown")
async def unregister_mdns() -> None:
    """Clean up the mDNS advertisement on shutdown."""
    if _zeroconf and _mdns_info:
        await _zeroconf.async_unregister_service(_mdns_info)
        await _zeroconf.async_close()
        print(f"[mDNS] '{settings.mdns_service_name}' unregistered")


prediction_service = PredictionService()
summarization_service = SummarizationService()
pipeline_services = PipelineServices()

# Configure CORS
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

class Work(BaseModel):
    id: Optional[str] = None
    title: Optional[str] = None
    year: Optional[int] = None
    doi: Optional[str] = None
    journal: Optional[str] = None          # journal / venue name
    is_open_access: bool = False
    citations: int = 0
    creativity_score: float = 0.0
    complexity_score: float = 0.0
    impact_factor: float = 0.0
    disruption_score: float = 0.0
    semantic_novelty: float = 0.0
    open_science_score: float = 0.0
    authors: Optional[List[str]] = None

class AuthorSuggestion(BaseModel):
    id: str
    display_name: str
    institution: str
    field_of_study: Optional[str] = None
    h_index: Optional[int] = None
    innovation_score: Optional[int] = None

class AuthorResponse(BaseModel):
    id: str
    display_name: str
    orcid: Optional[str] = None
    h_index: int
    i10_index: int
    works_count: int
    cited_by_count: int
    institution: str
    field_of_study: Optional[str] = None
    expertise: List[str] = []
    academic_history: List[str]
    works: List[Work]
    innovation_score: Optional[float] = None
    # metrics_computed: False means LLM analysis hasn't run yet — UI should show N/A for computed metrics
    metrics_computed: bool = False
    llm_active: bool = True
    # Modern Metrics (Averages/Global)
    average_creativity: float = 0.0
    average_complexity: float = 0.0
    average_skill_score: float = 0.0
    average_impact: float = 0.0
    average_activity: float = 0.0
    # The 10 Specific Metrics
    disruption_score: float = 0.0
    citation_acceleration: float = 0.0
    future_impact_score: float = 0.0
    network_centrality: float = 0.0
    semantic_novelty: float = 0.0
    interdisciplinary_index: float = 0.0
    policy_patent_score: float = 0.0
    open_science_score: float = 0.0
    collaboration_diversity: float = 0.0
    research_consistency: float = 0.0
    next_prediction: Optional[str] = None
    similar_researchers: List[AuthorSuggestion] = []

class PaperIntelligenceResponse(BaseModel):
    tldr: str = ""
    key_findings: List[str] = []
    techniques: List[str] = []
    tools_and_software: List[str] = []
    core_concepts: List[str] = []
    formulas: List[str] = []
    limitations: List[str] = []
    real_world_impact: str = ""
    future_directions: List[str] = []
    confidence: str = "Medium"
    text_source: str = "abstract_only"

class ChatMessage(BaseModel):
    role: str
    content: str

class ChatRequest(BaseModel):
    author_id: str
    paper_title: str
    user_message: str
    history: List[ChatMessage] = []

@app.get("/summarize_work")
async def summarize_work(title: str = Query(...), doi: Optional[str] = None):
    data = await summarization_service.summarize_paper(title, doi)
    return data

@app.get("/analyze_paper", response_model=PaperIntelligenceResponse)
async def analyze_paper(
    title: str = Query(..., description="Full paper title"),
    doi: Optional[str] = Query(None, description="DOI of the paper (e.g. 10.48550/arXiv.1706.03762)"),
    openalex_id: Optional[str] = Query(None, description="OpenAlex work ID (e.g. W2741809807 or full URL)"),
):
    """
    Deep paper intelligence: reads the FULL paper text (PDF when available)
    and extracts 9 structured insight dimensions via a Research Intelligence Agent LLM.
    
    PDF sources tried (in order):
      1. OpenAlex open-access PDF URL
      2. arXiv direct PDF (auto-detected from DOI)
      3. Unpaywall API
      4. Semantic Scholar PDF link
      5. Abstract + metadata fallback (when no PDF is accessible)
    """
    cache_key = f"intelligence:{openalex_id or doi or title}"
    cached = await analyze_paper_cache.get(cache_key)
    if cached is not None:
        print(f"[/analyze_paper] Cache hit for: {title[:50]}", flush=True)
        return cached

    result = await summarization_service.analyze_paper(
        title=title,
        doi=doi,
        openalex_id=openalex_id,
    )
    await analyze_paper_cache.set(cache_key, result)
    return result

@app.get("/presentation_outline")
async def presentation_outline(title: str = Query(...), doi: Optional[str] = None):
    data = await summarization_service.generate_presentation(title, doi)
    return data

@app.get("/ai_status")
async def ai_status():
    """Checks if the AI services have valid API keys and are reachable."""
    from app.services.summarization_service import is_llm_working
    groq_key = os.getenv("GROQ_API")
    has_key = groq_key is not None and len(groq_key) > 10
    return {
        "groq_api_configured": has_key,
        "llm_active": is_llm_working(),
        "model": "llama-3.3-70b-versatile",
        "key_prefix": groq_key[:7] if has_key else "None"
    }

class ConjectureResponse(BaseModel):
    id: str
    category: str
    title: str
    hypothesis: str
    options: List[str]
    correctOptionIndex: int
    explanation: str

@app.get("/daily_conjecture", response_model=ConjectureResponse)
async def get_daily_conjecture(author_id: Optional[str] = Query(None), name: Optional[str] = Query(None)):
    BASE_URL = "https://api.openalex.org"
    headers = _get_openalex_headers()
    author_data = None
    resolved_id = None
    
    clean_id = author_id.split("/")[-1] if author_id else None
    from fastapi import HTTPException
    
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
        async with httpx.AsyncClient(timeout=15.0, headers=headers) as client:
            if clean_id:
                res = await client.get(f"{BASE_URL}/authors/{clean_id}", params={"mailto": "vikki.4me@gmail.com"})
                if res.status_code == 200:
                    author_data = res.json()
                    resolved_id = clean_id
            elif name:
                res = await client.get(f"{BASE_URL}/authors", params={"search": name, "per_page": 1, "mailto": "vikki.4me@gmail.com"})
                if res.status_code == 200:
                    results = res.json().get("results", [])
                    if results:
                        author_data = results[0]
                        resolved_id = author_data["id"].split("/")[-1]
            
            if not author_data or not resolved_id:
                return fallback_conjecture

            # Fetch recent works of the author
            works_res = await client.get(
                f"{BASE_URL}/works",
                params={
                    "filter": f"authorships.author.id:{resolved_id}",
                    "per_page": 5,
                    "sort": "publication_year:desc",
                    "mailto": "vikki.4me@gmail.com"
                }
            )
            works = works_res.json().get("results", []) if works_res.status_code == 200 else []
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

    from app.services.summarization_service import is_llm_working
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

@app.get("/")
def read_root():
    return {"message": "Welcome to the SkoLab API!"}


class Quest(BaseModel):
    id: str
    title: str
    reward_entropy: int
    is_completed: bool

@app.get("/users/quests", response_model=List[Quest])
async def get_user_quests(user_id: str = Query(..., description="The user ID")):
    from sqlalchemy.future import select
    from app.db.database import AsyncSessionLocal
    from app.models.user_models import UserPreference, User

    async with AsyncSessionLocal() as session:
        # Check if quests preference exists for the user
        stmt = select(UserPreference).where(
            UserPreference.user_id == user_id,
            UserPreference.preference_key == "quests"
        )
        result = await session.execute(stmt)
        pref = result.scalars().first()
        
        default_quests = [
            {"id": "discovery", "title": "Review 5 papers", "reward_entropy": 15, "is_completed": False},
            {"id": "logic", "title": "Solve a Conjecture", "reward_entropy": 50, "is_completed": False},
            {"id": "profile", "title": "Endorse a Colleague", "reward_entropy": 10, "is_completed": False}
        ]
        
        if not pref:
            # Let's ensure the user exists first
            user_stmt = select(User).where(User.id == user_id)
            user_result = await session.execute(user_stmt)
            user = user_result.scalars().first()
            if not user:
                user = User(id=user_id, display_name=f"User_{user_id[:8]}" if len(user_id) > 8 else user_id)
                session.add(user)
                await session.flush()
                
            pref = UserPreference(
                user_id=user_id,
                preference_key="quests",
                preference_value=default_quests
            )
            session.add(pref)
            await session.commit()
            quests_data = default_quests
        else:
            quests_data = pref.preference_value or default_quests
            
        return [Quest(**q) for q in quests_data]

@app.post("/users/quests/complete")
async def complete_quest(user_id: str = Query(...), quest_id: str = Query(...)):
    from sqlalchemy.future import select
    from app.db.database import AsyncSessionLocal
    from app.models.user_models import UserPreference
    from sqlalchemy.orm.attributes import flag_modified

    async with AsyncSessionLocal() as session:
        stmt = select(UserPreference).where(
            UserPreference.user_id == user_id,
            UserPreference.preference_key == "quests"
        )
        result = await session.execute(stmt)
        pref = result.scalars().first()
        
        if not pref:
            await get_user_quests(user_id)
            result = await session.execute(stmt)
            pref = result.scalars().first()
            
        if pref:
            quests = list(pref.preference_value) if pref.preference_value else []
            updated = False
            reward = 0
            for q in quests:
                if q["id"] == quest_id:
                    q["is_completed"] = True
                    reward = q["reward_entropy"]
                    updated = True
                    break
            if updated:
                pref.preference_value = quests
                flag_modified(pref, "preference_value")
                await session.commit()
                return {"status": "success", "message": f"Quest {quest_id} completed", "entropy_awarded": reward}
                
        return {"status": "error", "message": f"Quest {quest_id} not found or initialization failed"}

class LeaderboardEntry(BaseModel):
    rank: int
    user_name: str
    institution: str
    entropy_score: int

@app.get("/leaderboard/{field}", response_model=List[LeaderboardEntry])
async def get_leaderboard(field: str):
    try:
        from app.services.researcher_worker import FIRESTORE_AVAILABLE
        if FIRESTORE_AVAILABLE:
            db = firestore.client()
            from google.cloud.firestore_v1.base_query import FieldFilter
            
            # Query Firestore for top researchers in the field
            query_ref = db.collection("global_researchers")
            if field and field != "All Fields" and field != "all":
                query_ref = query_ref.where(filter=FieldFilter("field_of_study", "==", field))
            
            docs = query_ref.order_by("innovation_score", direction=firestore.Query.DESCENDING).limit(10).stream()
            
            results = []
            for idx, doc in enumerate(docs):
                d = doc.to_dict()
                results.append(
                    LeaderboardEntry(
                        rank=idx + 1,
                        user_name=d.get("display_name") or "Unknown Researcher",
                        institution=d.get("current_institution") or "Independent",
                        entropy_score=int(d.get("innovation_score") or 0)
                    )
                )
            if results:
                return results
    except Exception as e:
        print(f"[Leaderboard] Error fetching real data: {e}", flush=True)
    
    return []


@app.get("/author_suggestions", response_model=List[AuthorSuggestion])
async def get_author_suggestions(query: str = Query(...)):
    cache_key = query.strip().lower()
    cached = await suggestions_cache.get(cache_key)
    if cached is not None:
        print(f"[Cache Hit] Suggestions for: '{query}'", flush=True)
        return cached

    # 1. Search Firestore for suggestions first (Fast)
    # from researcher_worker import FIRESTORE_AVAILABLE
    if False:
        try:
            db = firestore.client()
            # Use the 'filter' keyword argument to avoid UserWarnings and follow modern Firestore API
            from google.cloud.firestore_v1.base_query import FieldFilter
            
            docs = db.collection("global_researchers")\
                .where(filter=FieldFilter("display_name", ">=", query))\
                .where(filter=FieldFilter("display_name", "<=", query + "\uf8ff"))\
                .limit(10)\
                .get()
            
            if docs:
                suggestions = []
                for d in [doc.to_dict() for doc in docs]:
                    h_idx = d.get("h_index")
                    inv_score = d.get("innovation_score")
                    suggestions.append(AuthorSuggestion(
                        id=d.get("openalex_id"),
                        display_name=d.get("display_name"),
                        institution=d.get("current_institution") or "Independent Researcher",
                        field_of_study=d.get("field_of_study"),
                        h_index=int(h_idx) if h_idx is not None else None,
                        innovation_score=int(inv_score) if inv_score is not None else None
                    ))
                await suggestions_cache.set(cache_key, suggestions)
                return suggestions
        except Exception as e:
            print(f"Firestore Suggester Error: {e}")

    # 2. Fallback to OpenAlex
    BASE_URL = "https://api.openalex.org"
    timeout = httpx.Timeout(15.0, connect=5.0)
    headers = _get_openalex_headers()
    
    import re
    non_person_keywords = re.compile(
        r"\b(collaboration|group|consortium|committee|team|network|project|society|association|institute|university|department|lab|laboratory|center|centre|foundation|quantum|topology|invariants|materials|systems|physics|biology|chemistry|science|computing|theory|applications|methods|frontiers|research)\b",
        re.IGNORECASE
    )

    async with httpx.AsyncClient(timeout=timeout) as client:
        suggestions = []
        try:
            # First try direct author search
            res = await client.get(f"{BASE_URL}/authors", params={"search": query, "per_page": 10, "mailto": "vikki.4me@gmail.com"}, headers=headers)
            if res.status_code == 200:
                results = res.json().get("results", [])
                for author in results:
                    disp_name = author.get("display_name")
                    if not disp_name:
                        continue
                    
                    # Filter out non-person names
                    if non_person_keywords.search(disp_name):
                        continue
                    
                    # Check that name is at least 2 words and not excessively long
                    words = disp_name.strip().split()
                    if len(words) < 2 or len(words) > 4:
                        continue
                        
                    last_insts = author.get("last_known_institutions")
                    inst = "Independent Researcher"
                    if last_insts and isinstance(last_insts, list) and len(last_insts) > 0:
                        first_inst = last_insts[0]
                        if first_inst and isinstance(first_inst, dict):
                            inst = first_inst.get("display_name") or "Independent Researcher"
                    
                    stats = author.get("summary_stats") or {}
                    h_idx = stats.get("h_index")
                    
                    suggestions.append(AuthorSuggestion(
                        id=author["id"],
                        display_name=disp_name,
                        institution=inst,
                        h_index=int(h_idx) if h_idx is not None else None,
                        innovation_score=None
                    ))
            
            # If we don't have enough suggestions (less than 4), it's likely a topic query or has too many filtered names.
            # Fall back to searching works and extracting authors.
            if len(suggestions) < 4:
                print(f"[Fallback to Works] Insufficient human authors for '{query}', searching works...", flush=True)
                works_res = await client.get(f"{BASE_URL}/works", params={"search": query, "per_page": 20, "mailto": "vikki.4me@gmail.com"}, headers=headers)
                if works_res.status_code == 200:
                    works = works_res.json().get("results", [])
                    seen_ids = {s.id for s in suggestions}
                    for work in works:
                        for authorship in work.get("authorships", []):
                            author = authorship.get("author", {})
                            auth_name = author.get("display_name")
                            auth_id = author.get("id")
                            
                            if not auth_name or not auth_id or auth_id in seen_ids:
                                continue
                                
                            if non_person_keywords.search(auth_name):
                                continue
                                
                            words = auth_name.strip().split()
                            if len(words) < 2 or len(words) > 4:
                                continue
                                
                            last_insts = authorship.get("institutions", [])
                            inst = "Independent Researcher"
                            if last_insts:
                                inst = last_insts[0].get("display_name") or "Independent Researcher"
                                
                            seen_ids.add(auth_id)
                            suggestions.append(AuthorSuggestion(
                                id=auth_id,
                                display_name=auth_name,
                                institution=inst,
                                h_index=None,
                                innovation_score=None
                            ))
                            if len(suggestions) >= 10:
                                break
                        if len(suggestions) >= 10:
                            break
                            
            if suggestions:
                await suggestions_cache.set(cache_key, suggestions)
                return suggestions
        except Exception as e:
            print(f"OpenAlex Suggester Error: {e}")
    return []

@app.get("/refresh_author")
async def refresh_author(name: str = Query(...), background_tasks: BackgroundTasks = BackgroundTasks()):
    """Explicitly re-runs the teleportation worker to update Firestore data."""
    try:
        # Invalidate cache for this author name
        cache_key = name.strip().lower()
        await profile_cache.delete(cache_key)
        await suggestions_cache.delete(cache_key)

        # Search OpenAlex for the ID first
        BASE_URL = "https://api.openalex.org"
        headers = _get_openalex_headers()
        async with httpx.AsyncClient(timeout=20.0) as client:
            res = await client.get(
                f"{BASE_URL}/authors", 
                params={"search": name, "per_page": 1, "mailto": "vikki.4me@gmail.com"},
                headers=headers
            )
            if res.status_code == 200:
                results = res.json().get("results", [])
                if results:
                    author_id = results[0]["id"]
                    # Trigger worker
                    if teleport_researcher is not None:
                        background_tasks.add_task(teleport_researcher, author_id)
                    return {"status": "Refresh started", "author_id": author_id}
        from fastapi import HTTPException
        raise HTTPException(status_code=404, detail="Author not found for refresh")
    except Exception as e:
        from fastapi import HTTPException
        raise HTTPException(status_code=500, detail=str(e))

async def fetch_similar_authors(query_term: str, exclude_id: str) -> List[AuthorSuggestion]:
    if not query_term or query_term == "Multidisciplinary":
        query_term = "artificial intelligence"
        
    BASE_URL = "https://api.openalex.org"
    timeout = httpx.Timeout(10.0, connect=3.0)
    headers = _get_openalex_headers()
    
    async with httpx.AsyncClient(timeout=timeout) as client:
        params = {
            "search": query_term,
            "per_page": 8,
            "mailto": "vikki.4me@gmail.com"
        }
        try:
            res = await client.get(f"{BASE_URL}/authors", params=params, headers=headers)
            if res.status_code == 200:
                results = res.json().get("results", [])
                suggestions = []
                for author in results:
                    author_id = author.get("id", "")
                    clean_id = author_id.split("/")[-1]
                    clean_exclude_id = exclude_id.split("/")[-1]
                    if clean_id == clean_exclude_id:
                        continue
                    
                    last_insts = author.get("last_known_institutions")
                    inst = "Independent Researcher"
                    if last_insts and isinstance(last_insts, list) and len(last_insts) > 0:
                        first_inst = last_insts[0]
                        if first_inst and isinstance(first_inst, dict):
                            inst = first_inst.get("display_name") or "Independent Researcher"
                    
                    concepts = author.get("x_concepts", [])
                    field = concepts[0].get("display_name", "Multidisciplinary") if concepts else "Multidisciplinary"
                    
                    stats = author.get("summary_stats") or {}
                    h_idx = stats.get("h_index")
                    
                    suggestions.append(AuthorSuggestion(
                        id=author_id,
                        display_name=author.get("display_name", "Unknown"),
                        institution=inst,
                        field_of_study=field,
                        h_index=int(h_idx) if h_idx is not None else None,
                        innovation_score=None
                    ))
                    if len(suggestions) >= 5:
                        break
                return suggestions
        except Exception as e:
            print(f"Error fetching similar authors: {e}", flush=True)
    return []

@app.get("/search_author", response_model=Union[AuthorResponse, dict])
async def search_author(
    name: str = Query(...),
    id: Optional[str] = Query(None),
    background_tasks: BackgroundTasks = BackgroundTasks()
):
    """
    ARCHITECTURE: OpenAlex is the single source of truth for all profile data.
    - Works, titles, institution, counts → OpenAlex ONLY, returned immediately.
    - LLM metrics (disruption_score, predictions, etc.) → background teleport only.
    - metrics_computed=False until full teleport has run and been cached.
    """
    from app.services.summarization_service import is_llm_working
    print(f"[search_author] name='{name}', id='{id}'", flush=True)
    if name.strip().lower() in ["vikas", "user_vikas"]:
        name = "Vikas Vijigiri"
    clean_id = id.split("/")[-1] if id else None
    cache_key = f"id:{clean_id}" if clean_id else name.strip().lower()

    # ── 1. Check in-memory cache ──────────────────────────────────────────────
    cached = await profile_cache.get(cache_key)
    if cached is not None:
        print(f"[search_author] In-memory cache hit: '{cache_key}'", flush=True)
        return cached

    # ── 2. Check Firestore (pre-computed by teleport_researcher) ──────────────
    # from researcher_worker import FIRESTORE_AVAILABLE
    if False:
        try:
            db = firestore.client()
            if clean_id:
                doc = db.collection("global_researchers").document(clean_id).get()
                d = doc.to_dict() if doc.exists else None
            else:
                docs = db.collection("global_researchers").where("display_name", "==", name).limit(1).get()
                d = docs[0].to_dict() if docs else None

            if d:
                print(f"[search_author] Firestore cache hit for: '{clean_id or name}'", flush=True)
                # Works in Firestore come from teleport_researcher which sourced them from OpenAlex
                works_data = []
                for w in d.get("works", []):
                    try:
                        _work_fields = set(getattr(Work, 'model_fields', None) or Work.__fields__)
                        works_data.append(Work(**{k: v for k, v in w.items() if k in _work_fields}))
                    except Exception:
                        pass
                field = d.get("field_of_study") or (d.get("expertise", [""])[0] if d.get("expertise") else "")
                similar = await fetch_similar_authors(field, d.get("openalex_id", ""))
                response_data = AuthorResponse(
                    id=d.get("openalex_id", ""),
                    display_name=d.get("display_name", ""),
                    orcid=d.get("orcid"),
                    h_index=d.get("h_index", 0),
                    i10_index=d.get("i10_index", 0),
                    works_count=d.get("works_count", 0),
                    cited_by_count=d.get("cited_by_count", 0),
                    institution=d.get("current_institution") or "Independent Researcher",
                    field_of_study=d.get("field_of_study", "Multidisciplinary"),
                    expertise=d.get("expertise", []),
                    academic_history=d.get("academic_history", []),
                    works=works_data,
                    innovation_score=d.get("innovation_score"),
                    metrics_computed=is_llm_working() and d.get("metrics_computed", False),
                    llm_active=is_llm_working(),
                    average_creativity=d.get("average_creativity", 0.0),
                    average_complexity=d.get("average_complexity", 0.0),
                    average_skill_score=d.get("average_skill_score", 0.0),
                    average_impact=d.get("average_impact", 0.0),
                    average_activity=d.get("average_activity", 0.0),
                    disruption_score=d.get("disruption_score", 0.0),
                    citation_acceleration=d.get("citation_acceleration", 0.0),
                    future_impact_score=d.get("future_impact_score", 0.0),
                    network_centrality=d.get("network_centrality", 0.0),
                    semantic_novelty=d.get("semantic_novelty", 0.0),
                    interdisciplinary_index=d.get("interdisciplinary_index", 0.0),
                    policy_patent_score=d.get("policy_patent_score", 0.0),
                    open_science_score=d.get("open_science_score", 0.0),
                    collaboration_diversity=d.get("collaboration_diversity", 0.0),
                    research_consistency=d.get("research_consistency", 0.0),
                    next_prediction=d.get("next_prediction"),
                    similar_researchers=similar
                )
                await profile_cache.set(cache_key, response_data)
                return response_data
        except Exception as e:
            print(f"[search_author] Firestore lookup error: {e}", flush=True)

    # ── 3. Fetch directly from OpenAlex — source of truth ────────────────────
    BASE_URL = "https://api.openalex.org"
    headers = _get_openalex_headers()
    try:
        async with httpx.AsyncClient(timeout=httpx.Timeout(20.0, connect=8.0), headers=headers) as client:
            # ── 3a. Resolve author ──────────────────────────────────────────
            author_data = None
            resolved_id = None

            if clean_id:
                res = await client.get(f"{BASE_URL}/authors/{clean_id}",
                                       params={"mailto": "vikki.4me@gmail.com"})
                if res.status_code == 200:
                    author_data = res.json()
                    resolved_id = clean_id
            else:
                res = await client.get(f"{BASE_URL}/authors",
                                       params={"search": name, "per_page": 1,
                                               "mailto": "vikki.4me@gmail.com"})
                if res.status_code == 200:
                    results = res.json().get("results", [])
                    if results:
                        author_data = results[0]
                        resolved_id = author_data["id"].split("/")[-1]

            if not author_data or not resolved_id:
                from fastapi import HTTPException
                raise HTTPException(status_code=404, detail="Author not found on OpenAlex")

            # ── 3b. Fetch recent works (pure OpenAlex, no LLM) ──────────────
            filter_str = f"authorships.author.id:{resolved_id}"
            author_orcid = author_data.get("orcid")
            if author_orcid:
                filter_str = f"authorships.author.orcid:{author_orcid}"

            works_res = await client.get(
                f"{BASE_URL}/works",
                params={
                    "filter": filter_str,
                    "per_page": 50,
                    "sort": "publication_year:desc",
                    "mailto": "vikki.4me@gmail.com"
                }
            )
            raw_works = works_res.json().get("results", []) if works_res.status_code == 200 else []

            # Build Work objects from pure OpenAlex data — titles NEVER from LLM
            works_data = []
            for w in raw_works:
                title = w.get("title") or ""
                if not title.strip():
                    continue  # Skip untitled works
                primary_location = w.get("primary_location") or {}
                source = primary_location.get("source") or {}
                journal_name = source.get("display_name")
                pub_year = w.get("publication_year")
                citations = w.get("cited_by_count", 0)
                impact = source.get("2yr_mean_citedness", 0.0) or 0.0

                # Reconstruct abstract from inverted index (OpenAlex format)
                abstract = ""
                inv_idx = w.get("abstract_inverted_index") or {}
                if inv_idx:
                    try:
                        word_pos = [(pos, word) for word, positions in inv_idx.items() for pos in positions]
                        abstract = " ".join(wp[1] for wp in sorted(word_pos))
                    except Exception:
                        pass

                authors_list = []
                for auth_ship in w.get("authorships", []):
                    author_info = auth_ship.get("author")
                    if author_info:
                        a_name = author_info.get("display_name")
                        a_id = author_info.get("id")
                        if a_name and a_id:
                            authors_list.append(f"{a_name}|{a_id}")

                works_data.append(Work(
                    id=w.get("id"),
                    title=title,
                    year=pub_year,
                    doi=w.get("doi"),
                    journal=journal_name,
                    is_open_access=bool((w.get("open_access") or {}).get("is_oa")),
                    citations=citations,
                    creativity_score=0.0,   # LLM-computed — pending background task
                    complexity_score=0.0,
                    impact_factor=round(float(impact), 2),
                    disruption_score=0.0,
                    semantic_novelty=0.0,
                    open_science_score=0.0,
                    authors=authors_list
                ))

            # ── 3c. Parse OpenAlex author metadata ──────────────────────────
            last_insts = author_data.get("last_known_institutions") or []
            institution = "Independent Researcher"
            if last_insts and isinstance(last_insts, list):
                first = last_insts[0]
                if first and isinstance(first, dict):
                    institution = first.get("display_name") or "Independent Researcher"

            stats = author_data.get("summary_stats") or {}
            concepts = author_data.get("x_concepts") or []
            field = next((c.get("display_name") for c in concepts if c.get("level") == 1), None) \
                    or (concepts[0].get("display_name") if concepts else "Multidisciplinary")
            expertise = [c.get("display_name") for c in concepts
                         if c.get("level") in [1, 2] and c.get("display_name")][:6]

            # Build academic history from affiliations
            affiliations = author_data.get("affiliations") or []
            hist_map: dict = {}
            for aff in affiliations:
                inst = (aff.get("institution") or {})
                inst_name = inst.get("display_name")
                years = aff.get("years") or []
                if not inst_name or not years:
                    continue
                existing = hist_map.get(inst_name)
                if existing is None:
                    hist_map[inst_name] = [min(years), max(years)]
                else:
                    hist_map[inst_name] = [min(existing[0], min(years)), max(existing[1], max(years))]
            academic_history = [
                f"{n} ({y[0]}\u2013{y[1]})" if y[0] != y[1] else f"{n} ({y[0]})"
                for n, y in sorted(hist_map.items(), key=lambda x: x[1][0])
            ]

            similar = await fetch_similar_authors(field, author_data.get("id", ""))

            # ── 3d. Return pure OpenAlex profile immediately ─────────────────
            response_data = AuthorResponse(
                id=author_data.get("id", resolved_id),
                display_name=author_data.get("display_name", name),
                orcid=author_data.get("orcid"),
                h_index=stats.get("h_index", 0),
                i10_index=stats.get("i10_index", 0),
                works_count=author_data.get("works_count", 0),
                cited_by_count=author_data.get("cited_by_count", 0),
                institution=institution,
                field_of_study=field,
                expertise=expertise,
                academic_history=academic_history,
                works=works_data,
                innovation_score=None,
                metrics_computed=False,  # LLM metrics pending — background task queued
                llm_active=is_llm_working(),
                next_prediction=None,
                similar_researchers=similar
            )

            # Cache this OpenAlex-sourced result
            await profile_cache.set(cache_key, response_data)

            # ── 3e. Queue LLM enrichment in the background (NEVER blocks response)
            if is_llm_working() and teleport_researcher is not None:
                author_full_id = author_data.get("id", resolved_id)
                background_tasks.add_task(teleport_researcher, author_full_id)
                print(f"[search_author] Queued background teleport for: {author_data.get('display_name')}", flush=True)
            else:
                print(f"[search_author] LLM offline or unconfigured, skipping background task.", flush=True)

            return response_data

    except Exception as e:
        print(f"[search_author] OpenAlex fetch error: {e}", flush=True)
        from fastapi import HTTPException
        raise HTTPException(status_code=500, detail=f"Search failed: {str(e)}")

@app.get("/daily_feed")
async def get_daily_feed(author_id: Optional[str] = None, query_fallback: Optional[str] = None):
    # Caching bypassed as per complete cache removal requirement
    from fastapi import HTTPException
    try:
        data = await pipeline_services.get_daily_feed(author_id, query_fallback=query_fallback)
        return data
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@app.get("/match_grants")
async def match_grants(author_id: str = Query(...)):
    # Caching bypassed as per complete cache removal requirement
    from fastapi import HTTPException
    try:
        data = await pipeline_services.match_grants(author_id)
        return data
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@app.get("/collaborator_synergy")
async def get_collaborator_synergy(author_id: str = Query(...), collaborator_id: str = Query(...)):
    # Caching bypassed
    from fastapi import HTTPException
    try:
        data = await pipeline_services.get_collaborator_synergy(author_id, collaborator_id)
        return data
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@app.get("/citation_heatmap")
async def get_citation_heatmap(author_id: str = Query(...)):
    # Caching bypassed
    from fastapi import HTTPException
    try:
        data = await pipeline_services.get_citation_heatmap(author_id)
        return data
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@app.get("/journal_advisor")
async def get_journal_advisor(author_id: str = Query(...)):
    # Caching bypassed
    from fastapi import HTTPException
    try:
        data = await pipeline_services.get_journal_advisor(author_id)
        return data
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@app.get("/network_collaborators")
async def get_network_collaborators(author_id: str = Query(...), limit: int = Query(10), offset: int = Query(0), exclude_ids: str = Query(""), field: str = Query("")):
    from fastapi import HTTPException
    
    cache_key = f"{author_id}_{limit}_{offset}_{exclude_ids}_{field}"
    cached_data = await network_collaborators_cache.get(cache_key)
    if cached_data is not None:
        return cached_data
        
    excl_list = [x.strip() for x in exclude_ids.split(",")] if exclude_ids else []
    try:
        data = await pipeline_services.get_network_collaborators(author_id, limit, offset, excl_list, field)
        if data:
            await network_collaborators_cache.set(cache_key, data)
        return data
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@app.post("/chat_with_author")
async def chat_with_author(req: ChatRequest):
    hist_dict = [{"role": h.role, "content": h.content} for h in req.history]
    data = await pipeline_services.chat_with_author(
        author_id=req.author_id,
        paper_title=req.paper_title,
        user_message=req.user_message,
        history=hist_dict
    )
    return data

@app.post("/agent/upload_document")
async def upload_document(file: UploadFile = File(...)):
    try:
        content = await file.read()
        extracted_text = ""
        filename = file.filename or "unknown"
        
        if file.content_type == "application/pdf" or filename.endswith(".pdf"):
            with pdfplumber.open(io.BytesIO(content)) as pdf:
                for page in pdf.pages:
                    text = page.extract_text()
                    if text:
                        extracted_text += text + "\n"
        else:
            # Assume text or markdown
            extracted_text = content.decode("utf-8", errors="replace")
            
        return {"filename": filename, "extracted_text": extracted_text.strip()}
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

class AgentChatRequest(BaseModel):
    message: str
    history: list[dict[str, str]] = []
    mode: str = "RESEARCH"
    user_memory: Optional[dict] = None  # Injected by Android from UserMemoryProfile

@app.post("/agent/chat")
async def agent_chat(req: AgentChatRequest):
    try:
        base_prompt = f"You are Ask Skolar, an expert AI {req.mode} assistant for a senior researcher. Be concise, sharp, and proactive. You have access to tools that can fetch data from arXiv, papers, the researcher's network, and search the live web. Use web search whenever you need dynamic, recent, or real-time information outside academic databases."

        # Inject user memory block if present
        memory_block = ""
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

        system_prompt = base_prompt + memory_block
        messages = [{"role": "system", "content": system_prompt}]
        for h in req.history[-10:]:
            if h.get("role") in ["user", "assistant", "tool"]:
                messages.append({"role": h["role"], "content": h.get("content", "")})
        messages.append({"role": "user", "content": req.message})
        
        from app.services.pipeline_services import is_llm_working
        from app.services.connectors import TOOLS_SCHEMA, execute_tool_call
        import json
        
        # Candidate models in sequence for instant failover (active Groq models only)
        models = [
            "llama-3.3-70b-versatile",
            "llama-3.1-8b-instant",
            "llama-3.2-11b-vision-preview",
            "llama-3.2-3b-preview",
            "llama-3.2-1b-preview"
        ]

        if pipeline_services.api_key:
            async with httpx.AsyncClient(timeout=30.0) as client:
                chosen_model = None
                response_msg = None
                
                # Loop through candidate models to find one that succeeds for tool call / initial prompt
                for model in models:
                    try:
                        print(f"[AgentChat] Attempting initial tool call with model: {model}", flush=True)
                        res = await client.post(
                            pipeline_services.base_url,
                            headers={"Authorization": f"Bearer {pipeline_services.api_key}", "Content-Type": "application/json"},
                            json={
                                "model": model, 
                                "messages": messages, 
                                "temperature": 0.5, 
                                "max_tokens": 1024,
                                "tools": TOOLS_SCHEMA,
                                "tool_choice": "auto"
                            },
                            timeout=20.0
                        )
                        if res.status_code == 200:
                            response_msg = res.json()['choices'][0]['message']
                            chosen_model = model
                            print(f"[AgentChat] Initial call succeeded with model: {model}", flush=True)
                            break
                        else:
                            print(f"[AgentChat] Model {model} failed with status {res.status_code}: {res.text}. Trying next model...", flush=True)
                    except Exception as e:
                        print(f"[AgentChat] Model {model} raised exception: {e}. Trying next model...", flush=True)

                if chosen_model is not None:
                    tool_calls = response_msg.get("tool_calls")
                    
                    # Groq sometimes returns tools as text instead of tool_calls array
                    if not tool_calls and "content" in response_msg and response_msg["content"] and "<function=" in response_msg["content"]:
                        import re
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
                    
                    if tool_calls:
                        messages.append(response_msg) # Assistant's tool_call message
                        for tc in tool_calls:
                            func_name = tc["function"]["name"]
                            args = json.loads(tc["function"]["arguments"])
                            tool_result = await execute_tool_call(func_name, args)
                            messages.append({
                                "role": "tool",
                                "tool_call_id": tc["id"],
                                "name": func_name,
                                "content": str(tool_result)
                            })
                        
                        # Second call after tools: prioritise the chosen model, but fall back if needed
                        second_call_models = [chosen_model] + [m for m in models if m != chosen_model]
                        reply = None
                        for model in second_call_models:
                            try:
                                print(f"[AgentChat] Attempting second call with model: {model}", flush=True)
                                res2 = await client.post(
                                    pipeline_services.base_url,
                                    headers={"Authorization": f"Bearer {pipeline_services.api_key}", "Content-Type": "application/json"},
                                    json={
                                        "model": model, 
                                        "messages": messages, 
                                        "temperature": 0.5, 
                                        "max_tokens": 1024
                                    },
                                    timeout=20.0
                                )
                                if res2.status_code == 200:
                                    reply = res2.json()['choices'][0]['message']['content'].strip()
                                    print(f"[AgentChat] Second call succeeded with model: {model}", flush=True)
                                    break
                                else:
                                    print(f"[AgentChat] Second call model {model} failed with status {res2.status_code}: {res2.text}. Trying next model...", flush=True)
                            except Exception as e:
                                print(f"[AgentChat] Second call model {model} raised exception: {e}. Trying next model...", flush=True)
                        
                        if reply is not None:
                            # Clean up any stray XML function tags from the reply
                            import re
                            reply = re.sub(r'<function=.*?>.*?</function>', '', reply, flags=re.DOTALL)
                            reply = re.sub(r'<function=.*?>', '', reply)
                            reply = re.sub(r'</function>', '', reply)
                            reply = reply.strip()
                            return {"reply": reply}
                        else:
                            return {"reply": "Agent failed to generate a response after executing tools. All fallback models exhausted."}
                    else:
                        reply = response_msg.get('content', '').strip()
                        # Clean up any stray XML function tags from the reply
                        import re
                        reply = re.sub(r'<function=.*?>.*?</function>', '', reply, flags=re.DOTALL)
                        reply = re.sub(r'<function=.*?>', '', reply)
                        reply = re.sub(r'</function>', '', reply)
                        reply = reply.strip()
                        return {"reply": reply}
        return {"reply": "I am currently offline or rate-limited. Your query has been noted."}
    except Exception as e:
        print(f"Agent chat failed: {e}")
        return {"reply": "An error occurred while processing your query. Please try again."}


@app.get("/author_metrics")
async def get_author_metrics(author_id: str = Query(...)):
    from app.services.metrics_service import compute_author_metrics
    data = await compute_author_metrics(author_id)
    return data


@app.get("/industry_opportunities")
async def get_industry_opportunities(focus: str = Query("AI")):
    from app.services.industry_service import fetch_industry_opportunities
    opportunities = await fetch_industry_opportunities(focus)
    return opportunities


# ── Semantic Trending Engine ──────────────────────────────────────────────────
# Cache: per-author, 4-hour TTL (OpenAlex calls are expensive)
_semantic_trending_cache = SimpleAsyncCache(ttl_seconds=14400, max_size=200)

@app.get("/semantic_trending")
async def get_semantic_trending(
    author_id: str = Query(..., description="Full OpenAlex author URI or short ID"),
    limit: int = Query(10, ge=1, le=30)
):
    """
    Returns papers trending specifically in the author's research field.

    Algorithm:
      1. Fetch author record from OpenAlex → extract top concept/topic IDs & display names.
      2. For each top concept (up to 4), query OpenAlex works from the last 2 years
         filtered by that concept, sorted by cited_by_count:desc.
      3. Merge & deduplicate all results.
      4. Score each paper:
            velocity   = cited_by_count / max(1, months_since_publication)
            overlap    = count of author concepts that match paper concepts
            oa_bonus   = 0.1 if open access
            final      = velocity * 0.5 + overlap * 3.0 + oa_bonus
      5. Sort descending, return top `limit` papers.
      6. Cache per-author for 4 hours in both memory and Postgres.
    """
    import calendar

    cache_key = f"semantic_trending_{author_id}_{limit}"
    cached = await _semantic_trending_cache.get(cache_key)
    if cached is not None:
        print(f"[SemanticTrending] Cache hit for author={author_id}", flush=True)
        return cached

    # ── also check Postgres long-term cache ──────────────────────────────────
    try:
        pg_cached = await pipeline_services._load_from_postgres("semantic_trending", cache_key)
        if pg_cached is not None:
            await _semantic_trending_cache.set(cache_key, pg_cached)
            print(f"[SemanticTrending] Postgres cache hit for author={author_id}", flush=True)
            return pg_cached
    except Exception as e:
        print(f"[SemanticTrending] Postgres cache read error: {e}", flush=True)

    headers = _get_openalex_headers()
    now = __import__("datetime").datetime.utcnow()
    now_year = now.year
    now_month = now.month

    async with httpx.AsyncClient(timeout=20.0) as client:
        # ── Step 1: Fetch author record ───────────────────────────────────────
        clean_id = author_id.split("/")[-1]
        author_resp = await client.get(
            f"https://api.openalex.org/authors/{clean_id}",
            headers=headers
        )
        if author_resp.status_code != 200:
            raise HTTPException(status_code=502, detail=f"OpenAlex author fetch failed: {author_resp.status_code}")

        author_data = author_resp.json()

        # Extract top concepts/topics (prefer x_concepts, fall back to topics)
        raw_concepts = author_data.get("x_concepts") or author_data.get("topics") or []
        # Sort by score descending, take top 5
        top_concepts = sorted(raw_concepts, key=lambda c: c.get("score", 0), reverse=True)[:5]
        author_concept_ids = {c.get("id", "").split("/")[-1] for c in top_concepts}
        author_concept_names = [c.get("display_name", "") for c in top_concepts if c.get("display_name")]

        if not top_concepts:
            raise HTTPException(status_code=404, detail="Author has no resolvable research concepts in OpenAlex.")

        print(f"[SemanticTrending] Author concepts: {author_concept_names}", flush=True)

        # ── Step 2: Fetch works per concept ──────────────────────────────────
        prev_year = now_year - 1
        all_works: list[dict] = []
        seen_ids: set[str] = set()

        for concept in top_concepts[:4]:
            concept_id = concept.get("id", "").split("/")[-1]
            if not concept_id:
                continue
            try:
                works_resp = await client.get(
                    "https://api.openalex.org/works",
                    params={
                        "filter": f"concepts.id:{concept_id},publication_year:{prev_year}|{now_year}",
                        "sort": "cited_by_count:desc",
                        "per_page": 15,
                        "mailto": "vikki.4me@gmail.com",
                    },
                    headers=headers
                )
                if works_resp.status_code == 200:
                    works_data = works_resp.json().get("results", [])
                    for w in works_data:
                        wid = w.get("id", "")
                        if wid and wid not in seen_ids:
                            seen_ids.add(wid)
                            # tag which concept sourced this paper (for overlap scoring)
                            w["_source_concept_id"] = concept_id
                            all_works.append(w)
            except Exception as e:
                print(f"[SemanticTrending] Concept {concept_id} fetch error: {e}", flush=True)
                continue

        if not all_works:
            raise HTTPException(status_code=404, detail="No relevant trending papers found for this author's research area.")

        # ── Step 3: Score each paper ──────────────────────────────────────────
        def score_paper(w: dict) -> float:
            pub_year = w.get("publication_year") or now_year
            # Estimate month (OpenAlex often has publication_date YYYY-MM-DD)
            pub_date = w.get("publication_date") or f"{pub_year}-01-01"
            try:
                parts = pub_date.split("-")
                pub_month = int(parts[1]) if len(parts) > 1 else 1
                pub_year_int = int(parts[0])
            except Exception:
                pub_month = 1
                pub_year_int = pub_year

            months_alive = max(1, (now_year - pub_year_int) * 12 + (now_month - pub_month))
            cited = w.get("cited_by_count") or 0
            velocity = cited / months_alive  # citations per month

            # Concept overlap: how many author concepts appear in paper's concepts
            paper_concepts = w.get("concepts") or w.get("topics") or []
            paper_concept_ids = {c.get("id", "").split("/")[-1] for c in paper_concepts}
            overlap = len(paper_concept_ids & author_concept_ids)

            oa_bonus = 0.1 if w.get("open_access", {}).get("is_oa") else 0.0

            return velocity * 0.5 + overlap * 3.0 + oa_bonus

        scored = sorted(all_works, key=score_paper, reverse=True)
        top_papers = scored[:limit]

        # ── Step 4: Build response ────────────────────────────────────────────
        result_items = []
        for w in top_papers:
            pub_year = w.get("publication_year") or 0
            pub_date = w.get("publication_date") or f"{pub_year}-01-01"
            try:
                parts = pub_date.split("-")
                pub_year_int = int(parts[0])
                pub_month_int = int(parts[1]) if len(parts) > 1 else 1
            except Exception:
                pub_year_int = pub_year
                pub_month_int = 1

            months_alive = max(1, (now_year - pub_year_int) * 12 + (now_month - pub_month_int))
            cited = w.get("cited_by_count") or 0
            velocity = round(cited / months_alive, 2)

            # Concept tags: paper concepts that match author concepts
            paper_concepts = w.get("concepts") or w.get("topics") or []
            matched_tags = [
                c.get("display_name", "")
                for c in paper_concepts
                if c.get("id", "").split("/")[-1] in author_concept_ids and c.get("display_name")
            ][:3]
            if not matched_tags and author_concept_names:
                matched_tags = [author_concept_names[0]]

            loc = w.get("primary_location") or {}
            source = loc.get("source") or {}
            result_items.append({
                "id": w.get("id", ""),
                "title": w.get("title") or "Untitled",
                "journal": source.get("display_name") or "Unknown Journal",
                "year": pub_year_int,
                "cited_by_count": cited,
                "velocity_score": velocity,
                "is_open_access": (w.get("open_access") or {}).get("is_oa", False),
                "concept_tags": matched_tags,
                "doi": w.get("doi") or "",
            })

        response = {
            "author_concepts": author_concept_names[:5],
            "papers": result_items,
        }

        # ── Step 5: Cache ─────────────────────────────────────────────────────
        await _semantic_trending_cache.set(cache_key, response)
        try:
            await pipeline_services._save_to_postgres("semantic_trending", cache_key, response)
        except Exception as e:
            print(f"[SemanticTrending] Postgres cache write error: {e}", flush=True)

        print(f"[SemanticTrending] Returning {len(result_items)} personalized papers for author={author_id}", flush=True)
        return response


# ── User Memory & Activity Engine ────────────────────────────────────────────

class ActivityEventPayload(BaseModel):
    type: str
    timestamp: Optional[int] = None
    hourOfDay: Optional[int] = None
    paperTitle: Optional[str] = None
    paperDomain: Optional[str] = None
    paperJournal: Optional[str] = None
    durationSeconds: Optional[int] = None
    authorName: Optional[str] = None
    authorInstitution: Optional[str] = None
    query: Optional[str] = None
    filterValue: Optional[str] = None
    collaboratorName: Optional[str] = None
    collaboratorField: Optional[str] = None

class UserMemoryEventsRequest(BaseModel):
    user_id: str
    events: List[ActivityEventPayload]

_user_memory_cache = SimpleAsyncCache(ttl_seconds=3600, max_size=500)

@app.post("/user_memory/events")
async def post_user_memory_events(req: UserMemoryEventsRequest):
    """
    Accepts a batch of activity events from the Android app.
    Derives a memory profile and stores it in Postgres.
    """
    import json as _json
    from datetime import datetime
    from collections import Counter

    events = req.events
    user_id = req.user_id

    if not events:
        return {"status": "no_events"}

    # Load existing profile from Postgres
    existing_raw = await pipeline_services._load_from_postgres("user_memory", user_id)
    existing = existing_raw or {}

    # ── Derive topics ────────────────────────────────────────────────────────
    topic_sources = []
    for e in events:
        if e.paperDomain: topic_sources.append(e.paperDomain.strip().title())
        if e.query and len(e.query) > 3: topic_sources.append(e.query.strip().title())
        if e.filterValue and e.filterValue not in ("Global", "All"): topic_sources.append(e.filterValue.strip().title())
    topic_freq = Counter(topic_sources)
    top_topics = [t for t, _ in topic_freq.most_common(6)]

    # ── Active hours ─────────────────────────────────────────────────────────
    hours = [e.hourOfDay for e in events if e.hourOfDay is not None]
    hour_freq = Counter(hours)
    active_hours = [h for h, _ in hour_freq.most_common(4)]

    # ── Reading pace ─────────────────────────────────────────────────────────
    paper_sessions = [(e.paperTitle, e.durationSeconds) for e in events
                      if e.type == "PAPER_CLOSED" and e.paperTitle and e.durationSeconds]
    avg_read_sec = (sum(s for _, s in paper_sessions) / len(paper_sessions)) if paper_sessions else 0
    avg_read_min = avg_read_sec / 60.0
    reading_pace = (
        "deep_reader" if avg_read_min >= 5 else
        "moderate_reader" if avg_read_min >= 2 else
        "quick_scanner" if avg_read_min > 0 else
        existing.get("reading_pace", "unknown")
    )

    # ── Unfinished / recent ───────────────────────────────────────────────────
    unfinished = [t for t, s in paper_sessions if 0 < s < 90]
    recently_read = [t for t, s in paper_sessions if s >= 90]

    # ── Research style ───────────────────────────────────────────────────────
    domains = list({e.paperDomain for e in events if e.paperDomain})
    research_style = (
        "interdisciplinary" if len(domains) >= 4 else
        "focused" if len(domains) >= 2 else "exploratory"
    )

    # ── Collaborators ─────────────────────────────────────────────────────────
    collab_names = [e.collaboratorName or e.authorName for e in events
                    if e.type in ("COLLAB_CONNECTED", "AUTHOR_VISITED") and (e.collaboratorName or e.authorName)]
    collaborators = [n for n, _ in Counter(collab_names).most_common(5)]

    # ── Searches ──────────────────────────────────────────────────────────────
    searches = [e.query for e in events
                if e.type in ("SEARCH_QUERY", "AGENT_QUERY") and e.query]
    freq_searches = [q for q, _ in Counter(searches).most_common(5)]

    profile = {
        "user_id": user_id,
        "top_topics": top_topics or existing.get("top_topics", []),
        "active_hours": active_hours or existing.get("active_hours", []),
        "reading_pace": reading_pace,
        "research_style": research_style,
        "avg_read_minutes": round(avg_read_min, 2),
        "unfinished_papers": list(dict.fromkeys(unfinished))[:3],
        "recently_read_papers": list(dict.fromkeys(recently_read))[:5],
        "frequent_collaborators": collaborators or existing.get("frequent_collaborators", []),
        "frequent_search_terms": freq_searches or existing.get("frequent_search_terms", []),
        "last_active_topic": top_topics[0] if top_topics else existing.get("last_active_topic", ""),
        "total_papers_read": len([e for e in events if e.type == "PAPER_CLOSED" and (e.durationSeconds or 0) > 30]),
        "last_updated": int(datetime.utcnow().timestamp() * 1000),
    }

    await pipeline_services._save_to_postgres("user_memory", user_id, profile)
    await _user_memory_cache.set(user_id, profile)

    print(f"[UserMemory] Saved profile for user={user_id}: topics={top_topics[:3]}", flush=True)
    return {"status": "ok", "profile": profile}


@app.get("/user_memory/{user_id}")
async def get_user_memory(user_id: str):
    """
    Returns the current memory profile for a user.
    Used by the Android app on Ask Skolar init.
    """
    cached = await _user_memory_cache.get(user_id)
    if cached:
        return cached

    pg = await pipeline_services._load_from_postgres("user_memory", user_id)
    if pg:
        await _user_memory_cache.set(user_id, pg)
        return pg

    raise HTTPException(status_code=404, detail="No memory profile found for this user yet.")
