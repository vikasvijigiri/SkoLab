"""
test_openalex_type_filter.py
=============================
Regression test for ``fetch_works_by_concept``'s ``type:article`` filtering
(2026-09 ``/semantic_trending`` audit): OpenAlex's ``/works`` endpoint can
return non-article container entries -- e.g. a journal-issue ``paratext``
record whose ``title`` is the journal's own name ("Fundamenta Mathematicae")
-- unless filtered out. The same class of bug is already documented for
trending papers in ``decisions/0016``, fixed there with a server-side
``type:article`` filter *and* a client-side re-check; this applies the
identical pattern here.

Runs fully offline: the shared httpx client is mocked, no network call.
"""

from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from app.services.data.openalex_service import OpenAlexService


def _fake_response(results):
    resp = MagicMock()
    resp.status_code = 200
    resp.json.return_value = {"results": results}
    return resp


@pytest.mark.asyncio
async def test_fetch_works_by_concept_drops_non_article_types():
    """A paratext (journal front matter) entry slipping through the
    server-side filter must still be dropped client-side."""
    results = [
        {"id": "W1", "title": "A Real Paper", "type": "article"},
        {"id": "W2", "title": "Fundamenta Mathematicae", "type": "paratext"},
    ]
    fake_client = MagicMock()
    fake_client.get = AsyncMock(return_value=_fake_response(results))

    with patch(
        "app.services.data.openalex_service._get_http_client",
        return_value=fake_client,
    ):
        out = await OpenAlexService().fetch_works_by_concept("C123", 2025, 2026)

    assert [w["id"] for w in out] == ["W1"]


@pytest.mark.asyncio
async def test_fetch_works_by_concept_keeps_results_missing_a_type_field():
    """A response that omits `type` entirely must not be dropped -- only a
    confirmed non-article type is excluded."""
    results = [{"id": "W3", "title": "Untyped Result"}]
    fake_client = MagicMock()
    fake_client.get = AsyncMock(return_value=_fake_response(results))

    with patch(
        "app.services.data.openalex_service._get_http_client",
        return_value=fake_client,
    ):
        out = await OpenAlexService().fetch_works_by_concept("C123", 2025, 2026)

    assert [w["id"] for w in out] == ["W3"]


@pytest.mark.asyncio
async def test_fetch_works_by_concept_sends_type_article_filter():
    """The server-side filter itself must request type:article -- the
    client-side check is a backstop, not a replacement (fetching entries
    that get thrown away anyway wastes the request's own result budget)."""
    fake_client = MagicMock()
    fake_client.get = AsyncMock(return_value=_fake_response([]))

    with patch(
        "app.services.data.openalex_service._get_http_client",
        return_value=fake_client,
    ):
        await OpenAlexService().fetch_works_by_concept("C123", 2025, 2026)

    first_call_params = fake_client.get.call_args_list[0].kwargs["params"]
    assert "type:article" in first_call_params["filter"]
