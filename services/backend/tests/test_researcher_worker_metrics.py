"""app/services/data/researcher_worker.py's _compute_researcher_metrics_via_gateway.

Covers the 2026-09-12 no-slop-audit migration: 8 of the teleport worker's 10
researcher metrics moved from a Python duplicate (MetricsService) to a call
against the Go gateway's POST /internal/compute_metrics, with a zero-value
fallback on any failure so a gateway outage degrades this one enrichment
pass instead of crashing the whole worker.
"""

import dataclasses

import httpx
import pytest

import app.core.config as config_module
from app.services.data.researcher_worker import _compute_researcher_metrics_via_gateway

GATEWAY_RESULT = {
    "disruption_score": 0.123,
    "citation_acceleration": 4,
    "future_impact_score": 55.0,
    "semantic_novelty": 0.0,  # not part of Result, but tolerate extra fields
    "interdisciplinary_index": 62.0,
    "policy_patent_score": 0,
    "open_science_score": 75,
    "collaboration_diversity": 41.0,
    "research_consistency": 88.0,
}


def _kwargs(**overrides):
    base = dict(
        n1=40,
        n2=35,
        n3=25,
        yearly_citations=[5, 8, 12],
        early_citations=10,
        journal_score=2.5,
        h_index=15,
        topic_counts={"AI": 3},
        policy_cites=0,
        patent_cites=0,
        has_code=False,
        has_data=False,
        is_open_access=True,
        has_preprint=False,
        countries=["US", "IN"],
    )
    base.update(overrides)
    return base


class _FakeResponse:
    def __init__(self, json_body, status_code=200):
        self._json = json_body
        self.status_code = status_code

    def raise_for_status(self):
        if self.status_code >= 400:
            raise httpx.HTTPStatusError("error", request=None, response=self)

    def json(self):
        return self._json


class _FakeAsyncClient:
    """Records the last call so tests can assert on it."""

    last_url = None
    last_headers = None
    response = _FakeResponse(GATEWAY_RESULT)
    raise_exc = None

    def __init__(self, *args, **kwargs):
        pass

    async def __aenter__(self):
        return self

    async def __aexit__(self, *exc):
        return False

    async def post(self, url, json=None, headers=None):
        type(self).last_url = url
        type(self).last_headers = headers
        if type(self).raise_exc:
            raise type(self).raise_exc
        return type(self).response


@pytest.fixture(autouse=True)
def _reset_fake_client(monkeypatch):
    _FakeAsyncClient.last_url = None
    _FakeAsyncClient.last_headers = None
    _FakeAsyncClient.response = _FakeResponse(GATEWAY_RESULT)
    _FakeAsyncClient.raise_exc = None
    monkeypatch.setattr(httpx, "AsyncClient", _FakeAsyncClient)


@pytest.mark.asyncio
async def test_successful_call_returns_gateway_result():
    result = await _compute_researcher_metrics_via_gateway(**_kwargs())
    assert result == GATEWAY_RESULT
    assert _FakeAsyncClient.last_url.endswith("/internal/compute_metrics")


@pytest.mark.asyncio
async def test_gateway_connection_error_falls_back_to_zero_values():
    _FakeAsyncClient.raise_exc = httpx.ConnectError("connection refused")
    result = await _compute_researcher_metrics_via_gateway(**_kwargs())
    assert result == {
        "disruption_score": 0.0,
        "citation_acceleration": 0,
        "future_impact_score": 0.0,
        "interdisciplinary_index": 0.0,
        "policy_patent_score": 0,
        "open_science_score": 0,
        "collaboration_diversity": 0.0,
        "research_consistency": 0.0,
    }


@pytest.mark.asyncio
async def test_gateway_5xx_falls_back_to_zero_values():
    _FakeAsyncClient.response = _FakeResponse({"error": "boom"}, status_code=500)
    result = await _compute_researcher_metrics_via_gateway(**_kwargs())
    assert result["disruption_score"] == 0.0


@pytest.mark.asyncio
async def test_internal_token_sent_when_configured(monkeypatch):
    # Settings is a frozen dataclass instance held as a module global; patch
    # the module attribute (a fresh `from app.core.config import settings`
    # inside the function under test picks this up), not the instance.
    monkeypatch.setattr(
        config_module,
        "settings",
        dataclasses.replace(config_module.settings, internal_api_token="test-secret"),
    )
    await _compute_researcher_metrics_via_gateway(**_kwargs())
    assert _FakeAsyncClient.last_headers["X-Internal-Token"] == "test-secret"


@pytest.mark.asyncio
async def test_no_internal_token_header_when_unconfigured(monkeypatch):
    monkeypatch.setattr(
        config_module,
        "settings",
        dataclasses.replace(config_module.settings, internal_api_token=""),
    )
    await _compute_researcher_metrics_via_gateway(**_kwargs())
    assert "X-Internal-Token" not in _FakeAsyncClient.last_headers
