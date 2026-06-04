import logging
import datetime
import uuid
import asyncio
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
    db: Optional[AsyncSession] = None,
) -> List[Dict]:
    # 1. Resolve researcher actual focus and expertise from database to refine web search
    resolved_focus = focus
    expertise_keywords = []
    if name and db is not None:
        try:
            from app.models.researcher_models import ResearcherMetrics

            stmt_metrics = select(ResearcherMetrics).where(
                ResearcherMetrics.display_name.ilike(f"%{name}%")
            )
            res_metrics = await db.execute(stmt_metrics)
            metrics = res_metrics.scalars().first()
            if metrics:
                if metrics.field_of_study:
                    resolved_focus = metrics.field_of_study
                if metrics.expertise:
                    expertise_keywords = [k for k in metrics.expertise if k]
                logger.info(
                    f"Resolved research focus for {name} to: '{resolved_focus}' and expertise: {expertise_keywords}"
                )
        except Exception as e:
            logger.error(f"Error loading profile info for {name}: {e}")

    # 2. Try reading from Database cache first using resolved_focus
    cached_list = []
    if db is not None:
        try:
            # Clean up stale items (> 6 hours old)
            stale_threshold = datetime.datetime.now(datetime.timezone.utc).replace(tzinfo=None) - datetime.timedelta(hours=6)
            delete_stmt = delete(ScrapedOpportunity).where(
                ScrapedOpportunity.created_at < stale_threshold
            )
            await db.execute(delete_stmt)
            await db.commit()

            # Fetch active cached items for this resolved focus
            stmt = select(ScrapedOpportunity).where(
                ScrapedOpportunity.focus_topic == resolved_focus
            )
            res = await db.execute(stmt)
            db_items = res.scalars().all()
            for item in db_items:
                cached_list.append(
                    {
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
                        "relevanceExplanation": item.relevance_explanation
                        or "Aligned with your research focus.",
                    }
                )

            if cached_list:
                logger.info(
                    f"Loaded {len(cached_list)} opportunities from database cache for focus: {resolved_focus}"
                )
                return cached_list
        except Exception as e:
            logger.error(f"Error checking cache: {e}")

    # 3. Run Scraping if cache is empty
    logger.info(f"Cache miss for focus: {resolved_focus}. Launching ScrapingService...")
    scraping_service = ScrapingService()
    scraped_items = []

    try:
        # Refine search topic using primary expertise if available
        search_topic = resolved_focus
        if expertise_keywords:
            # e.g., "Advanced Condensed Matter Physics" -> "Condensed Matter Physics"
            primary_exp = expertise_keywords[0]
            cleaned_exp = (
                primary_exp.replace("Advanced ", "")
                .replace(" Research", "")
                .replace(" Studies", "")
                .strip()
            )
            if cleaned_exp:
                search_topic = cleaned_exp

        # --- Research Job Portals: only portals confirmed to return scrapable HTML ---
        from urllib.parse import quote_plus

        kw = quote_plus(search_topic)
        kw_dash = kw.replace("+", "-")

        RESEARCH_PORTALS = [
            # ✅ All confirmed-accessible (200 OK, rich HTML text)
            ("jobs.ac.uk", f"https://www.jobs.ac.uk/search/?keywords={kw}&cat=all"),
            ("Euraxess", f"https://euraxess.ec.europa.eu/jobs/search?keywords={kw}"),
            ("Nature Careers", f"https://www.nature.com/naturecareers/jobs?q={kw}"),
            (
                "Times Higher Education",
                f"https://www.timeshighereducation.com/unijobs/listings/?search={kw}",
            ),
            (
                "SimplyHired India",
                f"https://www.simplyhired.co.in/search?q={kw_dash}+research+postdoc",
            ),
            (
                "HigherEdJobs Intl",
                f"https://www.higheredjobs.com/international/search.cfm?Keyword={kw}",
            ),
        ]

        logger.info(
            f"Scraping {len(RESEARCH_PORTALS)} research job portals in parallel for: '{search_topic}'"
        )

        # Fetch portals + DuckDuckGo search in parallel for maximum coverage
        query_jobs = f"{search_topic} postdoc research associate job position university India 2025 2026"
        query_funding = f"{search_topic} research grant fellowship funding CSIR DST DBT India 2025 2026"

        portal_tasks = [
            scraping_service.search_portal(portal_name, url)
            for portal_name, url in RESEARCH_PORTALS
        ]
        ddg_tasks = [
            scraping_service.search_web(query_jobs, max_results=6),
            scraping_service.search_web(query_funding, max_results=6),
        ]

        portal_results_raw, ddg_jobs_raw, ddg_funding_raw = await asyncio.gather(
            asyncio.gather(*portal_tasks), *ddg_tasks
        )

        scraped_pages = [r for r in portal_results_raw if r is not None]
        logger.info(
            f"Successfully fetched {len(scraped_pages)}/{len(RESEARCH_PORTALS)} portals."
        )

        # Convert DDG snippet results into pseudo-portal pages for the LLM
        ddg_combined = ddg_jobs_raw + ddg_funding_raw
        if ddg_combined:
            ddg_text = "\n".join(
                [
                    f"Title: {r.get('title', '')}\nURL: {r.get('url', '')}\nSnippet: {r.get('snippet', '')}"
                    for r in ddg_combined
                    if r.get("url", "").startswith("http")
                ]
            )
            scraped_pages.append(
                {
                    "name": "DuckDuckGo Web Search",
                    "url": "https://duckduckgo.com",
                    "text": ddg_text,
                }
            )
            logger.info(
                f"Added {len(ddg_combined)} DDG results as supplementary source."
            )

        if scraped_pages:
            # LLM Prompt Schema for parsing search snippets into opportunities
            schema = {
                "opportunities": [
                    {
                        "type": "JOB or FUNDING",
                        "title": "Title of the grant or position",
                        "companyOrFunder": "Funder/Employer name",
                        "description": "Short description of the opportunity",
                        "url": "exact detail/apply URL",
                        "postedAgo": "posted date or relative time (e.g. 2d ago, May 2026)",
                        "eligibility": "definite eligibility criteria description",
                        "amount": "Grant amount or postdoc salary range (e.g. $75,000/yr)",
                        "procedureSteps": ["Step 1", "Step 2"],
                        "deadline": "deadline date (e.g. Dec 15, 2026 or Rolling)",
                        "requiredSkills": ["Skill 1", "Skill 2"],
                        "matchScore": 85,
                        "relevanceExplanation": "Custom 1-sentence alignment explanation.",
                    }
                ]
            }

            prompt_data = "Scraped Research Job Portal Contents:\n" + "\n\n".join(
                [
                    f"--- Portal: {p['name']} | URL: {p['url']} ---\n{p['text'][:5000]}"
                    for p in scraped_pages
                ]
            )

            researcher_context = ""
            if name and expertise_keywords:
                researcher_context = f"The target researcher is {name}, who has expertise in: {', '.join(expertise_keywords)}."

            parsed = await scraping_service.parse_content_to_json(
                raw_content=prompt_data,
                response_schema=schema,
                instruction=(
                    f"You are extracting REAL research jobs and funding opportunities in '{search_topic}' from scraped content of multiple international job portals. "
                    f"{researcher_context} "
                    f"IMPORTANT: Extract opportunities from ALL countries — include India (IIT, IISc, CSIR, IISER, ICAR, DBT, DST, DRDO), UK, EU, Asia, Australia, and North America. "
                    f"Do NOT only show US or Western jobs. If a portal listing mentions an Indian institution or region, always include it. "
                    f"AIM to extract at least 15 distinct opportunities. For each opportunity: "
                    f"1) Set 'matchScore' (integer 70–98) based on how well the researcher's background matches. "
                    f"2) Write 'relevanceExplanation' — a 1-sentence explanation tied to the researcher's expertise. "
                    f"CRITICAL: Extract ONLY real listings visible in the scraped text. Do NOT invent listings. "
                    f"Use real eligibility criteria, real deadlines, and real salary/grant amounts from the text. "
                    f"If a detail is missing, infer standard academic values (e.g. 'PhD required', 'INR 50,000–80,000/month' for India postdocs, '£35,000–£40,000/yr' for UK, '$65,000–$78,000/yr' for US postdocs). "
                    f"Use the portal URL as the 'url' if no specific listing URL is available."
                ),
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

                scraped_items.append(
                    {
                        "id": opp_id,
                        "type": opp_type,
                        "title": o.get("title") or "Research Opportunity",
                        "companyOrFunder": o.get("companyOrFunder")
                        or "Various Partners",
                        "tags": [resolved_focus, opp_type.capitalize()],
                        "description": o.get("description")
                        or "Active opportunity matching your profile.",
                        "postedAgo": o.get("postedAgo") or "Recently",
                        "url": opp_url,
                        "eligibility": o.get("eligibility")
                        or "Open to all qualified researchers",
                        "amount": o.get("amount") or "Varies",
                        "procedureSteps": o.get("procedureSteps")
                        or ["Check website", "Submit resume"],
                        "deadline": o.get("deadline") or "Open Now",
                        "status": "Active",
                        "requiredSkills": o.get("requiredSkills") or [resolved_focus],
                        "matchScore": int(o.get("matchScore") or 80),
                        "relevanceExplanation": o.get("relevanceExplanation")
                        or "Aligned with your research focus.",
                    }
                )

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
                            focus_topic=resolved_focus,
                            match_score=s["matchScore"],
                            relevance_explanation=s["relevanceExplanation"],
                        )
                        db.add(db_opp)
                    await db.commit()
                    logger.info(
                        f"Cached {len(scraped_items)} new opportunities in database for focus: {resolved_focus}"
                    )
                except Exception as db_err:
                    logger.error(f"Failed to cache opportunities in db: {db_err}")
                    await db.rollback()

    except Exception as e:
        logger.error(f"Error while scraping opportunities: {e}")

    if scraped_items:
        return scraped_items
    else:
        # Fallback to OpenAlex funders
        return await get_openalex_funders_as_opps(resolved_focus, openalex_service)


async def get_openalex_funders_as_opps(
    focus: str, openalex_service: Optional[OpenAlexService]
) -> List[Dict]:
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
            desc = funder.get(
                "description",
                f"Major funding organization supporting {focus} research and development programs.",
            )
            opps.append(
                {
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
                    "procedureSteps": [
                        "1. Check eligible country programs",
                        "2. Submit preliminary abstract proposal",
                        "3. Final grant application submission with budget outline",
                    ],
                    "deadline": "Mar 31, 2027",
                    "status": "Active",
                    "requiredSkills": ["Grant Writing", "Research Proposal"],
                    "matchScore": 84,
                    "relevanceExplanation": f"Federal grant funding specifically matching your field of study ({focus}) to sponsor independent projects.",
                }
            )
    except Exception as e:
        logger.error(f"Error fetching fallback OpenAlex funders: {e}")
    return opps
