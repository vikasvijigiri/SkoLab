from dotenv import load_dotenv

# MUST happen BEFORE any import that auto-initialises Firebase or reads env vars
load_dotenv()

import random
import httpx
import os
import asyncio
from fastapi import FastAPI, Query, BackgroundTasks
from fastapi.middleware.cors import CORSMiddleware
from typing import List, Optional, Union
from pydantic import BaseModel
from firebase_admin import firestore
from app.config import settings          # ← single source of truth
from app.services.prediction_service import PredictionService
from app.services.summarization_service import SummarizationService
from researcher_worker import teleport_researcher
from zeroconf import ServiceInfo
from zeroconf.asyncio import AsyncZeroconf
import socket
import time

class SimpleAsyncCache:
    def __init__(self, ttl_seconds: float, max_size: int = 200):
        self.ttl = ttl_seconds
        self.max_size = max_size
        self.cache = {}  # key -> (value, expiry_timestamp)
        self.lock = asyncio.Lock()

    async def get(self, key: str):
        async with self.lock:
            exists = key in self.cache
            print(f"[SimpleAsyncCache] get: '{key}', exists: {exists}", flush=True)
            if not exists:
                return None
            val, expiry = self.cache[key]
            if time.time() > expiry:
                print(f"[SimpleAsyncCache] get: '{key}' expired", flush=True)
                del self.cache[key]
                return None
            return val

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

app = FastAPI(
    title="ResQit API",
    description="The backend API for the ResQit platform",
    version="1.0.0",
)

# ── mDNS service advertisement ───────────────────────────────────────────────
# The Android app uses NSD (Network Service Discovery) to find this server
# automatically — no IP address is ever hardcoded on either side.
_zeroconf: AsyncZeroconf | None = None
_mdns_info: ServiceInfo | None = None


@app.on_event("startup")
async def verify_firestore() -> None:
    """Check if Firestore connection is responsive. If not, bypass to fallback."""
    from researcher_worker import check_connection_sync, set_firestore_available
    import concurrent.futures
    
    print("[Firestore] Verifying Firestore connection on startup...", flush=True)
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
        _mdns_info = ServiceInfo(
            type_=settings.mdns_service_type,
            name=settings.mdns_fqdn,
            addresses=[socket.inet_aton(settings.lan_ip)],
            port=settings.port,
            properties={"path": "/", "version": "1"},
        )
        _zeroconf = AsyncZeroconf()
        await _zeroconf.async_register_service(_mdns_info)
        print(
            f"[mDNS] '{settings.mdns_service_name}' registered at "
            f"{settings.lan_ip}:{settings.port}"
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

# Configure CORS
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

class Work(BaseModel):
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

class AuthorSuggestion(BaseModel):
    id: str
    display_name: str
    institution: str
    field_of_study: Optional[str] = None

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
    groq_key = os.getenv("GROQ_API")
    has_key = groq_key is not None and len(groq_key) > 10
    return {
        "groq_api_configured": has_key,
        "model": "llama-3.3-70b-versatile",
        "key_prefix": groq_key[:7] if has_key else "None"
    }

@app.get("/")
def read_root():
    return {"message": "Welcome to the Entroπ API!"}

@app.get("/author_suggestions", response_model=List[AuthorSuggestion])
async def get_author_suggestions(query: str = Query(...)):
    cache_key = query.strip().lower()
    cached = await suggestions_cache.get(cache_key)
    if cached is not None:
        print(f"[Cache Hit] Suggestions for: '{query}'", flush=True)
        return cached

    # 1. Search Firestore for suggestions first (Fast)
    from researcher_worker import FIRESTORE_AVAILABLE
    if FIRESTORE_AVAILABLE:
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
                suggestions = [
                    AuthorSuggestion(
                        id=d.get("openalex_id"),
                        display_name=d.get("display_name"),
                        institution=d.get("current_institution"),
                        field_of_study=d.get("field_of_study")
                    ) for d in [doc.to_dict() for doc in docs]
                ]
                await suggestions_cache.set(cache_key, suggestions)
                return suggestions
        except Exception as e:
            print(f"Firestore Suggester Error: {e}")

    # 2. Fallback to OpenAlex
    BASE_URL = "https://api.openalex.org"
    timeout = httpx.Timeout(15.0, connect=5.0)
    headers = {
        "User-Agent": "ResQitApp/1.0 (mailto:vikki.4me@gmail.com)",
        "Accept": "application/json"
    }
    async with httpx.AsyncClient(timeout=timeout) as client:
        params = {"search": query, "per_page": 10, "mailto": "vikki.4me@gmail.com"}
        try:
            res = await client.get(f"{BASE_URL}/authors", params=params, headers=headers)
            if res.status_code == 200:
                results = res.json().get("results", [])
                suggestions = []
                for author in results:
                    last_insts = author.get("last_known_institutions")
                    inst = "Independent Researcher"
                    if last_insts and isinstance(last_insts, list) and len(last_insts) > 0:
                        first_inst = last_insts[0]
                        if first_inst and isinstance(first_inst, dict):
                            inst = first_inst.get("display_name") or "Independent Researcher"
                    suggestions.append(AuthorSuggestion(
                        id=author["id"],
                        display_name=author["display_name"],
                        institution=inst
                    ))
                await suggestions_cache.set(cache_key, suggestions)
                return suggestions
        except: pass
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
        headers = {
            "User-Agent": "ResQitApp/1.0 (mailto:vikki.4me@gmail.com)",
            "Accept": "application/json"
        }
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
                    background_tasks.add_task(teleport_researcher, author_id)
                    return {"status": "Refresh started", "author_id": author_id}
        return {"error": "Author not found for refresh"}
    except Exception as e:
        return {"error": str(e)}

async def fetch_similar_authors(query_term: str, exclude_id: str) -> List[AuthorSuggestion]:
    if not query_term or query_term == "Multidisciplinary":
        query_term = "artificial intelligence"
        
    BASE_URL = "https://api.openalex.org"
    timeout = httpx.Timeout(10.0, connect=3.0)
    headers = {
        "User-Agent": "ResQitApp/1.0 (mailto:vikki.4me@gmail.com)",
        "Accept": "application/json"
    }
    
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
                    
                    suggestions.append(AuthorSuggestion(
                        id=author_id,
                        display_name=author.get("display_name", "Unknown"),
                        institution=inst,
                        field_of_study=field
                    ))
                    if len(suggestions) >= 5:
                        break
                return suggestions
        except Exception as e:
            print(f"Error fetching similar authors: {e}", flush=True)
    return []

@app.get("/search_author", response_model=Union[AuthorResponse, dict])
async def search_author(name: str = Query(...), id: Optional[str] = Query(None), background_tasks: BackgroundTasks = BackgroundTasks()):
    print(f"### [DEBUG] search_author called with name='{name}', id='{id}'", flush=True)
    clean_id = id.split("/")[-1] if id else None
    print(f"### [DEBUG] clean_id={clean_id}", flush=True)
    cache_key = f"id:{clean_id}" if clean_id else name.strip().lower()
    print(f"### [DEBUG] cache_key={cache_key}", flush=True)
    cached = await profile_cache.get(cache_key)
    if cached is not None:
        print(f"[Cache Hit] Profile for: '{cache_key}'", flush=True)
        return cached

    # 1. Check Firestore first for indexed data
    from researcher_worker import FIRESTORE_AVAILABLE
    if FIRESTORE_AVAILABLE:
        try:
            db = firestore.client()
            if clean_id:
                doc = db.collection("global_researchers").document(clean_id).get()
                docs = [doc] if doc.exists else []
            else:
                docs = db.collection("global_researchers").where("display_name", "==", name).limit(1).get()
            
            if docs:
                d = docs[0].to_dict()
                # Retrieve stored works correctly
                works_data = [Work(**w) for w in d.get("works", [])]
                
                # Fetch similar researchers
                field = d.get("field_of_study") or (d.get("expertise")[0] if d.get("expertise") else "")
                similar = await fetch_similar_authors(field, d.get("openalex_id", ""))
                
                response_data = AuthorResponse(
                    id=d.get("openalex_id", ""),
                    display_name=d.get("display_name", ""),
                    orcid=d.get("orcid"),
                    h_index=d.get("h_index", 0),
                    i10_index=d.get("i10_index", 0),
                    works_count=d.get("works_count", 0),
                    cited_by_count=d.get("cited_by_count", 0),
                    institution=d.get("current_institution", "Independent"),
                    field_of_study=d.get("field_of_study", "Multidisciplinary"),
                    expertise=d.get("expertise", []),
                    academic_history=d.get("academic_history", []),
                    works=works_data,
                    innovation_score=d.get("innovation_score"),
                    average_creativity=d.get("average_creativity", 0.0),
                    average_complexity=d.get("average_complexity", 0.0),
                    average_skill_score=d.get("average_skill_score", 0.0),
                    average_impact=d.get("average_impact", 0.0),
                    average_activity=d.get("average_activity", 0.0),
                    # New metrics
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
                    next_prediction=d.get("next_prediction", "No prediction available."),
                    similar_researchers=similar
                )
                await profile_cache.set(cache_key, response_data)
                return response_data
        except Exception as e:
            print(f"Firestore Search Error: {e}")

    # 2. If not in Firestore, fetch from OpenAlex
    BASE_URL = "https://api.openalex.org"
    timeout = httpx.Timeout(20.0, connect=10.0)
    headers = {
        "User-Agent": "ResQitApp/1.0 (mailto:vikki.4me@gmail.com)",
        "Accept": "application/json"
    }
    
    try:
        async with httpx.AsyncClient(timeout=timeout) as client:
            author_id = None
            author_data = None
            if clean_id:
                author_id = clean_id
                try:
                    res = await client.get(f"{BASE_URL}/authors/{clean_id}", headers=headers)
                    if res.status_code == 200:
                        author_data = res.json()
                except Exception as e:
                    print(f"Failed to fetch basic author data for {clean_id}: {e}")
            else:
                author_params = {"search": name, "per_page": 1, "mailto": "vikki.4me@gmail.com"}
                try:
                    res = await client.get(f"{BASE_URL}/authors", params=author_params, headers=headers)
                    if res.status_code != 200: return {"error": "API Error"}
                    
                    results = res.json().get("results", [])
                    if not results: return {"error": "Author not found"}
                    
                    author_data = results[0]
                    author_id = author_data["id"]
                except Exception as e:
                    print(f"Failed to find author by name search: {e}")
                    return {"error": f"Search failed: {e}"}
            
            if author_id and author_data:
                # Teleport immediately to get full publications and metrics
                try:
                    profile = await teleport_researcher(author_id)
                    if profile:
                        works_data = [Work(**w) for w in profile.get("works", [])]
                        field = profile.get("field_of_study") or (profile.get("expertise")[0] if profile.get("expertise") else "")
                        similar = await fetch_similar_authors(field, profile.get("openalex_id", author_id))
                        
                        response_data = AuthorResponse(
                            id=profile.get("openalex_id", author_id),
                            display_name=profile.get("display_name", ""),
                            orcid=profile.get("orcid"),
                            h_index=profile.get("h_index", 0),
                            i10_index=profile.get("i10_index", 0),
                            works_count=profile.get("works_count", 0),
                            cited_by_count=profile.get("cited_by_count", 0),
                            institution=profile.get("current_institution", "Independent"),
                            field_of_study=profile.get("field_of_study", "Multidisciplinary"),
                            expertise=profile.get("expertise", []),
                            academic_history=profile.get("academic_history", []),
                            works=works_data,
                            innovation_score=profile.get("innovation_score"),
                            average_creativity=profile.get("average_creativity", 0.0),
                            average_complexity=profile.get("average_complexity", 0.0),
                            average_skill_score=profile.get("average_skill_score", 0.0),
                            average_impact=profile.get("average_impact", 0.0),
                            average_activity=profile.get("average_activity", 0.0),
                            disruption_score=profile.get("disruption_score", 0.0),
                            citation_acceleration=profile.get("citation_acceleration", 0.0),
                            future_impact_score=profile.get("future_impact_score", 0.0),
                            network_centrality=profile.get("network_centrality", 0.0),
                            semantic_novelty=profile.get("semantic_novelty", 0.0),
                            interdisciplinary_index=profile.get("interdisciplinary_index", 0.0),
                            policy_patent_score=profile.get("policy_patent_score", 0.0),
                            open_science_score=open_science_score_val if 'open_science_score_val' in locals() else profile.get("open_science_score", 0.0),
                            collaboration_diversity=profile.get("collaboration_diversity", 0.0),
                            research_consistency=profile.get("research_consistency", 0.0),
                            next_prediction=profile.get("next_prediction", "No prediction available."),
                            similar_researchers=similar
                        )
                        await profile_cache.set(cache_key, response_data)
                        return response_data
                except Exception as e:
                    print(f"Inline teleportation failed: {e}")
     
                # Fallback to basic info if teleportation fails
                last_insts = author_data.get("last_known_institutions")
                inst = "Independent Researcher"
                if last_insts and isinstance(last_insts, list) and len(last_insts) > 0:
                    first_inst = last_insts[0]
                    if first_inst and isinstance(first_inst, dict):
                        inst = first_inst.get("display_name") or "Independent Researcher"
                stats = author_data.get("summary_stats", {})
                concepts = author_data.get("x_concepts", [])
                field = concepts[0].get("display_name", "Multidisciplinary") if concepts else "Multidisciplinary"
                expertise = [c.get("display_name") for c in concepts if c.get("level") in [1, 2]][:5]
                if not expertise: expertise = [c.get("display_name") for c in concepts[:3]]
                
                similar = await fetch_similar_authors(field, author_id)
                response_data = AuthorResponse(
                    id=author_id,
                    display_name=author_data["display_name"],
                    orcid=author_data.get("orcid"),
                    h_index=stats.get("h_index", 0),
                    i10_index=stats.get("i10_index", 0),
                    works_count=author_data.get("works_count", 0),
                    cited_by_count=author_data.get("cited_by_count", 0),
                    institution=inst,
                    field_of_study=field,
                    expertise=expertise,
                    academic_history=[],
                    works=[],
                    next_prediction="Indexing innovation score...",
                    similar_researchers=similar
                )
                await profile_cache.set(cache_key, response_data)
                return response_data
            else:
                return {"error": "Author not found"}
    except Exception as e:
        return {"error": f"Search failed: {str(e)}"}
