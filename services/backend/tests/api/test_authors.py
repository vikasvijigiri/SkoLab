"""Author routes: the LLM ones stay, the lookup ones are gone.

``GET /search_author`` and ``GET /refresh_author`` moved to the Go gateway
(``services/backend-go/internal/author/search.go``) — this asserts they no
longer exist here. The teleport *enrichment* worker is an LLM job and stays
Python, now reached via ``POST /api/v1/internal/teleport/{author_id}``.
"""

import pytest

from app.api.dependencies import get_pipeline_services
from app.schemas.authors_extra import GrantMatch, JournalRecommendation


class _FakePipeline:
    async def get_collaborator_synergy(self, *a, **k):
        return {"shared_topics": ["compilers"], "score": 0.7}

    async def match_grants(self, *a, **k):
        return [{"title": "NSF CAREER", "agency": "NSF", "match_score": 0.9}]

    async def get_journal_advisor(self, *a, **k):
        return [{"journal_name": "Nature", "match_score": 0.8}]


@pytest.fixture(autouse=True)
def _overrides(app):
    app.dependency_overrides[get_pipeline_services] = lambda: _FakePipeline()
    yield
    app.dependency_overrides.clear()


# ── the lookup routes are gone ──────────────────────────────────────────────


@pytest.mark.parametrize("path", ["/api/v1/search_author", "/api/v1/refresh_author"])
async def test_lookup_routes_moved_to_go(client, path):
    r = await client.get(path, params={"name": "Ada Lovelace"})
    # FastAPI returns 404 for an unregistered path (405 would mean it still
    # exists under another method).
    assert r.status_code == 404, r.text


# ── LLM author routes still served here ─────────────────────────────────────


async def test_match_grants_and_journal_advisor_are_typed_arrays(client):
    g = await client.get("/api/v1/match_grants", params={"author_id": "A1"})
    assert g.status_code == 200, g.text
    [GrantMatch(**row) for row in g.json()]

    j = await client.get("/api/v1/journal_advisor", params={"author_id": "A1"})
    assert j.status_code == 200, j.text
    [JournalRecommendation(**row) for row in j.json()]


# ── internal teleport handoff (Go gateway → Python worker) ──────────────────


@pytest.fixture
def _stub_teleport(monkeypatch):
    """Replace the real enrichment worker with a recorder (no network)."""
    calls: list[str] = []

    async def _fake(author_id: str) -> None:
        calls.append(author_id)

    monkeypatch.setattr(
        "app.api.v1.endpoints.internal.teleport_researcher", _fake, raising=False
    )
    return calls


async def test_internal_teleport_accepts_and_enqueues(client, _stub_teleport):
    r = await client.post("/api/v1/internal/teleport/A123")
    assert r.status_code == 202, r.text
    body = r.json()
    assert body["author_id"] == "A123"
    # BackgroundTasks run after the response is sent under ASGITransport.
    assert _stub_teleport == ["A123"]


async def test_internal_teleport_rejects_bad_token(client, _stub_teleport, monkeypatch):
    monkeypatch.setenv("INTERNAL_API_TOKEN", "s3cr3t")

    bad = await client.post(
        "/api/v1/internal/teleport/A123", headers={"X-Internal-Token": "wrong"}
    )
    assert bad.status_code == 401, bad.text

    ok = await client.post(
        "/api/v1/internal/teleport/A123", headers={"X-Internal-Token": "s3cr3t"}
    )
    assert ok.status_code == 202, ok.text
    assert _stub_teleport == ["A123"]
