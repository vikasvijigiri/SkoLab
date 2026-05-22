import firebase_admin
from firebase_admin import credentials, firestore
import httpx
import numpy as np
import os
from typing import Optional, List, Dict
import re
import concurrent.futures

# Initialize Firebase with Project ID only (for simple environments)
if not firebase_admin._apps:
    try:
        # Try to use default credentials first
        cred = credentials.ApplicationDefault()
        firebase_admin.initialize_app(cred)
    except Exception:
        try:
            # Fallback for local development if ADC is not set
            firebase_admin.initialize_app(options={
                'projectId': 'resqit-a7980',
            })
        except Exception:
            pass

FIRESTORE_AVAILABLE = False
db = None

def set_firestore_available(available: bool):
    global FIRESTORE_AVAILABLE, db
    FIRESTORE_AVAILABLE = available
    if available:
        try:
            db = firestore.client()
        except Exception as e:
            print(f"[Firestore] Failed to obtain client: {e}", flush=True)
            db = None
            FIRESTORE_AVAILABLE = False
    else:
        db = None
    print(f"[Firestore] FIRESTORE_AVAILABLE set to {FIRESTORE_AVAILABLE}", flush=True)

def check_connection_sync() -> bool:
    print("[Firestore] connection check bypassed (returns False)", flush=True)
    return False

from app.services.metrics_service import MetricsService
from app.services.prediction_service import PredictionService

metrics_service = MetricsService()
prediction_service = PredictionService()

async def teleport_researcher(author_id: str):
    """
    Deep-fetches all metrics and works for a researcher and caches them in Firestore.
    """
    if not author_id:
        return None

    clean_id = author_id.split("/")[-1]
    fetch_url = f"https://api.openalex.org/authors/{clean_id}"
    params = {"mailto": "vikki.4me@gmail.com"}
    headers = {
        "User-Agent": "ResQitApp/1.0 (mailto:vikki.4me@gmail.com)",
        "Accept": "application/json"
    }

    async with httpx.AsyncClient(timeout=30.0, headers=headers) as client:
        # 1. Fetch Author Profile
        try:
            print(f"Teleporting: {fetch_url}")
            res = await client.get(fetch_url, params=params)
            if res.status_code != 200:
                return None
            data = res.json()
        except Exception as e:
            print(f"Teleportation Profile Fetch Failed: {e}")
            return None
        
        # 2. Fetch Detailed Works
        canonical_id = data.get("id", clean_id).split("/")[-1]
        works_url = f"https://api.openalex.org/works"
        works_params = {
            "filter": f"authorships.author.id:{canonical_id}",
            "per_page": 200, # Fetch up to 200 works instead of 10
            "sort": "publication_year:desc", # Sort by year descending to get most recent first
            "mailto": "vikki.4me@gmail.com"
        }
        
        works_results = []
        try:
            works_res = await client.get(works_url, params=works_params)
            if works_res.status_code == 200:
                works_results = works_res.json().get("results", [])
        except Exception as e:
            print(f"Teleportation Works Fetch Failed: {e}")

        # 3. Deep Analysis for each Work
        processed_works = []
        total_creativity = 0
        total_complexity = 0
        total_skill_score = 0
        total_impact = 0
        
        # Aggregators for Author-level Modern Metrics
        all_yearly_citations = {} # year -> count
        all_countries = []
        topic_counts = {}
        concept_counts = {}
        weighted_concepts = {}
        total_policy_cites = 0
        total_patent_cites = 0
        open_science_flags = {"code": False, "data": False, "oa": False, "preprint": False}
        disruption_scores = []

        # Extract counts_by_year from author data for consistency/acceleration
        author_counts_by_year = data.get("counts_by_year", [])
        yearly_cites_list = sorted(author_counts_by_year, key=lambda x: x['year'])
        cites_sequence = [y.get("cited_by_count", 0) for y in yearly_cites_list]

        for w in works_results:
            # Impact Factor (from Journal)
            primary_location = w.get("primary_location", {})
            source = primary_location.get("source", {}) if primary_location else {}
            journal_impact = source.get("2yr_mean_citedness", 0) if source else 0
            
            # Open Science Flags
            if w.get("open_access", {}).get("is_oa"): open_science_flags["oa"] = True
            if w.get("has_fulltext"): open_science_flags["preprint"] = True
            # OpenAlex doesn't always have code/data flags explicitly like this, but we can check locations
            for loc in w.get("locations", []):
                if loc and loc.get("source") and loc.get("source", {}).get("type") == "repository":
                    open_science_flags["data"] = True
            
            # Countries
            for authorship in w.get("authorships", []):
                for inst in authorship.get("institutions", []):
                    country_code = inst.get("country_code")
                    if country_code: all_countries.append(country_code)

            # Count work-level concepts (Level 1/2 is the sweet spot for research interests)
            for c in w.get("concepts", []):
                level = c.get("level")
                name = c.get("display_name")
                if level is not None and level >= 1:
                    topic_counts[name] = topic_counts.get(name, 0) + 1
                    
                    score = c.get("score", 0)
                    citations = w.get("cited_by_count", 0)
                    
                    # Statistical count
                    concept_counts[name] = concept_counts.get(name, 0) + 1
                    
                    # Weighted importance (Quality-adjusted interest)
                    weight = score * np.log(citations + 2.718)
                    weighted_concepts[name] = weighted_concepts.get(name, 0) + weight

            # Disruption approximation (Mocked if data missing, or use citations)
            # D-Index usually requires citation network data which is heavy. 
            # We'll use a scaled proxy for this demo or placeholder.
            d_proxy = min(1.0, w.get("cited_by_count", 0) / 100.0) if w.get("cited_by_count", 0) > 0 else 0.1
            disruption_scores.append(d_proxy)

            # Use journal impact if available, else fallback to citations per year
            if journal_impact and journal_impact > 0:
                impact_factor = round(journal_impact, 2)
            else:
                pub_year = w.get("publication_year", 2024)
                years_active_paper = max(1, 2024 - pub_year + 1)
                impact_factor = round(w.get("cited_by_count", 0) / years_active_paper, 2)

            abstract_inverted = w.get("abstract_inverted_index")
            abstract = ""
            if abstract_inverted:
                word_pos = []
                for word, positions in abstract_inverted.items():
                    for pos in positions: word_pos.append((pos, word))
                word_pos.sort()
                abstract = " ".join([wp[1] for wp in word_pos])

            concepts = [
                f"{c.get('display_name')} (Level: {c.get('level')}, Relevance: {c.get('score')})" 
                for c in w.get("concepts", [])[:10]
            ]
            
            paper_dna = {
                "abstract": abstract,
                "concepts": concepts,
                "cited_by_count": w.get("cited_by_count", 0)
            }
            
            # Calculate legacy metrics
            m = metrics_service.calculate_metrics(paper_dna)
            
            processed_works.append({
                "title": w.get("title"),
                "year": w.get("publication_year"),
                "doi": w.get("doi"),
                "journal": source.get("display_name") if source else None,
                "is_open_access": bool(w.get("open_access", {}).get("is_oa")),
                "citations": w.get("cited_by_count", 0),
                "creativity_score": m["creativity"],
                "complexity_score": m["complexity"],
                "skill_score": m["skill_set_score"],
                "impact_factor": impact_factor,
                "disruption_score": round(d_proxy * 100, 1),
                "semantic_novelty": m["creativity"],
                "open_science_score": m["skill_set_score"]
            })
            total_creativity += m["creativity"]
            total_complexity += m["complexity"]
            total_skill_score += m["skill_set_score"]
            total_impact += impact_factor

        # 4. Calculate All 10 Modern Metrics
        h_index = data.get("summary_stats", {}).get("h_index", 0)
        avg_impact = round(total_impact / len(processed_works), 2) if processed_works else 0
        
        # Network Centrality Proxy: Unique Co-authors count
        co_authors = set()
        for w in works_results:
            for authorship in w.get("authorships", []):
                author_id = authorship.get("author", {}).get("id")
                if author_id and author_id != data.get("id"):
                    co_authors.add(author_id)
        
        modern_metrics = {
            "disruption_score": round(np.mean(disruption_scores) * 100, 1) if disruption_scores else 0.0,
            "citation_acceleration": metrics_service.calculate_citation_acceleration(cites_sequence),
            "future_impact_score": metrics_service.calculate_future_impact(data.get("cited_by_count", 0) / 10, avg_impact, h_index),
            "network_centrality": float(min(100.0, len(co_authors) * 4.0 + 15)), # Dynamic proxy
            "semantic_novelty": round(total_creativity / len(processed_works), 1) if processed_works else 0.0,
            "interdisciplinary_index": metrics_service.calculate_interdisciplinary_index(topic_counts),
            "policy_patent_score": float(total_policy_cites * 5 + total_patent_cites * 10 + 5), # +5 base
            "open_science_score": metrics_service.calculate_open_science_score(
                open_science_flags["code"], open_science_flags["data"], 
                open_science_flags["oa"], open_science_flags["preprint"]
            ),
            "collaboration_diversity": metrics_service.calculate_collaboration_diversity(all_countries),
            "research_consistency": metrics_service.calculate_research_consistency(cites_sequence)
        }

        avg_creativity = modern_metrics["semantic_novelty"]
        avg_complexity = modern_metrics["interdisciplinary_index"]
        avg_skill_score = modern_metrics["open_science_score"]
        
        # Calculate Average Activity (Publications per year)
        first_pub_year = data.get("summary_stats", {}).get("first_publication_year")
        last_pub_year = data.get("summary_stats", {}).get("last_publication_year", 2024)
        
        if first_pub_year:
            years_active = max(1, last_pub_year - first_pub_year + 1)
            avg_activity = round(data.get("works_count", 0) / years_active, 2)
        else:
            avg_activity = 0.0

        # 4. Innovation Score & Prediction
        # Expertise/Interests: Quality-Weighted top areas from paper-level concepts
        # This prioritizes what the researcher is FAMOUS for (citations) and focuses on (frequency)
        sorted_interests = sorted(weighted_concepts.items(), key=lambda x: x[1], reverse=True)
        # Exclude very broad Level 0 terms from expertise if possible
        top_research_interests = [area for area, weight in sorted_interests[:6]]
        
        author_concepts = data.get("x_concepts", [])
        
        # Primary Research Area: The most relevant Level 1 concept (e.g. "Condensed Matter Physics")
        # Level 0 is usually too broad (e.g. "Physics")
        # ArXiv-aligned mapping: Prefer Level 1 concepts as they map closely to ArXiv categories
        primary_area = "Multidisciplinary"
        for c in author_concepts:
            if c.get("level") == 1:
                primary_area = c.get("display_name")
                # Boost for common ArXiv-like physics categories
                if any(x in primary_area.lower() for x in ["condensed matter", "high energy", "astrophysics", "quantum physics", "nuclear", "general relativity"]):
                    break 
        
        if primary_area == "Multidisciplinary" and author_concepts:
            # Fallback to level 0 or first available
            primary_area = author_concepts[0].get("display_name")

        # Expertise: Mix of top quality-weighted interests and specific profile levels
        # Focus on Level 2/3 for "Strongly Correlated Systems" level precision
        expertise_pool = [
            c.get("display_name") for c in author_concepts 
            if c.get("level") in [1, 2, 3]
        ]
        # Intersect with weighted top interests to ensure accuracy
        expertise = [e for e in top_research_interests if e in expertise_pool]
        # Fill remaining slots with highest weighted specific concepts
        for item in top_research_interests:
            if item not in expertise:
                expertise.append(item)
        
        expertise = list(dict.fromkeys(expertise))[:8] # Unique and limit to 8 tags for density

        # Fallback
        if not expertise:
            expertise = [c.get("display_name") for c in author_concepts[:3]]

        scores = [c.get("score", 0) for c in author_concepts if c.get("level") is not None and c.get("level") <= 1]
        innovation_score = calculate_innovation_score(scores)
        
        # Predict Frontier using titles from the latest 3 publications
        recent_titles = [w.get("title") for w in works_results[:3] if w.get("title")]
        next_prediction = await prediction_service.predict_next_problem(recent_titles)

        affiliations = data.get("affiliations", [])
        history_map = {}
        for aff in affiliations:
            inst = aff.get("institution")
            if not inst or not inst.get("display_name"): continue
            name = inst.get("display_name")
            years = aff.get("years", [])
            if not years: continue
            
            if name not in history_map:
                history_map[name] = {"min": min(years), "max": max(years)}
            else:
                history_map[name]["min"] = min(history_map[name]["min"], min(years))
                history_map[name]["max"] = max(history_map[name]["max"], max(years))
        
        # Sort institutions chronologically by the earliest year of affiliation
        sorted_history = sorted(history_map.items(), key=lambda x: x[1]["min"])
        history = [
            f"{name} ({years['min']} — {years['max']})" if years["min"] != years["max"] else f"{name} ({years['min']})"
            for name, years in sorted_history
        ]

        # Safely resolve current institution
        last_insts = data.get("last_known_institutions")
        curr_inst = "Independent"
        if last_insts and isinstance(last_insts, list) and len(last_insts) > 0:
            first_inst = last_insts[0]
            if first_inst and isinstance(first_inst, dict):
                curr_inst = first_inst.get("display_name") or "Independent"

        # 5. Build Document
        researcher_profile = {
            "openalex_id": data.get("id", author_id),
            "display_name": data.get("display_name"),
            "orcid": data.get("orcid"),
            "current_institution": curr_inst,
            "field_of_study": primary_area,
            "expertise": expertise,
            "innovation_score": round(innovation_score, 2),
            "average_creativity": avg_creativity,
            "average_complexity": avg_complexity,
            "average_skill_score": avg_skill_score,
            "average_impact": avg_impact,
            "average_activity": avg_activity,
            "h_index": data.get("summary_stats", {}).get("h_index", 0),
            "i10_index": data.get("summary_stats", {}).get("i10_index", 0),
            "works_count": data.get("works_count", 0),
            "cited_by_count": data.get("cited_by_count", 0),
            "academic_history": history,
            "works": processed_works,
            "next_prediction": next_prediction,
            "is_verified": True,
            "last_synced": firestore.SERVER_TIMESTAMP,
            # Modern 10 Metrics
            **modern_metrics
        }

        # 6. Atomic Sync
        if FIRESTORE_AVAILABLE and db:
            try:
                db.collection("global_researchers").document(canonical_id).set(researcher_profile)
                print(f"Successfully vaulted Deep Data with Prediction for: {researcher_profile['display_name']}")
            except Exception as e:
                print(f"Firestore Sync Failed: {e}")
        else:
            print("[Firestore] Bypassing Sync (Firestore unavailable)")

        return researcher_profile

def calculate_innovation_score(scores: List[float]) -> float:
    if not scores or sum(scores) == 0: return 0.0
    probs = np.array(scores) / sum(scores)
    entropy = -np.sum(probs * np.log2(probs + 1e-9))
    return float(min((entropy / 3.0) * 100, 100.0))
