"""Task 8 — author routes typed; helper recovery left intact.

`str(e)` route-handler wrappers on the 8 author routes are removed; the ~7
`except Exception` blocks inside `_pg_*` / `fetch_similar_authors` / the
`search_author` Firestore fallback stay (genuine degradation paths).
"""

import pytest

from app.api.dependencies import get_openalex_service, get_pipeline_services
from app.schemas.authors_extra import (
    GrantMatch,
    JournalRecommendation,
    RefreshAuthorResponse,
)


class _FakePipeline:
    async def get_collaborator_synergy(self, *a, **k):
        return {"shared_topics": ["compilers"], "score": 0.7}

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


# test_network_collaborators_* and test_citation_heatmap_parses removed — GET
# /network_collaborators and GET /citation_heatmap both migrated to the Go
# gateway (services/backend-go/internal/author/{network,heatmap}.go), same as
# resolve_email / orbit_metrics before them.


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
