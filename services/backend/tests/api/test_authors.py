"""Task 8 — author routes typed; helper recovery left intact.

`str(e)` route-handler wrappers on the 8 author routes are removed; the ~7
`except Exception` blocks inside `_pg_*` / `fetch_similar_authors` / the
`search_author` Firestore fallback stay (genuine degradation paths).
"""

import pytest

from app.api.dependencies import get_openalex_service, get_pipeline_services
from app.schemas.authors_extra import (
    CitationHeatmap,
    GrantMatch,
    JournalRecommendation,
    NetworkCollaborator,
    RefreshAuthorResponse,
)


class _FakePipeline:
    async def get_network_collaborators(self, *a, **k):
        return [
            {
                "id": "A2",
                "name": "Grace Hopper",
                "institution": "USN",
                "field": "CS",
                "connection_path": "co-author",
                "relevance_score": 0.8,
            }
        ]

    async def get_collaborator_synergy(self, *a, **k):
        return {"shared_topics": ["compilers"], "score": 0.7}

    async def get_citation_heatmap(self, *a, **k):
        return {
            "years": [2022, 2023],
            "citations": [10, 20],
            "works": [1, 2],
            "institutional_reach": 3.0,
            "h_index": 2,
        }

    async def match_grants(self, *a, **k):
        return [{"title": "NSF CAREER", "agency": "NSF", "match_score": 0.9}]

    async def get_journal_advisor(self, *a, **k):
        return [{"journal_name": "Nature", "match_score": 0.8}]


class _FakeOpenAlex:
    async def search_authors(self, name, per_page=1):
        return [{"id": "https://openalex.org/A1", "display_name": name}]


@pytest.fixture(autouse=True)
def _overrides(app):
    app.dependency_overrides[get_pipeline_services] = lambda: _FakePipeline()
    app.dependency_overrides[get_openalex_service] = lambda: _FakeOpenAlex()
    yield
    app.dependency_overrides.clear()


async def test_refresh_author_parses(client):
    r = await client.get("/api/v1/refresh_author", params={"name": "Ada Lovelace"})
    assert r.status_code == 200, r.text
    RefreshAuthorResponse(**r.json())


async def test_network_collaborators_is_typed_array_and_bounds_limit(client):
    ok = await client.get(
        "/api/v1/network_collaborators", params={"author_id": "A1", "limit": 5}
    )
    assert ok.status_code == 200, ok.text
    [NetworkCollaborator(**row) for row in ok.json()]

    bad = await client.get(
        "/api/v1/network_collaborators", params={"author_id": "A1", "limit": 5000}
    )
    assert bad.status_code == 422


async def test_citation_heatmap_parses(client):
    r = await client.get("/api/v1/citation_heatmap", params={"author_id": "A1"})
    assert r.status_code == 200, r.text
    CitationHeatmap(**r.json())


async def test_match_grants_and_journal_advisor_are_typed_arrays(client):
    g = await client.get("/api/v1/match_grants", params={"author_id": "A1"})
    assert g.status_code == 200, g.text
    [GrantMatch(**row) for row in g.json()]

    j = await client.get("/api/v1/journal_advisor", params={"author_id": "A1"})
    assert j.status_code == 200, j.text
    [JournalRecommendation(**row) for row in j.json()]


async def test_refresh_author_requires_name(client):
    r = await client.get("/api/v1/refresh_author")
    assert r.status_code == 422
    assert r.json()["code"] == "validation_error"
