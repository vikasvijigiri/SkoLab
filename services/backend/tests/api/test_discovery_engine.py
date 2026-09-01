"""Task 10 — discovery-engine routes typed against the web client shapes."""

import pytest

from app.api.dependencies import get_prediction_service
from app.schemas.discovery import BreakthroughPrediction, NexusChatResponse


class _FakePrediction:
    async def predict_next_big_thing(self, field, focus_area=None, author_id=None):
        return {
            "breakthrough_name": "Room-temp superconductivity",
            "description": "d",
            "scientific_logic": "s",
            "business_application": "b",
            "time_horizon": "5y",
            "feasibility": "Medium",
            "roadmap_steps": ["a", "b"],
            "pioneering_papers": [
                {
                    "id": "W1",
                    "title": "T",
                    "authors": ["A"],
                    "year": 2011,
                    "cited_by_count": 9,
                }
            ],
            "latest_papers": [],
        }

    async def nexus_chat(self, papers, messages):
        return "synthesised answer"


@pytest.fixture(autouse=True)
def _overrides(app):
    app.dependency_overrides[get_prediction_service] = lambda: _FakePrediction()
    yield
    app.dependency_overrides.clear()


async def test_predict_parses(client):
    r = await client.post(
        "/api/v1/discovery/predict", json={"field": "condensed matter physics"}
    )
    assert r.status_code == 200, r.text
    BreakthroughPrediction(**r.json())


async def test_predict_empty_field_is_400_not_500(client):
    r = await client.post("/api/v1/discovery/predict", json={"field": "   "})
    assert r.status_code == 400
    assert r.json()["code"] == "http_400"


async def test_nexus_chat_parses(client):
    r = await client.post(
        "/api/v1/discovery/nexus-chat",
        json={
            "papers": [{"title": "p"}],
            "messages": [{"role": "user", "content": "hi"}],
        },
    )
    assert r.status_code == 200, r.text
    assert NexusChatResponse(**r.json()).content


async def test_nexus_chat_empty_body_is_422(client):
    r = await client.post("/api/v1/discovery/nexus-chat", json={})
    assert r.status_code == 422
    assert r.json()["code"] == "validation_error"
