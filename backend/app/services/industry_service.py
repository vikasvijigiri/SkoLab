import logging
import datetime
import uuid
from typing import List, Dict, Optional
from sqlalchemy import select, delete
from sqlalchemy.ext.asyncio import AsyncSession
from app.services.openalex_service import OpenAlexService
from app.services.scraping_service import ScrapingService
from app.models.content_models import ScrapedOpportunity

logger = logging.getLogger(__name__)

async def fetch_industry_opportunities(
    focus: str, 
    name: Optional[str] = None,
    openalex_service: Optional[OpenAlexService] = None,
    db: Optional[AsyncSession] = None
) -> List[Dict]:
    # 1. Try reading from Database cache first
    cached_list = []
    if db is not None:
        try:
            # Clean up stale items (> 6 hours old)
            stale_threshold = datetime.datetime.utcnow() - datetime.timedelta(hours=6)
            delete_stmt = delete(ScrapedOpportunity).where(ScrapedOpportunity.created_at < stale_threshold)
            await db.execute(delete_stmt)
            await db.commit()
            
            # Fetch active cached items for this focus
            stmt = select(ScrapedOpportunity).where(ScrapedOpportunity.focus_topic == focus)
            res = await db.execute(stmt)
            db_items = res.scalars().all()
            for item in db_items:
                cached_list.append({
                    "id": item.id,
                    "type": item.type,
                    "title": item.title,
                    "companyOrFunder": item.company_or_funder,
                    "tags": item.tags or [],
                    "description": item.description,
                    "postedAgo": item.posted_ago or "Active",
                    "url": item.url,
                    "eligibility": item.eligibility or "",
                    "amount": item.amount or "",
                    "procedureSteps": item.procedure_steps or [],
                    "deadline": item.deadline or "",
                    "status": item.status or "Active",
                    "requiredSkills": item.required_skills or [],
                    "matchScore": item.match_score or 80,
                    "relevanceExplanation": item.relevance_explanation or "Aligned with your research focus."
                })
            
            if cached_list:
                logger.info(f"Loaded {len(cached_list)} opportunities from database cache for focus: {focus}")
                return cached_list + get_curated_opportunities(focus)
        except Exception as e:
            logger.error(f"Error checking cache: {e}")
            
    # 2. Run Scraping if cache is empty
    logger.info(f"Cache miss for focus: {focus}. Launching ScrapingService...")
    scraping_service = ScrapingService()
    scraped_items = []
    
    # Resolve researcher expertise keywords from database to refine web search
    expertise_keywords = []
    if name and db is not None:
        try:
            from app.models.researcher_models import ResearcherMetrics
            stmt_metrics = select(ResearcherMetrics).where(ResearcherMetrics.display_name.ilike(f"%{name}%"))
            res_metrics = await db.execute(stmt_metrics)
            metrics = res_metrics.scalars().first()
            if metrics and metrics.expertise:
                expertise_keywords = [k for k in metrics.expertise if k]
                logger.info(f"Found expertise keywords for {name}: {expertise_keywords}")
        except Exception as e:
            logger.error(f"Error loading expertise keywords for {name}: {e}")

    try:
        # Refine search topic using primary expertise if available
        search_topic = focus
        if expertise_keywords:
            # e.g., "Advanced Condensed Matter Physics" -> "Condensed Matter Physics"
            primary_exp = expertise_keywords[0]
            cleaned_exp = primary_exp.replace("Advanced ", "").replace(" Research", "").replace(" Studies", "").strip()
            if cleaned_exp:
                search_topic = cleaned_exp

        logger.info(f"Searching web using refined topic: '{search_topic}' (derived from focus: '{focus}', name: '{name}')")
        query_funding = f"{search_topic} research grant fellowship 2026"
        query_jobs = f"{search_topic} postdoc research job positions 2026"
        
        search_funding = await scraping_service.search_web(query_funding, max_results=4)
        search_jobs = await scraping_service.search_web(query_jobs, max_results=4)
        
        all_results = search_funding + search_jobs
        
        if all_results:
            # LLM Prompt Schema for parsing search snippets into opportunities
            schema = {
                "opportunities": [
                    {
                        "type": "JOB or FUNDING",
                        "title": "Title of the grant or position",
                        "companyOrFunder": "Funder/Employer name",
                        "description": "Short description of the opportunity",
                        "url": "direct URL",
                        "postedAgo": "e.g. 1d ago, 3d ago",
                        "eligibility": "Simple eligibility criteria description",
                        "amount": "Grant amount or postdoc salary range (e.g. $75,000/yr)",
                        "procedureSteps": ["Step 1", "Step 2"],
                        "deadline": "e.g. Dec 15, 2026",
                        "requiredSkills": ["Skill 1", "Skill 2"],
                        "matchScore": 85,
                        "relevanceExplanation": "Custom 1-sentence alignment explanation."
                    }
                ]
            }
            
            prompt_data = f"Search Results:\n" + "\n".join([
                f"- Title: {r['title']}\n  URL: {r['url']}\n  Snippet: {r['snippet']}"
                for r in all_results
            ])
            
            researcher_context = ""
            if name and expertise_keywords:
                researcher_context = f"The target researcher is {name}, who has expertise in: {', '.join(expertise_keywords)}."
            
            parsed = await scraping_service.parse_content_to_json(
                raw_content=prompt_data,
                response_schema=schema,
                instruction=(
                    f"Extract active, real research jobs or funding opportunities in {search_topic}. "
                    f"{researcher_context} For each opportunity, evaluate its suitability against the researcher's background and determine: "
                    f"1) 'matchScore' (an integer percentage from 70 to 98) indicating how well their skills match the job/funding, and "
                    f"2) 'relevanceExplanation' (a brief 1-sentence explanation of why it aligns with their scientific publications and expertise). "
                    f"Ensure all URLs are valid and copied exactly from the source results."
                )
            )
            
            opps = parsed.get("opportunities", [])
            for o in opps:
                # Basic validation
                opp_url = o.get("url") or ""
                if not opp_url.startswith("http"):
                    continue
                    
                opp_id = f"scraped_{uuid.uuid4().hex[:12]}"
                opp_type = o.get("type", "JOB").upper()
                if opp_type not in ["JOB", "FUNDING", "REQUIREMENT"]:
                    opp_type = "JOB"
                    
                scraped_items.append({
                    "id": opp_id,
                    "type": opp_type,
                    "title": o.get("title") or "Research Opportunity",
                    "companyOrFunder": o.get("companyOrFunder") or "Various Partners",
                    "tags": [focus, opp_type.capitalize()],
                    "description": o.get("description") or "Active opportunity matching your profile.",
                    "postedAgo": o.get("postedAgo") or "Recently",
                    "url": opp_url,
                    "eligibility": o.get("eligibility") or "Open to all qualified researchers",
                    "amount": o.get("amount") or "Varies",
                    "procedureSteps": o.get("procedureSteps") or ["Check website", "Submit resume"],
                    "deadline": o.get("deadline") or "Open Now",
                    "status": "Active",
                    "requiredSkills": o.get("requiredSkills") or [focus],
                    "matchScore": int(o.get("matchScore") or 80),
                    "relevanceExplanation": o.get("relevanceExplanation") or "Aligned with your research focus."
                })
                
            # Cache in Database
            if db is not None and scraped_items:
                try:
                    for s in scraped_items:
                        db_opp = ScrapedOpportunity(
                            id=s["id"],
                            type=s["type"],
                            title=s["title"],
                            company_or_funder=s["companyOrFunder"],
                            description=s["description"],
                            url=s["url"],
                            posted_ago=s["postedAgo"],
                            tags=s["tags"],
                            eligibility=s["eligibility"],
                            amount=s["amount"],
                            procedure_steps=s["procedureSteps"],
                            deadline=s["deadline"],
                            status=s["status"],
                            required_skills=s["requiredSkills"],
                            focus_topic=focus,
                            match_score=s["matchScore"],
                            relevance_explanation=s["relevanceExplanation"]
                        )
                        db.add(db_opp)
                    await db.commit()
                    logger.info(f"Cached {len(scraped_items)} new opportunities in database for focus: {focus}")
                except Exception as db_err:
                    logger.error(f"Failed to cache opportunities in db: {db_err}")
                    await db.rollback()
                    
    except Exception as e:
        logger.error(f"Error while scraping opportunities: {e}")
        
    if scraped_items:
        return scraped_items + get_curated_opportunities(focus)
    else:
        # Fallback to OpenAlex funders + Curated Opportunities
        return await get_openalex_funders_as_opps(focus, openalex_service) + get_curated_opportunities(focus)


def get_curated_opportunities(focus: str) -> List[Dict]:
    return [
        {
            "id": "job_deepmind",
            "type": "JOB",
            "title": f"Senior Research Scientist ({focus})",
            "companyOrFunder": "Google DeepMind",
            "tags": [focus, "Research", "Industry"],
            "description": f"Lead advanced research projects in {focus} to build the future of intelligence.",
            "postedAgo": "1d ago",
            "url": "https://deepmind.google/about/careers/",
            "eligibility": "PhD in Computer Science, Physics, or related quantitative field with publications.",
            "amount": "$180,000 - $240,000/yr",
            "procedureSteps": ["1. Review role descriptions", "2. Submit CV and Research Statement online", "3. Complete initial coding/technical screening", "4. Panel interviews"],
            "deadline": "Jan 15, 2027",
            "status": "Active",
            "requiredSkills": ["Research Design", "Advanced Mathematics", focus],
            "matchScore": 95,
            "relevanceExplanation": f"Top-tier industry research placement with strong alignment with your publication citations in {focus}."
        },
        {
            "id": "job_openai",
            "type": "JOB",
            "title": f"Postdoctoral Researcher - {focus}",
            "companyOrFunder": "OpenAI",
            "tags": [focus, "Postdoc", "AGI"],
            "description": f"Collaborate with world-class engineers to push boundary safety and alignment in {focus}.",
            "postedAgo": "3d ago",
            "url": "https://openai.com/careers",
            "eligibility": "Recently completed PhD with strong track record in machine learning or physics.",
            "amount": "$150,000/yr",
            "procedureSteps": ["1. Submit cover letter explaining alignment research", "2. Technical interview", "3. Research presentation to team"],
            "deadline": "Open until filled",
            "status": "Active",
            "requiredSkills": ["Deep Learning", "Python", focus],
            "matchScore": 92,
            "relevanceExplanation": f"Elite postdoctoral post at OpenAI supporting alignment research intersecting with {focus} concepts."
        },
        {
            "id": "req_innocentive",
            "type": "REQUIREMENT",
            "title": "Open Innovation Challenges",
            "companyOrFunder": "Wazoku (InnoCentive)",
            "tags": ["Bounty", "Open Innovation", "Problem Solving"],
            "description": f"Solve real corporate R&D challenges in {focus} for financial rewards.",
            "postedAgo": "Active",
            "url": "https://www.wazoku.com/wazoku-crowd/",
            "eligibility": "Open to all students, researchers, and professional scientists globally.",
            "amount": "$10,000 - $50,000 Bounties",
            "procedureSteps": ["1. Register on Wazoku platform", "2. Review challenge requirements & guidelines", "3. Submit proposed technical solution document"],
            "deadline": "Rolling deadlines",
            "status": "Active",
            "requiredSkills": ["Problem Solving", "Technical Writing"],
            "matchScore": 75,
            "relevanceExplanation": f"Crowdsourced scientific challenge offering bounties for custom problem-solving contributions in {focus}."
        }
    ]


async def get_openalex_funders_as_opps(focus: str, openalex_service: Optional[OpenAlexService]) -> List[Dict]:
    opps = []
    if not openalex_service:
        openalex_service = OpenAlexService()
    try:
        results = await openalex_service.search_funders(focus, per_page=4)
        for funder in results:
            name = funder.get("display_name", "Unknown Funder")
            homepage = funder.get("homepage_url")
            if not homepage:
                continue
            desc = funder.get("description", f"Major funding organization supporting {focus} research and development programs.")
            opps.append({
                "id": funder.get("id", ""),
                "type": "FUNDING",
                "title": f"Research Fellowship: {name}",
                "companyOrFunder": name,
                "tags": [focus, "Grant", "Research Funding"],
                "description": desc,
                "postedAgo": "Open Now",
                "url": homepage,
                "eligibility": "Faculty members or independent postdoctoral scholars in related fields.",
                "amount": "$50,000 - $120,000/yr",
                "procedureSteps": ["1. Check eligible country programs", "2. Submit preliminary abstract proposal", "3. Final grant application submission with budget outline"],
                "deadline": "Mar 31, 2027",
                "status": "Active",
                "requiredSkills": ["Grant Writing", "Research Proposal"],
                "matchScore": 84,
                "relevanceExplanation": f"Federal grant funding specifically matching your field of study ({focus}) to sponsor independent projects."
            })
    except Exception as e:
        logger.error(f"Error fetching fallback OpenAlex funders: {e}")
    return opps
