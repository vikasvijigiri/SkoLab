"""Output-validation tests for PredictionService (Horizon + Gap Finder).

Neither the JSON-schema check on Horizon's breakthrough prediction nor the
section-marker check on Gap Finder's next-prediction text existed before --
a static audit (2026-09-11) found both LLM outputs were trusted verbatim.
These tests exercise PredictionService directly (not through the HTTP
route, which only sees the fallback dict shape via mocks in
tests/test_discovery.py) with a mocked LLMService.query, so no live LLM
call/API key is needed. LLMService itself constructs safely with no
network I/O (just reads settings), so real instances are used with only
`.query` swapped for an AsyncMock -- patching the class is unnecessary and
wouldn't work anyway (PredictionService imports it locally inside
__init__, not at module scope).
"""

from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from app.services.ai.prediction_service import PredictionService


def _service_with_mocked_llm(content: str) -> PredictionService:
    service = PredictionService()
    resp = MagicMock()
    resp.content = content
    service.llm_service.query = AsyncMock(return_value=resp)
    service.openalex_service.search_works = AsyncMock(return_value=[])
    return service


VALID_BREAKTHROUGH_JSON = """
{
  "breakthrough_name": "Cryo-Stable Room-Temperature Superconductors",
  "description": "A composite lattice material sustains zero-resistance conduction at ambient temperature by combining two independently-verified doping techniques from the source papers.",
  "scientific_logic": "Paper 1 demonstrated phonon-mediated pairing survives above 200K in a boron-doped lattice; Paper 3 showed a separate strain-engineering technique extends coherence length threefold.",
  "business_application": "Grid operators could deploy lossless long-haul transmission lines, eliminating a well-documented multi-billion-dollar annual loss from resistive dissipation.",
  "time_horizon": "5-7 years",
  "feasibility": "Medium",
  "roadmap_steps": ["Replicate the composite synthesis at lab scale", "Validate coherence length under load", "Pilot a short transmission segment"]
}
"""


@pytest.mark.asyncio
@patch("app.services.ai.prediction_service.is_llm_working", return_value=True)
async def test_predict_next_big_thing_accepts_well_formed_json(_mock_is_llm_working):
    service = _service_with_mocked_llm(VALID_BREAKTHROUGH_JSON)

    result = await service.predict_next_big_thing(field="Superconductivity")

    assert result["breakthrough_name"] == "Cryo-Stable Room-Temperature Superconductors"
    assert result["feasibility"] == "Medium"
    assert result["roadmap_steps"] == [
        "Replicate the composite synthesis at lab scale",
        "Validate coherence length under load",
        "Pilot a short transmission segment",
    ]


@pytest.mark.asyncio
@patch("app.services.ai.prediction_service.is_llm_working", return_value=True)
async def test_predict_next_big_thing_falls_back_on_invalid_feasibility(_mock_is_llm_working):
    """`feasibility` outside High/Medium/Low used to pass straight through --
    the field is a plain `str` on the public schema, so nothing caught it."""
    malformed = VALID_BREAKTHROUGH_JSON.replace('"feasibility": "Medium"', '"feasibility": "Very likely"')
    service = _service_with_mocked_llm(malformed)

    result = await service.predict_next_big_thing(field="Superconductivity")

    # Falls through to the deterministic fallback, not the malformed content.
    assert result["scientific_logic"] == "Failed to generate precise logic due to LLM error."
    assert result["feasibility"] == "Medium"


@pytest.mark.asyncio
@patch("app.services.ai.prediction_service.is_llm_working", return_value=True)
async def test_predict_next_big_thing_falls_back_on_blank_narrative_field(_mock_is_llm_working):
    """An empty `description` used to render as a prediction with a blank
    paragraph -- looks broken, not explicitly a failure."""
    malformed = VALID_BREAKTHROUGH_JSON.replace(
        '"description": "A composite lattice material sustains zero-resistance conduction at ambient temperature by combining two independently-verified doping techniques from the source papers.",',
        '"description": "",',
    )
    service = _service_with_mocked_llm(malformed)

    result = await service.predict_next_big_thing(field="Superconductivity")

    assert result["breakthrough_name"] == "Next-Gen Superconductivity Breakthrough"


@pytest.mark.asyncio
@patch("app.services.ai.prediction_service.is_llm_working", return_value=True)
async def test_predict_next_big_thing_falls_back_on_truncated_json(_mock_is_llm_working):
    service = _service_with_mocked_llm('{"breakthrough_name": "Truncated')

    result = await service.predict_next_big_thing(field="Superconductivity")

    assert result["scientific_logic"] == "Failed to generate precise logic due to LLM error."


@pytest.mark.asyncio
@patch("app.services.ai.prediction_service.is_llm_working", return_value=True)
async def test_predict_next_problem_accepts_well_formed_response(_mock_is_llm_working):
    content = (
        "**Next Frontier**: A working title.\n\n"
        "**Toolkit**: Method A, Method B\n\n"
        "**Logic**: Sentence one. Sentence two."
    )
    service = _service_with_mocked_llm(content)

    result = await service.predict_next_problem(
        author_name="Ada Lovelace",
        expertise=["Computing"],
        works=[{"title": "On the Analytical Engine", "year": 1843, "abstract": "..."}],
    )

    assert "**Next Frontier**" in result


@pytest.mark.asyncio
@patch("app.services.ai.prediction_service.is_llm_working", return_value=True)
async def test_predict_next_problem_raises_on_missing_required_section(_mock_is_llm_working):
    """Previously: a response missing **Toolkit** (truncation, model drift)
    shipped to the caller looking like a complete prediction."""
    content = "**Next Frontier**: A working title.\n\n**Logic**: Sentence one. Sentence two."
    service = _service_with_mocked_llm(content)

    with pytest.raises(Exception, match="Toolkit"):
        await service.predict_next_problem(
            author_name="Ada Lovelace",
            expertise=["Computing"],
            works=[{"title": "On the Analytical Engine", "year": 1843, "abstract": "..."}],
        )
