import pytest
import httpx
from unittest.mock import AsyncMock, MagicMock
from app.main import app
from app.api.dependencies import get_prediction_service

try:
    transport = httpx.ASGITransport(app=app)
    client_args = {"transport": transport}
except AttributeError:
    client_args = {"app": app}


@pytest.fixture
def mock_prediction_service():
    mock = MagicMock()
    mock.predict_next_big_thing = AsyncMock(return_value={
        "breakthrough_name": "Test Quantum Super-conductor",
        "description": "A breakthrough in quantum super-conducting magnets.",
        "scientific_logic": "By linking quantum spin states with crystal lattices.",
        "business_application": "Startups can build lossless grid energy transfers.",
        "time_horizon": "5-7 years",
        "feasibility": "Medium",
        "roadmap_steps": ["Step 1", "Step 2"],
        "pioneering_papers": [],
        "latest_papers": [],
    })
    mock.nexus_chat = AsyncMock(return_value="Nexus AI synthesized response text")
    
    app.dependency_overrides[get_prediction_service] = lambda: mock
    yield mock
    app.dependency_overrides.pop(get_prediction_service, None)


@pytest.mark.anyio
async def test_predict_discovery(mock_prediction_service):
    async with httpx.AsyncClient(base_url="http://testserver", **client_args) as ac:
        response = await ac.post(
            "/api/v1/discovery/predict",
            json={"field": "Quantum Superconductivity"}
        )
        assert response.status_code == 200
        data = response.json()
        assert data["breakthrough_name"] == "Test Quantum Super-conductor"
        assert data["time_horizon"] == "5-7 years"
        assert data["feasibility"] == "Medium"
        mock_prediction_service.predict_next_big_thing.assert_called_once_with(
            field="Quantum Superconductivity", focus_area=None
        )


@pytest.mark.anyio
async def test_nexus_chat(mock_prediction_service):
    async with httpx.AsyncClient(base_url="http://testserver", **client_args) as ac:
        response = await ac.post(
            "/api/v1/discovery/nexus-chat",
            json={
                "papers": [{"title": "Paper 1", "abstract": "Test Abstract"}],
                "messages": [{"role": "user", "content": "Hello AI"}]
            }
        )
        assert response.status_code == 200
        data = response.json()
        assert data["content"] == "Nexus AI synthesized response text"
        mock_prediction_service.nexus_chat.assert_called_once()
