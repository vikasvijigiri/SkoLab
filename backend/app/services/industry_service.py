import logging
from typing import List, Dict, Optional
from app.services.openalex_service import OpenAlexService

logger = logging.getLogger(__name__)

async def fetch_industry_opportunities(focus: str, openalex_service: Optional[OpenAlexService] = None) -> List[Dict]:
    opportunities = []

    if not openalex_service:
        openalex_service = OpenAlexService()

    # 1. Fetch Real Funders from OpenAlex
    try:
        results = await openalex_service.search_funders(focus, per_page=5)
        for funder in results:
            name = funder.get("display_name", "Unknown Funder")
            homepage = funder.get("homepage_url")
            if not homepage:
                continue  # We only want 100% working links
            
            desc = funder.get("description", f"Major funding organization for {focus} research.")
            
            opportunities.append({
                "id": funder.get("id", ""),
                "type": "FUNDING",
                "title": f"Grant Application: {name}",
                "companyOrFunder": name,
                "tags": [focus, "Grant", "Research Funding"],
                "description": desc,
                "postedAgo": "Open Now",
                "url": homepage
            })
    except Exception as e:
        logger.error(f"Error fetching OpenAlex funders: {e}")


    # 2. Add Curated, High-Profile Real Industry Jobs (with 100% working links)
    curated_jobs = [
        {
            "id": "job_deepmind",
            "type": "JOB",
            "title": "Research Scientist",
            "companyOrFunder": "Google DeepMind",
            "tags": ["AI", "Research", "Industry"],
            "description": "Join Google DeepMind to build advanced AI models and agentic systems.",
            "postedAgo": "Recently",
            "url": "https://deepmind.google/about/careers/"
        },
        {
            "id": "job_openai",
            "type": "JOB",
            "title": "Research Engineer",
            "companyOrFunder": "OpenAI",
            "tags": ["AGI", "Scaling", "Transformers"],
            "description": "Develop state-of-the-art foundation models with the team at OpenAI.",
            "postedAgo": "Recently",
            "url": "https://openai.com/careers"
        },
        {
            "id": "req_innocentive",
            "type": "REQUIREMENT",
            "title": "Open Innovation Challenges",
            "companyOrFunder": "Wazoku (InnoCentive)",
            "tags": ["Bounty", "Open Innovation", "Problem Solving"],
            "description": "Solve real corporate R&D challenges for financial rewards.",
            "postedAgo": "Active",
            "url": "https://www.wazoku.com/wazoku-crowd/"
        }
    ]

    opportunities.extend(curated_jobs)

    return opportunities
