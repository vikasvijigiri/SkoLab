import pytest
from unittest.mock import AsyncMock, MagicMock, patch
from fastapi.testclient import TestClient

from app.main import app
from app.services.industry.industry_academic_service import IndustryAcademicService
from app.schemas.core import UserMemoryProfileResponse


@pytest.fixture
def mock_db():
    session = AsyncMock()
    return session


@pytest.mark.asyncio
@patch("app.services.industry.industry_academic_service.industry_academic_cache")
@patch("app.services.industry.industry_academic_service.UserMemoryService")
@patch("app.services.industry.industry_academic_service.LLMService")
@patch("app.services.industry.industry_academic_service.OpenAlexService")
async def test_get_tieups_cache_hit(
    MockOpenAlex, MockLLM, MockUserMemory, mock_cache, mock_db
):
    # Setup cache hit
    cached_data = {
        "trending": [
            {
                "title": "Quantum Blockchain for FinTech",
                "description": "Secure financial systems using quantum key distribution.",
                "search_queries": ["quantum cryptography finance"],
                "papers": [
                    {
                        "id": "https://openalex.org/W123",
                        "title": "Quantum Money",
                        "doi": "https://doi.org/123",
                        "year": 2024,
                        "journal": "Nature Quantum",
                        "citations": 150,
                        "authors": ["Alice", "Bob"],
                    }
                ],
            }
        ],
        "futuristic": [],
    }
    mock_cache.get = AsyncMock(return_value=cached_data)

    service = IndustryAcademicService(mock_db)
    result = await service.get_tieups("user_123")

    assert result == cached_data
    mock_cache.get.assert_called_once_with("user_123")
    MockUserMemory.return_value.get_user_memory.assert_not_called()


@pytest.mark.asyncio
@patch("app.services.industry.industry_academic_service.industry_academic_cache")
@patch("app.services.industry.industry_academic_service.UserMemoryService")
@patch("app.services.industry.industry_academic_service.LLMService")
@patch("app.services.industry.industry_academic_service.OpenAlexService")
async def test_get_tieups_cache_miss_success(
    MockOpenAlex, MockLLM, MockUserMemory, mock_cache, mock_db
):
    # Setup cache miss
    mock_cache.get = AsyncMock(return_value=None)
    mock_cache.set = AsyncMock()

    # Mock UserMemoryProfile
    mock_memory_profile = UserMemoryProfileResponse(
        user_id="user_123",
        top_topics=["Quantum Computing", "Cryptography"],
        last_active_topic="Quantum Computing",
        researcher_bio="A researcher in quantum mechanics.",
    )
    mock_user_memory_inst = AsyncMock()
    mock_user_memory_inst.get_user_memory = AsyncMock(return_value=mock_memory_profile)
    MockUserMemory.return_value = mock_user_memory_inst

    # Mock LLM Response
    mock_llm_inst = AsyncMock()
    mock_llm_response = MagicMock()
    mock_llm_response.content = """
    {
      "trending": [
        {
          "title": "Quantum Cryptography for Banking",
          "description": "Implementing QKD protocols in commercial financial backbones.",
          "search_queries": ["quantum key distribution banking"]
        }
      ],
      "futuristic": [
        {
          "title": "Deep Space Quantum Teleportation",
          "description": "Visionary quantum relay satellites across Lagrange points.",
          "search_queries": ["satellite quantum communication gravity"]
        }
      ]
    }
    """
    mock_llm_inst.query = AsyncMock(return_value=mock_llm_response)
    MockLLM.return_value = mock_llm_inst

    # Mock OpenAlex paper search
    mock_openalex_inst = AsyncMock()
    mock_paper = {
        "id": "https://openalex.org/W999",
        "title": "Satellite QKD Experiment",
        "doi": "https://doi.org/999",
        "publication_year": 2025,
        "cited_by_count": 42,
        "authorships": [{"author": {"display_name": "Charlie"}}],
        "primary_location": {"source": {"display_name": "Journal of Space Optics"}},
    }
    mock_openalex_inst.search_works = AsyncMock(return_value=[mock_paper])
    MockOpenAlex.return_value = mock_openalex_inst

    service = IndustryAcademicService(mock_db)
    result = await service.get_tieups("user_123")

    # Assertions
    assert "trending" in result
    assert "futuristic" in result
    assert len(result["trending"]) == 1
    assert result["trending"][0]["title"] == "Quantum Cryptography for Banking"
    assert len(result["trending"][0]["papers"]) == 1
    assert result["trending"][0]["papers"][0]["title"] == "Satellite QKD Experiment"

    mock_cache.get.assert_called_once_with("user_123")
    mock_cache.set.assert_called_once_with("user_123", result)


def test_api_endpoint_success(monkeypatch):
    # Mock the service layer directly for the API client test
    mock_tieups = {
        "trending": [
            {
                "title": "AI in Drug Discovery",
                "description": "Generative chemistry models for faster drug matching.",
                "search_queries": ["generative chemistry drugs"],
                "papers": [],
            }
        ],
        "futuristic": [],
    }

    async def mock_get_tieups(self, user_id: str):
        return mock_tieups

    monkeypatch.setattr(IndustryAcademicService, "get_tieups", mock_get_tieups)

    client = TestClient(app)
    response = client.get("/api/v1/industry_academic_tieups?user_id=user_123")

    assert response.status_code == 200
    data = response.json()
    assert len(data["trending"]) == 1
    assert data["trending"][0]["title"] == "AI in Drug Discovery"
