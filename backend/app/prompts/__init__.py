# app/prompts package

from .agent_prompts import (
    AGENT_BASE_PROMPT,
    AGENT_SUMMARY_PROMPT,
    AGENT_SUMMARY_PERSONA,
    AGENT_FINAL_TURN_PROMPT,
)
from .summarization_prompts import RESEARCH_INTELLIGENCE_SYSTEM_PROMPT
from .prediction_prompts import PREDICTION_SYSTEM_PROMPT
from .scraping_prompts import JSON_PARSER_SYSTEM_PROMPT
from .pipeline_prompts import DAILY_FEED_ADVISOR_PROMPT_TEMPLATE
