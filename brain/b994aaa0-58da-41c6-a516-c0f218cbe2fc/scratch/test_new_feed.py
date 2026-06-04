import asyncio
import os
import sys
from pathlib import Path
from dotenv import load_dotenv

backend_root = r"c:\Users\VikasVijigiri\Documents\QyRus\backend"
sys.path.insert(0, backend_root)
load_dotenv(os.path.join(backend_root, ".env"))

os.environ["DATABASE_URL"] = "postgresql+asyncpg://postgres:ResQit2025!@127.0.0.1:5432/skolab"
os.environ["GOOGLE_APPLICATION_CREDENTIALS"] = os.path.join(backend_root, "service-account.json")

from app.services.pipeline_services import PipelineServices, is_prestigious_journal
from app.services.openalex_service import is_work_relevant_to_discipline

async def main():
    services = PipelineServices()
    query_fallback = "quantum_cryptography"
    
    # Let's run the candidate gathering logic step-by-step
    concepts = []
    search_queries = [query_fallback]
    discipline = query_fallback
    
    candidates = []
    seen_titles = set()
    
    def add_candidate_if_valid(w) -> None:
        title = w.get("title", "")
        if not title:
            return
        title_norm = title.strip().lower().rstrip(".")
        abstract_index = w.get("abstract_inverted_index")
        custom_abstract = w.get("_custom_abstract")
        
        has_abs = bool(abstract_index or custom_abstract)
        
        # Filter out future years
        import datetime
        current_year = datetime.datetime.now().year
        pub_year = w.get("publication_year")
        if pub_year and pub_year > current_year + 1:
            return
            
        is_dup = title_norm in seen_titles or w.get("id") in [p.get("id") for p in candidates]
        
        if has_abs and not is_dup:
            candidates.append(w)
            seen_titles.add(title_norm)

    # Fetch from OpenAlex
    for q in search_queries:
        print(f"Querying OpenAlex for '{q}'...")
        res = await services.openalex_service.search_works(q, per_page=15, sort="publication_date:desc")
        print(f"Returned {len(res)} works.")
        for w in res:
            add_candidate_if_valid(w)
            
    print(f"Candidates count after initial OpenAlex queries: {len(candidates)}")
    
    # Let's print details about the candidates
    print("\n--- ALL CANDIDATES IN POOL ---")
    for idx, w in enumerate(candidates):
        primary_loc = w.get("primary_location") or {}
        source_obj = primary_loc.get("source") or {} if primary_loc else {}
        journal = source_obj.get("display_name") or ""
        is_prest = is_prestigious_journal(journal)
        is_rel = is_work_relevant_to_discipline(w, discipline)
        pub_date = w.get("publication_date")
        citations = w.get("cited_by_count") or 0
        
        print(f"{idx+1}. {w.get('title')}")
        print(f"   Journal: {journal} (Prestigious: {is_prest})")
        print(f"   Date: {pub_date} | Citations: {citations} | Relevant: {is_rel}")
        print(f"   Sort key: (rel={is_rel}, date={pub_date}, prest={is_prest}, citations={citations})")
        print("---")

if __name__ == "__main__":
    asyncio.run(main())
