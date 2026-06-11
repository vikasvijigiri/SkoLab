import pytest
import httpx
from unittest.mock import patch, MagicMock
from app.main import app

# Configure HTTPX AsyncClient transport compatibility
try:
    transport = httpx.ASGITransport(app=app)
    client_args = {"transport": transport}
except AttributeError:
    client_args = {"app": app}


@pytest.mark.anyio
async def test_quests_leaderboard():
    """Verify that quests leaderboard endpoint resolves successfully."""
    async with httpx.AsyncClient(base_url="http://testserver", **client_args) as ac:
        response = await ac.get("/api/v1/leaderboard/Physics")
        assert response.status_code == 200
        data = response.json()
        assert isinstance(data, list)


@pytest.mark.anyio
async def test_assistant_professor_roadmap_mocked():
    """Verify assistant professor roadmap generates milestones, checklists, and download URLs."""
    async with httpx.AsyncClient(base_url="http://testserver", **client_args) as ac:
        # Mock OpenAlex response to prevent remote API dependency
        mock_author = {
            "display_name": "Albert Einstein",
            "id": "https://openalex.org/A12345678",
            "works_count": 50,
            "summary_stats": {
                "h_index": 25,
                "cited_by_count": 1500
            }
        }
        with patch("app.services.data.openalex_service.OpenAlexService.fetch_author_by_id", return_value=mock_author):
            response = await ac.get("/api/v1/assistant_professor_roadmap?author_id=A12345678&focus=Physics")
            assert response.status_code == 200
            data = response.json()
            assert data["userName"] == "Albert Einstein"
            assert data["researchFocus"] == "Physics"
            assert "milestones" in data
            assert "checklist" in data
            assert "templates" in data
            assert len(data["templates"]) == 3
            # Check download links are present
            assert "downloads/research_statement_template.md" in data["templates"][0]["downloadUrl"]


@pytest.mark.anyio
async def test_daily_conjecture_mocked():
    """Verify that daily conjecture falls back gracefully or queries correctly."""
    async with httpx.AsyncClient(base_url="http://testserver", **client_args) as ac:
        mock_author = {
            "display_name": "Albert Einstein",
            "id": "https://openalex.org/A12345678",
            "works_count": 50,
            "summary_stats": {
                "h_index": 25,
                "cited_by_count": 1500
            }
        }
        mock_works = [
            {
                "title": "On the Electrodynamics of Moving Bodies",
                "abstract_inverted_index": {"The": [0]}
            }
        ]
        with patch("app.services.data.openalex_service.OpenAlexService.fetch_author_by_id", return_value=mock_author), \
             patch("app.services.data.openalex_service.OpenAlexService.fetch_author_works", return_value=mock_works):
            response = await ac.get("/api/v1/daily_conjecture?author_id=A12345678")
            assert response.status_code == 200
            data = response.json()
            assert "title" in data
            assert "hypothesis" in data
            assert "options" in data


@pytest.mark.anyio
async def test_network_collaborators_mocked():
    """Verify networking hub resolves network collaborators successfully."""
    async with httpx.AsyncClient(base_url="http://testserver", **client_args) as ac:
        mock_collaborators = [
            {
                "name": "Dr. Sarah Jenkins",
                "institution": "Stanford Department of Physics",
                "field": "Physics",
                "match": "94%"
            }
        ]
        with patch("app.services.platform.pipeline_services.PipelineServices.get_network_collaborators", return_value=mock_collaborators):
            response = await ac.get("/api/v1/network_collaborators?author_id=A12345678")
            assert response.status_code == 200
            data = response.json()
            assert isinstance(data, list)
