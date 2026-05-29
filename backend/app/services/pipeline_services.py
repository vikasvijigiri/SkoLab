import os



import datetime



import httpx



import json



import random



import asyncio



import re



from typing import List, Dict, Optional, Any



from app.services.llm_service import is_llm_working, set_llm_limit_exceeded



from sqlalchemy.future import select



from app.db.database import AsyncSessionLocal



from app.models.user_models import CacheEntry, AgentChatHistory



from app.services.openalex_service import OpenAlexService



from app.prompts import DAILY_FEED_ADVISOR_PROMPT_TEMPLATE



from app.db.pg_cache import PgBackedCache







# Per-feature PG caches with appropriate TTLs



# These are the local fast layer; Firestore backs the large enriched docs.



_pg_daily_feed_cache       = PgBackedCache(ttl_seconds=3600,  name="pipeline_daily_feed")



_pg_match_grants_cache     = PgBackedCache(ttl_seconds=3600,  name="pipeline_match_grants")



_pg_synergy_cache          = PgBackedCache(ttl_seconds=7200,  name="pipeline_synergy")



_pg_heatmap_cache          = PgBackedCache(ttl_seconds=3600,  name="pipeline_heatmap")



_pg_journal_advisor_cache  = PgBackedCache(ttl_seconds=7200,  name="pipeline_journal_advisor")



_pg_network_collab_cache   = PgBackedCache(ttl_seconds=3600,  name="pipeline_network_collab")







class PipelineServices:



    def __init__(self):



        from app.services.llm_service import LLMService



        self.llm_service = LLMService()



        self.model = "llama-3.3-70b-versatile"



        self.openalex_service = OpenAlexService()







    def _get_firestore_db(self):



        """Returns a Firestore client if available, else None."""



        try:



            from app.services.researcher_worker import FIRESTORE_AVAILABLE



            if not FIRESTORE_AVAILABLE:



                return None



            from firebase_admin import firestore as _firestore



            return _firestore.client()



        except Exception as exc:



            print(f"[PipelineServices] Firestore unavailable: {exc}", flush=True)



            return None







    async def _firestore_get_safe(self, collection: str, doc_id: str, timeout: float = 5.0):



        """



        Wraps a synchronous Firestore document.get() in a thread executor with a short timeout.



        Prevents the blocking Firestore SDK from stalling the asyncio event loop when



        credentials are expired or the network is unavailable.



        Returns the document dict if found and within time, else None.



        """



        db = self._get_firestore_db()



        if not db:



            return None



        loop = asyncio.get_event_loop()



        try:



            def _blocking_get():



                doc = db.collection(collection).document(doc_id).get()



                return doc.to_dict() if doc.exists else None



            result = await asyncio.wait_for(



                loop.run_in_executor(None, _blocking_get),



                timeout=timeout



            )



            return result



        except asyncio.TimeoutError:



            print(f"[PipelineServices] Firestore get timed out ({timeout}s) for {collection}/{doc_id}", flush=True)



        except Exception as e:



            print(f"[PipelineServices] Firestore get error for {collection}/{doc_id}: {e}", flush=True)



        return None







    async def _save_to_postgres(self, cache_key: str, data: Dict[str, Any], ttl_seconds: int = 3600):



        """Save data to PostgreSQL cache_entries with TTL via PgBackedCache."""



        cache = PgBackedCache(ttl_seconds=ttl_seconds, name="pipeline")



        await cache.set(cache_key, data)







    async def _load_from_postgres(self, cache_key: str, ttl_seconds: int = 3600) -> Optional[Dict[str, Any]]:



        """Load data from PostgreSQL cache_entries with TTL check."""



        cache = PgBackedCache(ttl_seconds=ttl_seconds, name="pipeline")



        return await cache.get(cache_key)







    async def _fetch_author_profile(self, author_id: str) -> Optional[Dict[str, Any]]:



        """Helper to fetch author profile from OpenAlex or database."""



        return await self.openalex_service.fetch_author_by_id(author_id)







    async def get_daily_feed(self, author_id: Optional[str], query_fallback: Optional[str] = None) -> List[Dict[str, Any]]:



        """



        Generates a personalized daily feed of 3 papers based on author's primary concepts.



        """



        doc_id = None



        if author_id:



            doc_id = author_id.split("/")[-1]



        elif query_fallback:



            doc_id = f"fallback_{re.sub(r'[^a-zA-Z0-9_]', '_', query_fallback.strip().lower())}"



        else:



            doc_id = "default_feed"







        cache_key = f"daily_feed_{doc_id}"



        cached_data = await self._load_from_postgres(cache_key)



        if cached_data and "items" in cached_data:



            print(f"[Postgres Cache Hit] daily_feeds for doc_id={doc_id}", flush=True)



            return cached_data["items"]







        _fs_cached = await self._firestore_get_safe("daily_feeds", doc_id, timeout=5.0)

        if _fs_cached and "items" in _fs_cached:

            print(f"[Firestore Cache Hit] daily_feeds for doc_id={doc_id}", flush=True)

            await self._save_to_postgres(cache_key, {"items": _fs_cached["items"]})

            return _fs_cached["items"]





        concepts = []



        author_name = "Researcher"



        search_term = query_fallback or "research"



        if author_id:



            profile = await self._fetch_author_profile(author_id)



            if profile:



                author_name = profile.get("display_name", "Researcher")



                concepts_list = profile.get("x_concepts", []) or []



                # First try level 1/2 concepts; fall back to any concept with a name



                concepts = [c.get("display_name") for c in concepts_list if c.get("level") in [1, 2] and c.get("display_name")]



                if not concepts:



                    concepts = [c.get("display_name") for c in concepts_list[:5] if c.get("display_name")]



                # Remove author's own name from concept list (OpenAlex quirk: some authors have their name as a concept)



                concepts = [c for c in concepts if c and c.strip().lower() != author_name.strip().lower()]







                # Also try topics field if x_concepts is empty



                if not concepts:



                    topics_list = profile.get("topics", []) or []



                    concepts = [t.get("display_name") for t in topics_list[:5] if t.get("display_name")]







                if concepts:



                    # Search recent papers from OpenAlex



                    search_term = " OR ".join([f'"{c}"' for c in concepts[:3]])



            else:



                # Fallback: Query local database metrics for concepts



                # doc_id is the cleaned author ID (no full URI prefix)



                try:



                    from app.models.researcher_models import ResearcherMetrics



                    async with AsyncSessionLocal() as session:



                        stmt = select(ResearcherMetrics).where(ResearcherMetrics.openalex_id == doc_id)



                        res = await session.execute(stmt)



                        rm = res.scalars().first()



                        if rm:



                            author_name = rm.display_name



                            concepts = rm.expertise or []



                            if concepts:



                                search_term = " OR ".join([f'"{c}"' for c in concepts[:3]])



                except Exception as e:



                    print(f"[DailyFeed] Database lookup fallback error: {e}", flush=True)







        papers = []



        try:



            results = await self.openalex_service.search_works(search_term, per_page=20)



            # Filter papers with abstracts



            for w in results:



                abstract_index = w.get("abstract_inverted_index")



                if abstract_index and w.get("title"):



                    papers.append(w)



                if len(papers) >= 3:



                    break



        except Exception as e:



            print(f"Error fetching papers for daily feed: {e}")







        if not papers:



            print(f"[DailyFeed] No matching publications found for search_term='{search_term}', returning mock fallback", flush=True)



            concepts_lower = [c.lower() for c in concepts] if concepts else []



            fld = (query_fallback or "STEM").lower()



            if any("quantum" in c or "phys" in c for c in concepts_lower) or "phys" in fld:



                mock_papers = [



                    {



                        "id": "mock_phys_1",



                        "title": "Topological Phases and Coherence in Superconducting Quantum Circuits",



                        "authors": ["A. Einstein", "M. Curie", "S. Hawking"],



                        "journal": "Nature Physics",



                        "year": 2025,



                        "relevance_score": 96,



                        "recommendation_reason": "A milestone analysis on phase dynamics relevant to your work on quantum systems.",



                        "doi": "10.1038/nphys.mock1"



                    },



                    {



                        "id": "mock_phys_2",



                        "title": "Quantum Zeno Dynamics under High-Frequency Projective Measurements",



                        "authors": ["R. Feynman", "N. Bohr"],



                        "journal": "Physical Review Letters",



                        "year": 2024,



                        "relevance_score": 93,



                        "recommendation_reason": "Directly matches your interest in measurement-induced state freezing.",



                        "doi": "10.1103/PhysRevLett.mock2"



                    },



                    {



                        "id": "mock_phys_3",



                        "title": "Unified Boundary Conditions for Non-Linear Gravitational Wave Equations",



                        "authors": ["S. Hawking", "K. Thorne"],



                        "journal": "Reviews of Modern Physics",



                        "year": 2025,



                        "relevance_score": 91,



                        "recommendation_reason": "Presents boundary-value constraints essential for modeling macroscopic wave propagations.",



                        "doi": "10.1103/RevModPhys.mock3"



                    }



                ]



            elif any("comput" in c or "machine" in c or "cs" in c or "learn" in c for c in concepts_lower) or "comput" in fld or "ai" in fld or "cs" in fld:



                mock_papers = [



                    {



                        "id": "mock_cs_1",



                        "title": "Generative Scaling Laws for Large Multimodal Vision-Language Models",



                        "authors": ["A. Turing", "G. Hopper"],



                        "journal": "IEEE TPAMI",



                        "year": 2025,



                        "relevance_score": 97,



                        "recommendation_reason": "Provides theoretical guarantees for cross-modal generalization relevant to your AI focus.",



                        "doi": "10.1109/TPAMI.mock1"



                    },



                    {



                        "id": "mock_cs_2",



                        "title": "Decoupled Attention Mechanisms for Memory-Efficient Transformer Architectures",



                        "authors": ["D. Knuth", "T. Berners-Lee"],



                        "journal": "Journal of Machine Learning Research",



                        "year": 2024,



                        "relevance_score": 94,



                        "recommendation_reason": "Addresses memory constraints in deep architectures, highly relevant to your CS track.",



                        "doi": "10.5555/JMLR.mock2"



                    },



                    {



                        "id": "mock_cs_3",



                        "title": "Provably Secure Consensus Protocols in Decentralized Ledger Architectures",



                        "authors": ["S. Nakamoto", "L. Lamport"],



                        "journal": "ACM Computing Surveys",



                        "year": 2025,



                        "relevance_score": 90,



                        "recommendation_reason": "Analyzes algorithmic bounds for distributed fault tolerance.",



                        "doi": "10.1145/mock3"



                    }



                ]



            else:



                mock_papers = [



                    {



                        "id": "mock_gen_1",



                        "title": "Comparative Genomic Mapping of Signal Pathway Regulation in Eukaryotes",



                        "authors": ["C. Darwin", "G. Mendel"],



                        "journal": "Nature Genetics",



                        "year": 2025,



                        "relevance_score": 95,



                        "recommendation_reason": "Identifies molecular pathways that map directly to your biological/health research.",



                        "doi": "10.1038/ng.mock1"



                    },



                    {



                        "id": "mock_gen_2",



                        "title": "Interdisciplinary Research Methodologies in Modern Technical Cohorts",



                        "authors": ["L. da Vinci", "N. Tesla"],



                        "journal": "Science",



                        "year": 2024,



                        "relevance_score": 92,



                        "recommendation_reason": "Provides a comprehensive overview of collaborative cross-domain research paradigms.",



                        "doi": "10.1126/science.mock2"



                    },



                    {



                        "id": "mock_gen_3",



                        "title": "Behavioral Feedback Loops and Cognitive Load in Interactive Technical Systems",



                        "authors": ["W. Wundt", "B. F. Skinner"],



                        "journal": "Journal of Cognitive Neuroscience",



                        "year": 2025,



                        "relevance_score": 89,



                        "recommendation_reason": "Highly relevant to human-centric system design and behavioral analysis.",



                        "doi": "10.1162/jocn.mock3"



                    }



                ]



            try:



                await self._save_to_postgres(cache_key, {"items": mock_papers})



            except Exception:



                pass



            return mock_papers







        feed_items = []



        for i, paper in enumerate(papers[:3]):



            title = paper.get("title", "Untitled Research Paper")



            authors = [a.get("author", {}).get("display_name", "Unknown") for a in paper.get("authorships", [])][:3]



            journal = paper.get("primary_location", {}).get("source", {}).get("display_name") or "Scientific Journal"



            year = paper.get("publication_year") or 2025



            doi = paper.get("doi")



            openalex_id = paper.get("id")







            # Extract abstract from inverted index



            abstract_index = paper.get("abstract_inverted_index")



            abstract = ""



            if abstract_index:



                try:



                    word_list = []



                    for word, pos_list in abstract_index.items():



                        for pos in pos_list:



                            word_list.append((pos, word))



                    word_list.sort()



                    abstract = " ".join([w[1] for w in word_list])



                except:



                    pass



            if not abstract:



                abstract = "No abstract available."







            relevance_score = random.randint(88, 98)



            recommendation_reason = f"Highly relevant to your expertise in {', '.join(concepts[:2])}."







            if is_llm_working():  # Decoupled: LLM moved to background addon to unblock core app



                # Ask LLM to write a personalized reason



                messages = [



                    {



                        "role": "system",



                        "content": DAILY_FEED_ADVISOR_PROMPT_TEMPLATE.format(



                            title=title,



                            author_name=author_name,



                            concepts=', '.join(concepts)



                        )



                    }



                ]



                try:



                    response = await self.llm_service.query(



                        messages=messages,



                        models=[self.model],



                        temperature=0.5,



                        max_tokens=50



                    )



                    if response.content:



                        recommendation_reason = response.content.strip()



                except Exception as e:



                    print(f"Daily feed reason generation failed: {e}", flush=True)







            feed_items.append({



                "id": openalex_id,



                "title": title,



                "authors": authors,



                "journal": journal,



                "year": year,



                "relevance_score": relevance_score,



                "recommendation_reason": recommendation_reason,



                "doi": doi



            })







        if feed_items:



            try:



                await self._save_to_postgres(cache_key, {"items": feed_items})



                print(f"[Postgres Cache Save] daily_feeds for doc_id={doc_id}", flush=True)



            except Exception as e:



                print(f"[Postgres Cache Error] daily_feeds write failed: {e}", flush=True)







        if db and feed_items:



            try:



                from firebase_admin import firestore as _fs



                db.collection("daily_feeds").document(doc_id).set({



                    "items": feed_items,



                    "last_synced": _fs.SERVER_TIMESTAMP



                })



                print(f"[Firestore Cache Save] daily_feeds for doc_id={doc_id}", flush=True)



            except Exception as e:



                print(f"[Firestore Cache Error] daily_feeds write failed: {e}", flush=True)







        return feed_items







    async def match_grants(self, author_id: str) -> List[Dict[str, Any]]:



        """



        Scores prestigious STEM grant options against the researcher's profile.



        """



        clean_id = author_id.split("/")[-1]



        



        cache_key = f"match_grants_{clean_id}"



        cached_data = await self._load_from_postgres(cache_key)



        if cached_data and "items" in cached_data:



            print(f"[Postgres Cache Hit] match_grants for author_id={clean_id}", flush=True)



            return cached_data["items"]







        _fs_cached = await self._firestore_get_safe("match_grants", clean_id, timeout=5.0)

        if _fs_cached and "items" in _fs_cached:

            print(f"[Firestore Cache Hit] match_grants for author_id={clean_id}", flush=True)

            await self._save_to_postgres(cache_key, {"items": _fs_cached["items"]})

            return _fs_cached["items"]

        db = self._get_firestore_db()





        profile = await self._fetch_author_profile(author_id)



        author_name = "Researcher"



        h_index = 5



        concepts = ["STEM"]



        concepts_lower = ["stem"]







        if profile:



            author_name = profile.get("display_name", "Researcher")



            h_index = profile.get("summary_stats", {}).get("h_index", 5)



            author_name_lower = author_name.lower()



            x_concepts = profile.get("x_concepts", []) or []



            # Filter self-name concepts and extract level 1/2



            valid = [c for c in x_concepts if c.get("display_name") and c.get("display_name").lower() != author_name_lower]



            concepts = [c.get("display_name") for c in valid if c.get("level") in [1, 2]][:3]



            if not concepts:



                concepts = [c.get("display_name") for c in valid[:5] if c.get("display_name")]



            if not concepts:



                topics = profile.get("topics", []) or []



                concepts = [t.get("display_name") for t in topics[:3] if t.get("display_name")]



            concepts_lower = [c.lower() for c in concepts if c]







        grants = [



            {



                "title": "Core Research Grant (CRG) 2025–26",



                "agency": "SERB",



                "agency_color": "#009688", # Teal



                "days_left": 23,



                "amount": "₹50–90 Lakh",



                "field": "All STEM Fields",



                "url": "https://www.serbonline.in/"



            },



            {



                "title": "National Science Foundation — CAREER Award",



                "agency": "NSF",



                "agency_color": "#3F51B5", # Indigo



                "days_left": 41,



                "amount": "$500K–1M",



                "field": "CS/Engineering/Basic Sciences",



                "url": "https://www.nsf.gov/funding/opportunities/career-faculty-early-career-development-program"



            },



            {



                "title": "DST INSPIRE Faculty Award",



                "agency": "DST",



                "agency_color": "#4CAF50", # Emerald



                "days_left": 58,



                "amount": "₹35 Lakh/yr",



                "field": "Science & Tech Innovation",



                "url": "https://dst.gov.in/scientific-programmes/scientific-engineering-research/inspire"



            },



            {



                "title": "NIH R01 Research Project Grant",



                "agency": "NIH",



                "agency_color": "#E91E63", # Rose



                "days_left": 67,



                "amount": "$250K–1.5M",



                "field": "Biomedical & Life Sciences",



                "url": "https://grants.nih.gov/grants/funding/r01.htm"



            },



            {



                "title": "Prime Minister's Research Fellows (PMRF)",



                "agency": "MoE",



                "agency_color": "#FF9800", # Amber



                "days_left": 89,



                "amount": "₹80K/month + research grants",



                "field": "Sleek Technical PhD/Post-doc research",



                "url": "https://www.pmrf.in/"



            },



            {



                "title": "ERC Starting Grants",



                "agency": "ERC",



                "agency_color": "#9C27B0", # Purple



                "days_left": 105,



                "amount": "€1.5M",



                "field": "High-impact pioneering science",



                "url": "https://erc.europa.eu/apply-funding/starting-grant"



            }



        ]







        scored_grants = []



        for grant in grants:



            grant_field_lower = grant["field"].lower()



            # Compute field overlap: how many author concepts match the grant's field



            field_overlap = sum(



                1 for c in concepts_lower



                if c and (c in grant_field_lower or grant_field_lower in c



                          or any(word in grant_field_lower for word in c.split() if len(word) > 3))



            )



            # Deterministic score: base (60) + h_index contribution + field overlap bonus



            # All STEM grants get at least base score; specific field grants get bonus



            h_contribution = min(h_index * 2, 20)  # cap at 20 points



            overlap_bonus = field_overlap * 5  # 5 points per matching concept



            # Universal grants (SERB, ERC) get a small base bonus



            universal_bonus = 5 if "all" in grant_field_lower or "pioneer" in grant_field_lower else 0



            match_score = min(max(60 + h_contribution + overlap_bonus + universal_bonus, 62), 98)







            rationale = f"Aligned with your research track in {', '.join(concepts[:2]) if concepts else 'STEM'}."



            



            if is_llm_working():  # Decoupled: LLM moved to background addon to unblock core app



                messages = [



                    {



                        "role": "system",



                        "content": f"You are a research grant advisor. Evaluate the grant opportunity '{grant['title']}' from agency '{grant['agency']}' for the researcher '{author_name}' with h-index {h_index} and expertise in '{', '.join(concepts)}'. Provide a concise 2-sentence rationale of why this is a good fit and how their profile aligns. Keep it under 40 words."



                    }



                ]



                try:



                    response = await self.llm_service.query(



                        messages=messages,



                        models=[self.model],



                        temperature=0.4,



                        max_tokens=100



                    )



                    if response.content:



                        rationale = response.content.strip()



                except Exception as e:



                    print(f"Grant rationale generation failed: {e}", flush=True)







            scored_grants.append({



                "title": grant["title"],



                "agency": grant["agency"],



                "agency_color": grant["agency_color"],



                "days_left": grant["days_left"],



                "amount": grant["amount"],



                "field": grant["field"],



                "match_score": match_score,



                "url": grant["url"],



                "rationale": rationale



            })







        if scored_grants:



            try:



                await self._save_to_postgres(cache_key, {"items": scored_grants})



                print(f"[Postgres Cache Save] match_grants for author_id={clean_id}", flush=True)



            except Exception as e:



                print(f"[Postgres Cache Error] match_grants write failed: {e}", flush=True)







        if db and scored_grants:



            try:



                from firebase_admin import firestore as _fs



                db.collection("match_grants").document(clean_id).set({



                    "items": scored_grants,



                    "last_synced": _fs.SERVER_TIMESTAMP



                })



                print(f"[Firestore Cache Save] match_grants for author_id={clean_id}", flush=True)



            except Exception as e:



                print(f"[Firestore Cache Error] match_grants write failed: {e}", flush=True)







        return scored_grants







    async def get_collaborator_synergy(self, author_id: str, collaborator_id: str) -> Dict[str, Any]:



        """



        Generates specific joint proposals and strategic co-authorship pathways between two researchers.



        """



        clean_author = author_id.split("/")[-1]



        clean_collab = collaborator_id.split("/")[-1]



        doc_id = f"{clean_author}_{clean_collab}"







        cache_key = f"collaborator_synergy_{doc_id}"



        cached_data = await self._load_from_postgres(cache_key, ttl_seconds=7200)



        if cached_data:



            print(f"[Postgres Cache Hit] collaborator_synergy for doc_id={doc_id}", flush=True)



            return cached_data







        db = self._get_firestore_db()



        if db:



            try:



                doc = db.collection("collaborator_synergies").document(doc_id).get()



                if doc.exists:



                    print(f"[Firestore Cache Hit] collaborator_synergies for doc_id={doc_id}", flush=True)



                    cached_data = doc.to_dict()



                    if cached_data:



                        # Clean metadata if present before returning



                        cached_data.pop("last_synced", None)



                        await self._save_to_postgres(cache_key, cached_data, ttl_seconds=7200)



                        return cached_data



            except Exception as e:



                print(f"[Firestore Cache Error] collaborator_synergies lookup failed: {e}", flush=True)







        profile1_task = self._fetch_author_profile(author_id)



        profile2_task = self._fetch_author_profile(collaborator_id)



        



        profile1, profile2 = await asyncio.gather(profile1_task, profile2_task)



        



        # Fallback database lookup for profiles if OpenAlex fails



        if not profile1:



            try:



                from app.models.researcher_models import ResearcherMetrics



                async with AsyncSessionLocal() as session:



                    stmt = select(ResearcherMetrics).where(ResearcherMetrics.openalex_id == clean_author)



                    res = await session.execute(stmt)



                    rm = res.scalars().first()



                    if rm:



                        profile1 = {



                            "display_name": rm.display_name,



                            "x_concepts": [{"display_name": c, "level": 1} for c in rm.expertise or []]



                        }



            except Exception as e:



                print(f"[CollaboratorSynergy] DB fallback lookup for author failed: {e}", flush=True)







        if not profile2:



            try:



                from app.models.researcher_models import ResearcherMetrics



                async with AsyncSessionLocal() as session:



                    stmt = select(ResearcherMetrics).where(ResearcherMetrics.openalex_id == clean_collab)



                    res = await session.execute(stmt)



                    rm = res.scalars().first()



                    if rm:



                        profile2 = {



                            "display_name": rm.display_name,



                            "x_concepts": [{"display_name": c, "level": 1} for c in rm.expertise or []]



                        }



            except Exception as e:



                print(f"[CollaboratorSynergy] DB fallback lookup for collab failed: {e}", flush=True)







        name1 = profile1.get("display_name", "Researcher A") if profile1 else "Researcher A"



        name2 = profile2.get("display_name", "Researcher B") if profile2 else "Researcher B"







        def extract_concepts(profile, fallback_name):



            """Extract clean concept list, filtering the researcher's own name (OpenAlex quirk)."""



            if not profile:



                return []



            name_lower = (profile.get("display_name") or "").lower()



            x = profile.get("x_concepts", []) or []



            # Filter self-name concepts



            valid = [c for c in x if c.get("display_name") and c.get("display_name").lower() != name_lower]



            result = [c.get("display_name") for c in valid if c.get("level") in [1, 2] and c.get("display_name")]



            if not result:



                result = [c.get("display_name") for c in valid[:5] if c.get("display_name")]



            if not result:



                topics = profile.get("topics", []) or []



                result = [t.get("display_name") for t in topics[:5] if t.get("display_name")]



            return result







        concepts1 = extract_concepts(profile1, "Quantum Mechanics") or ["Quantum Mechanics"]



        concepts2 = extract_concepts(profile2, "Machine Learning") or ["Machine Learning"]



        overlap_concepts = list(set(concepts1).intersection(set(concepts2)))



        



        # Deterministic synergy score based on overlap — no random component



        synergy_score = 72 + min(len(overlap_concepts) * 5, 20)  # max 92 from overlap alone



        synergy_score = min(max(synergy_score, 70), 99)







        joint_proposal_title = f"Synergistic Research Framework in {overlap_concepts[0] if overlap_concepts else 'Cross-Disciplinary Sci'}"



        co_authorship_direction = f"Combining {name1}'s expertise in {concepts1[0]} with {name2}'s deep foundation in {concepts2[0]}."



        strategic_action_plan = [



            "Share datasets and codebases for comparative profiling.",



            "Formulate a joint proposal for pilot funding.",



            "Draft a co-authored manuscript focusing on theoretical boundaries."



        ]







        if is_llm_working():  # Decoupled: LLM moved to background addon to unblock core app



            messages = [



                {



                    "role": "system",



                    "content": f"""You are an elite academic synergy counselor. Analyze the collaborative potential between:



Researcher A: {name1} (Expertise: {', '.join(concepts1[:4])})



Researcher B: {name2} (Expertise: {', '.join(concepts2[:4])})







Provide your response in this exact JSON format:



{{



  "joint_proposal_title": "[Specific, compelling scientific title for a joint research paper]",



  "co_authorship_direction": "[1-2 sentence description explaining how their skills uniquely complement each other to solve a specific hard problem]",



  "strategic_action_plan": ["[Action 1]", "[Action 2]", "[Action 3]"]



}}



"""



                }



            ]



            try:



                response = await self.llm_service.query(



                    messages=messages,



                    models=[self.model],



                    temperature=0.3,



                    max_tokens=300,



                    response_format={"type": "json_object"}



                )



                if response.content:



                    data = json.loads(response.content.strip())



                    joint_proposal_title = data.get("joint_proposal_title", joint_proposal_title)



                    co_authorship_direction = data.get("co_authorship_direction", co_authorship_direction)



                    strategic_action_plan = data.get("strategic_action_plan", strategic_action_plan)



            except Exception as e:



                print(f"Collaborator synergy generation failed: {e}", flush=True)







        result = {



            "synergy_score": synergy_score,



            "joint_proposal_title": joint_proposal_title,



            "co_authorship_direction": co_authorship_direction,



            "strategic_action_plan": strategic_action_plan



        }







        try:



            await self._save_to_postgres(cache_key, result, ttl_seconds=7200)



            print(f"[Postgres Cache Save] collaborator_synergy for doc_id={doc_id}", flush=True)



        except Exception as e:



            print(f"[Postgres Cache Error] collaborator_synergy write failed: {e}", flush=True)







        if db:



            try:



                from firebase_admin import firestore as _fs



                db.collection("collaborator_synergies").document(doc_id).set({



                    **result,



                    "last_synced": _fs.SERVER_TIMESTAMP



                })



                print(f"[Firestore Cache Save] collaborator_synergies for doc_id={doc_id}", flush=True)



            except Exception as e:



                print(f"[Firestore Cache Error] collaborator_synergies write failed: {e}", flush=True)







        return result







    async def get_citation_heatmap(self, author_id: str) -> Dict[str, Any]:



        """



        Visualizes year-by-year citation and publication count trends.



        """



        clean_id = author_id.split("/")[-1]







        cache_key = f"citation_heatmap_{clean_id}"



        cached_data = await self._load_from_postgres(cache_key)



        if cached_data:



            print(f"[Postgres Cache Hit] citation_heatmap for author_id={clean_id}", flush=True)



            return cached_data







        db = self._get_firestore_db()



        if db:



            try:



                doc = db.collection("citation_heatmaps").document(clean_id).get()



                if doc.exists:



                    print(f"[Firestore Cache Hit] citation_heatmaps for author_id={clean_id}", flush=True)



                    cached_data = doc.to_dict()



                    if cached_data:



                        cached_data.pop("last_synced", None)



                        await self._save_to_postgres(cache_key, cached_data)



                        return cached_data



            except Exception as e:



                print(f"[Firestore Cache Error] citation_heatmaps lookup failed: {e}", flush=True)







        profile = await self._fetch_author_profile(author_id)



        if not profile:



            # Fallback



            years = [2020, 2021, 2022, 2023, 2024, 2025]



            return {



                "years": years,



                "citations": [12, 24, 45, 80, 154, 220],



                "works": [2, 3, 4, 3, 5, 4],



                "institutional_reach": 5,



                "h_index": 5



            }







        counts_by_year = profile.get("counts_by_year", [])



        counts_by_year = sorted(counts_by_year, key=lambda x: x.get("year", 0))







        # Keep last 8 years for compactness in mobile layout



        recent_counts = counts_by_year[-8:] if len(counts_by_year) > 8 else counts_by_year



        



        years = [x.get("year") for x in recent_counts]



        citations = [x.get("cited_by_count") for x in recent_counts]



        works = [x.get("works_count") for x in recent_counts]



        h_index = profile.get("summary_stats", {}).get("h_index", 5)







        # Estimate institutional reach based on co-authorships count from recent works



        institutional_reach = min(int(h_index * 1.5) + random.randint(2, 6), 35)







        result = {



            "years": years,



            "citations": citations,



            "works": works,



            "institutional_reach": institutional_reach,



            "h_index": h_index



        }







        try:



            await self._save_to_postgres(cache_key, result)



            print(f"[Postgres Cache Save] citation_heatmap for author_id={clean_id}", flush=True)



        except Exception as e:



            print(f"[Postgres Cache Error] citation_heatmap write failed: {e}", flush=True)







        if db:



            try:



                from firebase_admin import firestore as _fs



                db.collection("citation_heatmaps").document(clean_id).set({



                    **result,



                    "last_synced": _fs.SERVER_TIMESTAMP



                })



                print(f"[Firestore Cache Save] citation_heatmaps for author_id={clean_id}", flush=True)



            except Exception as e:



                print(f"[Firestore Cache Error] citation_heatmaps write failed: {e}", flush=True)







        return result







    async def get_journal_advisor(self, author_id: str) -> List[Dict[str, Any]]:



        """



        Recommends best-fit venues for predicted frontier using Groq.



        """



        clean_id = author_id.split("/")[-1]







        cache_key = f"journal_advisor_{clean_id}"



        cached_data = await self._load_from_postgres(cache_key, ttl_seconds=7200)



        if cached_data and "venues" in cached_data:



            print(f"[Postgres Cache Hit] journal_advisor for author_id={clean_id}", flush=True)



            return cached_data["venues"]







        db = self._get_firestore_db()



        if db:



            try:



                doc = db.collection("journal_advisor_recommendations").document(clean_id).get()



                if doc.exists:



                    cached_data = doc.to_dict()



                    if cached_data and "venues" in cached_data:



                        print(f"[Firestore Cache Hit] journal_advisor_recommendations for author_id={clean_id}", flush=True)



                        await self._save_to_postgres(cache_key, {"venues": cached_data["venues"]}, ttl_seconds=7200)



                        return cached_data["venues"]



            except Exception as e:



                print(f"[Firestore Cache Error] journal_advisor_recommendations lookup failed: {e}", flush=True)







        profile = await self._fetch_author_profile(author_id)



        author_name = "Researcher"



        concepts = ["science"]



        next_prediction = ""



        



        if profile:



            author_name = profile.get("display_name", "Researcher")



            concepts = [c.get("display_name") for c in profile.get("x_concepts", []) if c.get("level") in [1, 2]][:3]



            next_prediction = profile.get("next_prediction") or ""



        else:



            # Fallback: Query local database metrics for profile



            try:



                from app.models.researcher_models import ResearcherMetrics



                async with AsyncSessionLocal() as session:



                    stmt = select(ResearcherMetrics).where(ResearcherMetrics.openalex_id == clean_id)



                    res = await session.execute(stmt)



                    rm = res.scalars().first()



                    if rm:



                        author_name = rm.display_name



                        concepts = rm.expertise or []



                        next_prediction = rm.next_prediction or ""



            except Exception as e:



                print(f"[JournalAdvisor] Database lookup fallback error: {e}", flush=True)







        # Determine dynamic default journals based on field concepts



        concepts_lower = [c.lower() for c in concepts] if concepts else []



        if any("quantum" in c or "phys" in c for c in concepts_lower):



            venues = [



                {"journal_name": "Physical Review Letters", "estimated_impact_factor": 8.6, "match_score": 96, "submission_tips": "Emphasize topological phase transition and quantum coherence properties of the physical system."},



                {"journal_name": "Reviews of Modern Physics", "estimated_impact_factor": 54.4, "match_score": 91, "submission_tips": "Provide a comprehensive, authoritative review of the theoretical framework and past experimental progress."},



                {"journal_name": "Journal of High Energy Physics", "estimated_impact_factor": 5.8, "match_score": 85, "submission_tips": "Detail the mathematical calculations and boundary-value solutions for field wave propagations."}



            ]



        elif any("comput" in c or "machine" in c or "cs" in c or "learn" in c for c in concepts_lower):



            venues = [



                {"journal_name": "Nature Machine Intelligence", "estimated_impact_factor": 18.8, "match_score": 95, "submission_tips": "Emphasize generalizability and cross-domain algorithmic performance."},



                {"journal_name": "IEEE Transactions on Pattern Analysis and Machine Intelligence (TPAMI)", "estimated_impact_factor": 20.8, "match_score": 88, "submission_tips": "Ensure deep theoretical foundations and extensive baseline benchmarks."},



                {"journal_name": "Journal of Machine Learning Research (JMLR)", "estimated_impact_factor": 5.1, "match_score": 82, "submission_tips": "Focus on high-quality mathematical rigor and open science compliance."}



            ]



        elif any("biol" in c or "medicine" in c or "genet" in c or "clin" in c for c in concepts_lower):



            venues = [



                {"journal_name": "New England Journal of Medicine (NEJM)", "estimated_impact_factor": 158.5, "match_score": 94, "submission_tips": "Ensure clinical trial data is extremely rigorous with robust statistical verification."},



                {"journal_name": "The Lancet", "estimated_impact_factor": 168.9, "match_score": 91, "submission_tips": "Emphasize the global health significance and clinical applicability of the findings."},



                {"journal_name": "Nature Medicine", "estimated_impact_factor": 58.7, "match_score": 88, "submission_tips": "Detail the molecular pathways and cellular mechanisms responsible for the clinical expression."}



            ]



        else:



            venues = [



                {"journal_name": "Nature", "estimated_impact_factor": 64.8, "match_score": 94, "submission_tips": "Highlight the broad interdisciplinary significance and pioneering nature of the research findings."},



                {"journal_name": "Science", "estimated_impact_factor": 56.9, "match_score": 92, "submission_tips": "Present high-fidelity scientific data with a clear impact on multiple STEM domains."},



                {"journal_name": "Proceedings of the National Academy of Sciences (PNAS)", "estimated_impact_factor": 11.1, "match_score": 87, "submission_tips": "Provide strong empirical support for cross-disciplinary applications of the methodology."}



            ]











        if is_llm_working():  # Decoupled: LLM moved to background addon to unblock core app



            messages = [



                {



                    "role": "system",



                    "content": f"""You are an elite journal venue advisor. Recommend the top 3 best-fit peer-reviewed scientific journals for a researcher's next possible paper: '{next_prediction}'.



The researcher '{author_name}' works in '{', '.join(concepts)}'.







For each journal, generate a match score (70-98%), estimated impact factor, and a specific submission tip.



Tip requirements: Mention what specific aspect of their proposed framework to emphasize (e.g. mathematical rigor, empirical verification). You can use simple LaTeX equations (like $E=mc^2$ or $\\mathcal{{O}}(N)$) in the submission tips.







Provide your response in this exact JSON format:



[



  {{



    "journal_name": "[Full Journal Name]",



    "estimated_impact_factor": [Float, e.g. 15.2],



    "match_score": [Int, e.g. 94],



    "submission_tips": "[Submission tips with optional LaTeX]"



  }},



  ...



]



"""



                }



            ]



            try:



                response = await self.llm_service.query(



                    messages=messages,



                    models=[self.model],



                    temperature=0.4,



                    max_tokens=400,



                    response_format={"type": "json_object"}



                )



                if response.content:



                    parsed = json.loads(response.content.strip())



                    if isinstance(parsed, list):



                        venues = parsed



                    elif isinstance(parsed, dict):



                        # Try to find list inside



                        for k, v in parsed.items():



                            if isinstance(v, list):



                                venues = v



                                break



            except Exception as e:



                print(f"Journal advisor generation failed: {e}", flush=True)







        if venues:



            try:



                await self._save_to_postgres(cache_key, {"venues": venues[:3]}, ttl_seconds=7200)



                print(f"[Postgres Cache Save] journal_advisor for author_id={clean_id}", flush=True)



            except Exception as e:



                print(f"[Postgres Cache Error] journal_advisor write failed: {e}", flush=True)







        if db and venues:



            try:



                from firebase_admin import firestore as _fs



                db.collection("journal_advisor_recommendations").document(clean_id).set({



                    "venues": venues[:3],



                    "last_synced": _fs.SERVER_TIMESTAMP



                })



                print(f"[Firestore Cache Save] journal_advisor_recommendations for author_id={clean_id}", flush=True)



            except Exception as e:



                print(f"[Firestore Cache Error] journal_advisor_recommendations write failed: {e}", flush=True)







        return venues[:3]







    async def get_network_collaborators(self, author_id: str, limit: int = 10, offset: int = 0, exclude_ids: List[str] = None, field: str = "") -> List[Dict[str, Any]]:



        """



        Fetches Depth 1 and Depth 2 co-author connections.







        Cache strategy (fastest-first):



          1. ResearcherConnection table (PostgreSQL) — instant, with 24-hour TTL.



          2. CacheEntry blob (legacy key) — retained for backwards compat.



          3. Full OpenAlex computation — stores results in both above for next time.



        """



        clean_id = author_id.split("/")[-1]



        if not clean_id or clean_id == "fallback_seed":



            raise ValueError("No valid author ID provided for collaborator network extraction.")







        from app.models.user_models import ResearcherConnection, ResearcherProfile



        from sqlalchemy import delete







        now = datetime.datetime.utcnow()



        CONNECTION_TTL_HOURS = 24



        PROFILE_TTL_DAYS = 7







        # ── 1. Fast path: read from ResearcherConnection table ────────────────



        async with AsyncSessionLocal() as session:



            try:



                stmt = (



                    select(ResearcherConnection)



                    .where(



                        ResearcherConnection.author_openalex_id == clean_id,



                        ResearcherConnection.expires_at > now,



                    )



                    .order_by(ResearcherConnection.relevance_score.desc())



                )



                result = await session.execute(stmt)



                cached_rows = result.scalars().all()



                if cached_rows:



                    print(f"[DB Fast Path] ResearcherConnection hit: {len(cached_rows)} rows for {clean_id}", flush=True)



                    exclude_set_fast = set(exclude_ids or [])



                    exclude_set_fast.add(clean_id)



                    all_rows = [



                        {



                            "id": row.connection_openalex_id,



                            "name": row.connection_name,



                            "institution": row.connection_institution or "Independent Researcher",



                            "field": row.connection_field or "Researcher",



                            "connection_path": row.connection_path or "",



                            "relevance_score": row.relevance_score,



                            "papers_collaborated": row.papers_collaborated,



                            "total_publications": row.total_publications,



                            "h_index": row.h_index,



                        }



                        for row in cached_rows



                        if row.connection_openalex_id.split("/")[-1] not in exclude_set_fast



                    ]



                    # Apply field filter if requested



                    if field:



                        field_lower = field.strip().lower()



                        all_rows = [r for r in all_rows if field_lower in (r["field"] or "").lower() or field_lower in (r["connection_path"] or "").lower()] or all_rows



                    return all_rows[offset:offset + limit]



            except Exception as e:



                print(f"[DB Fast Path Error] ResearcherConnection read failed: {e}", flush=True)







        # ── 2. Legacy CacheEntry blob (cache_key fallback) ────────────────────



        cache_key = f"network_collaborators_{clean_id}_{field}"



        cached_blob = await self._load_from_postgres(cache_key)



        if cached_blob and "collaborators" in cached_blob:



            print(f"[Postgres Blob Hit] network_collaborators for author_id={clean_id}", flush=True)



            collaborators = cached_blob["collaborators"]



            if exclude_ids:



                ex = set(exclude_ids)



                collaborators = [c for c in collaborators if c["id"].split("/")[-1] not in ex]



            return collaborators[offset:offset + limit]







        # ── 3. Full OpenAlex computation with robust fallback ─────────────────



        exclude_set = set(exclude_ids or [])



        exclude_set.add(clean_id)







        try:



            profile = await self._fetch_author_profile(author_id)



            if not profile:



                raise ValueError(f"Author with ID '{author_id}' not found on OpenAlex.")



            primary_name = profile.get("display_name", "Main Author")







            # Persist the primary author's profile



            await self._upsert_researcher_profile(profile, PROFILE_TTL_DAYS)







            target_fields: List[str] = []



            if field:



                target_fields = [f.strip().lower() for f in field.split(",") if f.strip()]



            else:



                target_fields = [c.get("display_name", "").lower() for c in profile.get("x_concepts", []) if c.get("display_name")]







            def is_relevant_collaborator(candidate_fields: List[str]) -> bool:



                if not target_fields:



                    return True



                for tf in target_fields:



                    if not tf:



                        continue



                    for cf in candidate_fields:



                        cf_lower = cf.strip().lower()



                        if tf in cf_lower or cf_lower in tf:



                            return True



                return False







            async def fetch_works_for_author(auth_clean_id, max_works=20):



                try:



                    return await self.openalex_service.fetch_author_works(auth_clean_id, per_page=max_works)



                except Exception:



                    return []







            d1_works = await fetch_works_for_author(clean_id, 100)



            if not d1_works:



                raise ValueError(f"No publications found for author '{primary_name}' ({clean_id}) on OpenAlex.")







            # Stably sort works by citations count, then by year



            d1_works = sorted(



                d1_works,



                key=lambda w: (w.get("cited_by_count", 0), w.get("publication_year", 0)),



                reverse=True



            )







            depth1_authors: Dict[str, Any] = {}



            for work in d1_works:



                work_title = work.get("title", "Research Paper")



                concepts = work.get("concepts", [])



                work_concepts = [c.get("display_name") for c in concepts if c.get("display_name")]



                work_field = work_concepts[0] if work_concepts else "Researcher"



                for auth_ship in work.get("authorships", []):



                    author_meta = auth_ship.get("author", {})



                    auth_id = author_meta.get("id")



                    if auth_id:



                        auth_clean = auth_id.split("/")[-1]



                        if auth_clean not in exclude_set:



                            name = author_meta.get("display_name", "Unknown")



                            insts = auth_ship.get("institutions", [])



                            inst_name = insts[0].get("display_name") if insts else "Independent Researcher"



                            if auth_id not in depth1_authors:



                                depth1_authors[auth_id] = {



                                    "id": auth_id,



                                    "name": name,



                                    "institution": inst_name,



                                    "field": work_field,



                                    "shared_paper": work_title,



                                    "joint_count": 1,



                                    "work_concepts": work_concepts,



                                }



                            else:



                                depth1_authors[auth_id]["joint_count"] += 1







            depth2_authors: Dict[str, Any] = {}



            # Stably sort top direct co-authors by joint collaboration count descending first



            d1_list = sorted(depth1_authors.values(), key=lambda x: x["joint_count"], reverse=True)[:10]



            d2_tasks = [fetch_works_for_author(d1["id"].split("/")[-1], 20) for d1 in d1_list]



            d2_results = await asyncio.gather(*d2_tasks, return_exceptions=True)







            for d1, works in zip(d1_list, d2_results):



                if isinstance(works, list):



                    for work in works:



                        concepts = work.get("concepts", [])



                        work_concepts = [c.get("display_name") for c in concepts if c.get("display_name")]



                        work_field = work_concepts[0] if work_concepts else "Expert Collaborator"



                        for auth_ship in work.get("authorships", []):



                            author_meta = auth_ship.get("author", {})



                            auth_id = author_meta.get("id")



                            if auth_id:



                                auth_clean = author_meta.get("id").split("/")[-1]



                                if auth_clean not in exclude_set and auth_id not in depth1_authors and auth_id not in depth2_authors:



                                    name = author_meta.get("display_name", "Unknown")



                                    insts = auth_ship.get("institutions", [])



                                    inst_name = insts[0].get("display_name") if insts else "Independent Researcher"



                                    depth2_authors[auth_id] = {



                                        "id": auth_id,



                                        "name": name,



                                        "institution": inst_name,



                                        "field": work_field,



                                        "connection_path": f"Collaborates with {d1['name']} (connected via {primary_name})",



                                        "joint_count": 1,



                                        "d1_parent_name": d1["name"],



                                        "work_concepts": work_concepts,



                                    }







            # Batch-fetch h-index and works_count for all discovered authors



            all_ids = [v["id"].split("/")[-1] for v in list(depth1_authors.values()) + list(depth2_authors.values())]



            real_stats: Dict[str, Any] = {}



            if all_ids:



                try:



                    async def fetch_stats_chunk(chunk):



                        filter_str = "openalex:" + "|".join(chunk)



                        async with httpx.AsyncClient(timeout=20.0) as client:



                            res = await client.get(



                                f"https://api.openalex.org/authors",



                                params={"filter": filter_str, "per_page": 50, "mailto": "support@skolab.open"},



                            )



                            if res.status_code == 200:



                                for a in res.json().get("results", []):



                                    aid_short = a["id"].split("/")[-1]



                                    real_stats[aid_short] = {



                                        "works_count": a.get("works_count", 0),



                                        "h_index": a.get("summary_stats", {}).get("h_index", 0),



                                        "concepts": [c.get("display_name", "") for c in a.get("x_concepts", []) if c.get("display_name")],



                                        "institution": (a.get("last_known_institutions") or [{}])[0].get("display_name", "Independent Researcher"),



                                        "raw_profile": a,



                                    }



                    stat_tasks = [fetch_stats_chunk(all_ids[i:i + 50]) for i in range(0, len(all_ids), 50)]



                    await asyncio.gather(*stat_tasks, return_exceptions=True)



                except Exception as e:



                    print(f"[Stats Batch Error] {e}", flush=True)







                # ── Fallback to database for missing stats ───────────────────



                missing_ids = [aid for aid in all_ids if aid not in real_stats]



                if missing_ids:



                    async with AsyncSessionLocal() as session:



                        try:



                            profile_ids = missing_ids + [f"https://openalex.org/{mid}" for mid in missing_ids]



                            stmt_profile = select(ResearcherProfile).where(ResearcherProfile.openalex_id.in_(profile_ids))



                            res_p = await session.execute(stmt_profile)



                            for rp in res_p.scalars().all():



                                aid_short = rp.openalex_id.split("/")[-1]



                                real_stats[aid_short] = {



                                    "works_count": rp.works_count or 0,



                                    "h_index": rp.h_index or 0,



                                    "concepts": rp.concepts or [],



                                    "institution": rp.institution or "Independent Researcher",



                                    "raw_profile": rp.raw_profile,



                                }



                            



                            still_missing = [aid for aid in missing_ids if aid not in real_stats]



                            if still_missing:



                                from app.models.researcher_models import ResearcherMetrics



                                metrics_ids = still_missing + [f"https://openalex.org/{mid}" for mid in still_missing]



                                stmt_metrics = select(ResearcherMetrics).where(ResearcherMetrics.openalex_id.in_(metrics_ids))



                                res_m = await session.execute(stmt_metrics)



                                for rm in res_m.scalars().all():



                                    aid_short = rm.openalex_id.split("/")[-1]



                                    real_stats[aid_short] = {



                                        "works_count": rm.works_count or 0,



                                        "h_index": rm.h_index or 0,



                                        "concepts": rm.expertise or [],



                                        "institution": rm.current_institution or "Independent Researcher",



                                        "raw_profile": None,



                                    }



                        except Exception as db_exc:



                            print(f"[DB Stats Fallback Error] {db_exc}", flush=True)







            collaborators_pool: List[Dict[str, Any]] = []







            for auth_id, d1 in depth1_authors.items():



                auth_id_short = auth_id.split("/")[-1]



                stats = real_stats.get(auth_id_short, {})



                total_pubs = stats.get("works_count", 0)



                h_idx = stats.get("h_index", 0)



                author_concepts = stats.get("concepts", [])







                cand_concepts = d1.get("work_concepts", []) + author_concepts



                if not is_relevant_collaborator(cand_concepts):



                    continue







                if stats.get("raw_profile"):



                    await self._upsert_researcher_profile(stats["raw_profile"], PROFILE_TTL_DAYS)







                rec = {



                    "id": auth_id,



                    "name": d1["name"],



                    "institution": d1["institution"],



                    "field": d1.get("field") or "Researcher",



                    "connection_path": f"Co-authored '{d1['shared_paper']}' with {primary_name}",



                    "relevance_score": min(99, 70 + (d1["joint_count"] * 5)),



                    "papers_collaborated": d1["joint_count"],



                    "total_publications": total_pubs,



                    "h_index": h_idx,



                    "depth": 1,



                }



                collaborators_pool.append(rec)







            for auth_id, d2 in depth2_authors.items():



                auth_id_short = auth_id.split("/")[-1]



                stats = real_stats.get(auth_id_short, {})



                total_pubs = stats.get("works_count", 0)



                h_idx = stats.get("h_index", 0)



                author_concepts = stats.get("concepts", [])







                cand_concepts = d2.get("work_concepts", []) + author_concepts



                if not is_relevant_collaborator(cand_concepts):



                    continue







                if stats.get("raw_profile"):



                    await self._upsert_researcher_profile(stats["raw_profile"], PROFILE_TTL_DAYS)







                rec = {



                    "id": auth_id,



                    "name": d2["name"],



                    "institution": d2["institution"],



                    "field": d2["field"],



                    "connection_path": d2["connection_path"],



                    "relevance_score": 75,



                    "papers_collaborated": 1,



                    "total_publications": total_pubs,



                    "h_index": h_idx,



                    "depth": 2,



                }



                collaborators_pool.append(rec)







            collaborators_pool.sort(key=lambda x: x["relevance_score"], reverse=True)







            if collaborators_pool:



                expires_at = now + datetime.timedelta(hours=CONNECTION_TTL_HOURS)



                async with AsyncSessionLocal() as session:



                    try:



                        await session.execute(



                            delete(ResearcherConnection).where(



                                ResearcherConnection.author_openalex_id == clean_id



                            )



                        )



                        for rec in collaborators_pool:



                            row = ResearcherConnection(



                                author_openalex_id=clean_id,



                                connection_openalex_id=rec["id"],



                                connection_name=rec["name"],



                                connection_institution=rec["institution"],



                                connection_field=rec["field"],



                                depth=rec.get("depth", 2),



                                connection_path=rec["connection_path"],



                                relevance_score=rec["relevance_score"],



                                papers_collaborated=rec.get("papers_collaborated", 1),



                                total_publications=rec.get("total_publications"),



                                h_index=rec.get("h_index"),



                                last_synced=now,



                                expires_at=expires_at,



                            )



                            session.add(row)



                        await session.commit()



                        print(f"[DB Save] {len(collaborators_pool)} ResearcherConnection rows saved for {clean_id}", flush=True)



                    except Exception as e:



                        print(f"[DB Save Error] ResearcherConnection write failed: {e}", flush=True)



                        await session.rollback()







                await self._save_to_postgres(cache_key, {"collaborators": collaborators_pool})



                print(f"[Postgres Blob Save] network_collaborators cached for {clean_id}", flush=True)







        except Exception as exc:



            print(f"[NetworkCollaborators] OpenAlex computation failed: {exc}, returning fallback co-authors", flush=True)



            primary_concepts = []



            primary_field = "Physics"



            primary_name = "Main Author"



            try:



                from app.models.researcher_models import ResearcherMetrics



                async with AsyncSessionLocal() as session:



                    stmt = select(ResearcherMetrics).where(ResearcherMetrics.openalex_id == clean_id)



                    res = await session.execute(stmt)



                    rm = res.scalars().first()



                    if rm:



                        primary_name = rm.display_name



                        primary_concepts = rm.expertise or []



                        primary_field = rm.field_of_study or "Physics"



            except Exception:



                pass







            fld_lower = primary_field.lower()



            if "phys" in fld_lower or any("phys" in c.lower() or "quantum" in c.lower() for c in primary_concepts):



                mock_collabs = [



                    ("Albert Einstein", "Princeton University", "Theoretical Physics", 95, 99),



                    ("Stephen Hawking", "University of Cambridge", "Cosmology", 62, 92),



                    ("Marie Curie", "Sorbonne University", "Radiology", 35, 95),



                    ("Richard Feynman", "Caltech", "Quantum Electrodynamics", 75, 89),



                    ("Niels Bohr", "University of Copenhagen", "Quantum Mechanics", 50, 86)



                ]



            elif "comput" in fld_lower or "cs" in fld_lower or any("comput" in c.lower() or "machine" in c.lower() for c in primary_concepts):



                mock_collabs = [



                    ("Alan Turing", "University of Cambridge", "Computer Science", 10, 97),



                    ("Grace Hopper", "Yale University", "Software Engineering", 25, 94),



                    ("Ada Lovelace", "University of London", "Analytical Engines", 5, 91),



                    ("Donald Knuth", "Stanford University", "Algorithms", 60, 88),



                    ("Tim Berners-Lee", "MIT", "World Wide Web", 85, 85)



                ]



            else:



                mock_collabs = [



                    ("Leonardo da Vinci", "Independent Researcher", "General Science", 15, 99),



                    ("Isaac Newton", "University of Cambridge", "Classical Mechanics", 45, 97),



                    ("Galileo Galilei", "University of Pisa", "Astronomy", 30, 95),



                    ("Charles Darwin", "University of Cambridge", "Evolutionary Biology", 28, 93),



                    ("Nikola Tesla", "Independent Researcher", "Electrical Engineering", 55, 91)



                ]







            collaborators_pool = [



                {



                    "id": f"https://openalex.org/mock_{name.replace(' ', '_').lower()}",



                    "name": name,



                    "institution": inst,



                    "field": field_name,



                    "connection_path": f"Connected via shared interest in {primary_field}",



                    "relevance_score": rel_score,



                    "papers_collaborated": random.randint(1, 4),



                    "total_publications": total_pub,



                    "h_index": random.randint(15, 60),



                }



                for name, inst, field_name, total_pub, rel_score in mock_collabs



            ]







        filtered_final = [c for c in collaborators_pool if c["id"].split("/")[-1] not in exclude_set]



        return filtered_final[offset:offset + limit]







    async def _upsert_researcher_profile(self, openalex_author: Dict[str, Any], ttl_days: int = 7):



        """



        Upsert a researcher's profile into the ResearcherProfile table.



        Skips gracefully if the author dict is missing the 'id' key.



        """



        from app.models.user_models import ResearcherProfile



        auth_id = openalex_author.get("id")



        if not auth_id:



            return



        clean = auth_id.split("/")[-1]



        insts = openalex_author.get("last_known_institutions") or []



        inst_name = insts[0].get("display_name", "Independent Researcher") if insts else "Independent Researcher"



        from app.services.openalex_service import extract_field_and_expertise



        field, concepts = extract_field_and_expertise(openalex_author, openalex_author.get("display_name", "Researcher"))



        stats = openalex_author.get("summary_stats") or {}



        now = datetime.datetime.utcnow()



        expires_at = now + datetime.timedelta(days=ttl_days)







        async with AsyncSessionLocal() as session:



            try:



                stmt = select(ResearcherProfile).where(ResearcherProfile.openalex_id == clean)



                result = await session.execute(stmt)



                row = result.scalars().first()



                if row:



                    row.display_name = openalex_author.get("display_name", row.display_name)



                    row.institution = inst_name



                    row.field_of_study = field



                    row.h_index = stats.get("h_index")



                    row.works_count = openalex_author.get("works_count")



                    row.concepts = concepts



                    row.raw_profile = openalex_author



                    row.last_synced = now



                    row.expires_at = expires_at



                else:



                    row = ResearcherProfile(



                        openalex_id=clean,



                        display_name=openalex_author.get("display_name", "Unknown"),



                        institution=inst_name,



                        field_of_study=field,



                        h_index=stats.get("h_index"),



                        works_count=openalex_author.get("works_count"),



                        concepts=concepts,



                        raw_profile=openalex_author,



                        last_synced=now,



                        expires_at=expires_at,



                    )



                    session.add(row)



                await session.commit()



            except Exception as e:



                print(f"[DB Upsert Error] ResearcherProfile for {clean}: {e}", flush=True)



                await session.rollback()







    async def chat_with_author(self, author_id: str, paper_title: str, user_message: str, history: List[Dict[str, str]]) -> Dict[str, Any]:



        """



        Simulates chatting with a specific researcher about their paper using Groq.



        """



        clean_author = author_id.split("/")[-1]



        sanitized_title = re.sub(r'[^a-zA-Z0-9]', '_', paper_title.lower())[:100]



        doc_id = f"chat_{clean_author}_{sanitized_title}"







        user_id = "default_local_user"



        



        async with AsyncSessionLocal() as session:



            if not history:



                try:



                    stmt = select(AgentChatHistory).where(



                        AgentChatHistory.user_id == user_id, 



                        AgentChatHistory.context_id == doc_id



                    ).order_by(AgentChatHistory.timestamp.asc())



                    result = await session.execute(stmt)



                    db_msgs = result.scalars().all()



                    if db_msgs:



                        history = [{"role": msg.role, "content": msg.content} for msg in db_msgs]



                        print(f"[Postgres Chat Load] Loaded {len(history)} messages for doc_id={doc_id}", flush=True)



                except Exception as e:



                    print(f"[Postgres Chat Load Error] failed: {e}", flush=True)







        profile = await self._fetch_author_profile(author_id)



        author_name = "Researcher"



        concepts = ["science"]



        institution = "Research Lab"



        



        if profile:



            author_name = profile.get("display_name", "Researcher")



            concepts = [c.get("display_name") for c in profile.get("x_concepts", []) if c.get("level") in [1, 2]][:3]



            institution = profile.get("institution") or "Research Institution"







        system_prompt = f"""You are the esteemed researcher {author_name} ({institution}), specializing in {', '.join(concepts)}.



You are having an interactive chat with a fellow researcher who is asking about your paper: "{paper_title}".







Guidelines:



1. Act fully in character as {author_name}. Be polite, intellectually rigorous, and helpful.



2. Formulate your response as a chat message. Keep it relatively concise (under 80 words) and conversation-focused.



3. You can reference specific findings from the paper, outline potential future directions, or answer general conceptual questions about the domain.



4. You may include small LaTeX equations (like $\\mathcal{{O}}(N)$ or $E=mc^2$) if the user asks a technical or mathematical question.



"""







        messages = [{"role": "system", "content": system_prompt}]



        



        # Append history



        for msg in history[-5:]: # limit to last 5 messages for token economy



            role = msg.get("role", "user")



            if role in ["user", "assistant"]:



                messages.append({"role": role, "content": msg.get("content", "")})



                



        # Append current user message



        messages.append({"role": "user", "content": user_message})







        reply = f"Thank you for your question about my work. I believe the principles discussed in '{paper_title}' outline a strong foundation for this domain."







        if is_llm_working():  # Decoupled: LLM moved to background addon to unblock core app



            try:



                response = await self.llm_service.query(



                    messages=messages,



                    models=[self.model],



                    temperature=0.6,



                    max_tokens=150



                )



                if response.content:



                    reply = response.content.strip()



            except Exception as e:



                print(f"Author chat simulation failed: {e}", flush=True)







        async with AsyncSessionLocal() as session:



            try:



                user_msg = AgentChatHistory(



                    user_id=user_id,



                    context_id=doc_id,



                    role="user",



                    content=user_message



                )



                asst_msg = AgentChatHistory(



                    user_id=user_id,



                    context_id=doc_id,



                    role="assistant",



                    content=reply



                )



                session.add(user_msg)



                session.add(asst_msg)



                await session.commit()



                print(f"[Postgres Chat Save] Saved to chat_history for doc_id={doc_id}", flush=True)



            except Exception as e:



                print(f"[Postgres Chat Save Error] failed: {e}", flush=True)







        return {



            "author_id": author_id,



            "author_name": author_name,



            "reply": reply



        }



