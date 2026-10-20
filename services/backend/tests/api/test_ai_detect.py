"""The AI-writing detector client (app/services/detector.py) and its
gateway-only routes. The detector service is replaced by httpx.MockTransport:
nothing here calls the network."""

from __future__ import annotations

import json

import httpx
import pytest

from app.services import detector


@pytest.fixture
def configured(monkeypatch):
    monkeypatch.setenv("AI_DETECTOR_URL", "https://detector.example/ ")
    monkeypatch.setenv("AI_DETECTOR_TOKEN", " secret ")


def _client(handler):
    return httpx.AsyncClient(transport=httpx.MockTransport(handler))


async def test_scores_passages_with_the_secret(configured):
    seen = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        return httpx.Response(
            200, json={"probabilities": [0.99, 0.01], "model": "desklib"}
        )

    async with _client(handler) as client:
        probabilities, model = await detector.score(["a", "b"], client=client)
    assert probabilities == [0.99, 0.01] and model == "desklib"
    assert str(seen[0].url) == "https://detector.example/detect"
    assert seen[0].headers["x-detector-token"] == "secret"
    assert json.loads(seen[0].content) == {"passages": ["a", "b"]}


async def test_unconfigured_is_unavailable(monkeypatch):
    monkeypatch.delenv("AI_DETECTOR_URL", raising=False)
    monkeypatch.setenv("AI_DETECTOR_TOKEN", "x")
    assert not detector.configured()
    with pytest.raises(detector.DetectorUnavailable):
        await detector.score(["a"])
    assert await detector.warm() is False


@pytest.mark.parametrize(
    "answer",
    [
        httpx.Response(403, json={"detail": "forbidden"}),
        httpx.Response(500, text="boom"),
        httpx.Response(200, text="not json"),
        httpx.Response(200, json={"probabilities": [0.5]}),  # one passage short
        httpx.Response(200, json={"probabilities": [0.5, 7]}),  # not a probability
        httpx.Response(200, json={"model": "m"}),
    ],
)
async def test_unusable_answers_are_unavailable(configured, answer):
    async with _client(lambda request: answer) as client:
        with pytest.raises(detector.DetectorUnavailable):
            await detector.score(["a", "b"], client=client)


async def test_a_network_failure_is_unavailable(configured):
    def handler(request):
        raise httpx.ConnectError("down")

    async with _client(handler) as client:
        with pytest.raises(detector.DetectorUnavailable):
            await detector.score(["a"], client=client)
        assert await detector.warm(client=client) is False


async def test_warm_asks_for_health(configured):
    seen = []

    def handler(request):
        seen.append(request)
        return httpx.Response(200, json={"status": "ok"})

    async with _client(handler) as client:
        assert await detector.warm(client=client) is True
    assert str(seen[0].url) == "https://detector.example/health"


@pytest.fixture
def gateway(monkeypatch):
    from types import SimpleNamespace

    from app.api import dependencies

    monkeypatch.setattr(
        dependencies, "settings", SimpleNamespace(internal_api_token="gateway-secret")
    )


async def _post(client, path, body=None, token="gateway-secret"):
    return await client.post(path, json=body, headers={"X-Internal-Token": token})


@pytest.mark.usefixtures("gateway")
async def test_routes_admit_only_the_gateway_and_validate(client, monkeypatch):
    async def fake_score(passages):
        return [0.9] * len(passages), "fake"

    async def fake_warm():
        return True

    monkeypatch.setattr(detector, "score", fake_score)
    monkeypatch.setattr(detector, "warm", fake_warm)
    wrong = await _post(client, "/api/v1/ai/detect", {"passages": ["x"]}, token="wrong")
    assert wrong.status_code == 403
    assert (
        await _post(client, "/api/v1/ai/detect/warm", token="wrong")
    ).status_code == 403
    for bad in ([], ["   "], ["x" * 6001], ["x"] * 61):
        answer = await _post(client, "/api/v1/ai/detect", {"passages": bad})
        assert answer.status_code == 422
    answer = await _post(client, "/api/v1/ai/detect", {"passages": ["a", "b"]})
    assert answer.status_code == 200
    assert answer.json() == {"probabilities": [0.9, 0.9], "model": "fake"}
    assert (await _post(client, "/api/v1/ai/detect/warm")).status_code == 204


@pytest.mark.usefixtures("gateway")
async def test_route_answers_503_when_the_detector_is_unavailable(client, monkeypatch):
    monkeypatch.delenv("AI_DETECTOR_URL", raising=False)
    answer = await _post(client, "/api/v1/ai/detect", {"passages": ["text"]})
    assert answer.status_code == 503
