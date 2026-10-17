"""The AI writing improver's model call (app/services/llm.py) and its
gateway-only route. The model provider is replaced by httpx.MockTransport:
nothing here calls the network."""

from __future__ import annotations

import json

import httpx
import pytest

from app.services import llm


def _chat(content, *, status=200, headers=None, model="llama-3.3-70b-versatile"):
    body = {
        "model": model,
        "choices": [{"message": {"content": content}}],
        "usage": {"prompt_tokens": 120, "completion_tokens": 40},
    }
    return httpx.Response(
        status, json=body if status == 200 else {"error": "x"}, headers=headers or {}
    )


def _client(*responses, seen=None):
    queue = list(responses)

    def handler(request: httpx.Request) -> httpx.Response:
        if seen is not None:
            seen.append(request)
        item = queue.pop(0)
        if isinstance(item, Exception):
            raise item
        return item

    return httpx.AsyncClient(transport=httpx.MockTransport(handler))


@pytest.fixture(autouse=True)
def _key(monkeypatch):
    monkeypatch.setenv("GROQ_API", "gsk_test_key")
    monkeypatch.delenv("GROQ_MODEL", raising=False)


async def test_improve_sends_the_goal_and_placeholders_rule_and_parses_edits():
    seen: list[httpx.Request] = []
    answer = json.dumps(
        {
            "edits": [
                {
                    "original": "The results was clear.",
                    "replacement": "The results were clear.",
                    "reason": "Subject-verb agreement",
                    "category": "grammar",
                },
                {"original": "same", "replacement": "same"},
                {"original": "x", "replacement": "y", "category": "made-up"},
                "not an edit",
                {"original": "", "replacement": "dropped"},
            ]
        }
    )
    async with _client(_chat(answer), seen=seen) as client:
        result = await llm.improve(
            "The results was clear. See ⟦1⟧.", "grammar", client=client
        )
    sent = json.loads(seen[0].content)
    assert seen[0].headers["authorization"] == "Bearer gsk_test_key"
    assert sent["model"] == llm.DEFAULT_MODEL and sent["response_format"] == {
        "type": "json_object"
    }
    assert (
        "⟦1⟧" in sent["messages"][0]["content"]
        and llm.GOALS["grammar"] in sent["messages"][0]["content"]
    )
    assert "The results was clear. See ⟦1⟧." in sent["messages"][1]["content"]
    assert [(e.original, e.replacement, e.category) for e in result.edits] == [
        ("The results was clear.", "The results were clear.", "grammar"),
        ("x", "y", "clarity"),
    ]
    assert (result.prompt_tokens, result.completion_tokens) == (120, 40)


async def test_the_model_and_key_come_from_the_environment(monkeypatch):
    monkeypatch.delenv("GROQ_API")
    monkeypatch.setenv("GROQ_API_KEY", "gsk_other")
    monkeypatch.setenv("GROQ_MODEL", "openai/gpt-oss-120b")
    seen: list[httpx.Request] = []
    async with _client(_chat('{"edits": []}'), seen=seen) as client:
        await llm.improve("Text.", "clarity", client=client)
    assert json.loads(seen[0].content)["model"] == "openai/gpt-oss-120b"
    assert seen[0].headers["authorization"] == "Bearer gsk_other"


async def test_without_a_key_nothing_is_called(monkeypatch):
    monkeypatch.delenv("GROQ_API")
    with pytest.raises(llm.LLMUnavailable) as exc:
        await llm.improve("Text.", "clarity", client=_client())
    assert exc.value.status == 503


@pytest.mark.parametrize(
    "content", ["not json", '{"no_edits": 1}', '["edits"]', '{"edits": "many"}']
)
async def test_unusable_answers_are_bad_output(content):
    async with _client(_chat(content)) as client:
        with pytest.raises(llm.LLMBadOutput):
            await llm.improve("Text.", "clarity", client=client)


async def test_an_answer_without_choices_is_bad_output():
    async with _client(httpx.Response(200, json={"id": "x"})) as client:
        with pytest.raises(llm.LLMBadOutput):
            await llm.improve("Text.", "clarity", client=client)


async def test_a_busy_provider_says_when_to_retry():
    async with _client(_chat("", status=429, headers={"retry-after": "7"})) as client:
        with pytest.raises(llm.LLMUnavailable) as exc:
            await llm.improve("Text.", "clarity", client=client)
    assert (exc.value.status, exc.value.retry_after) == (429, 7)


async def test_a_transient_failure_is_retried_once():
    async with _client(_chat("", status=503), _chat('{"edits": []}')) as client:
        result = await llm.improve("Text.", "clarity", client=client)
    assert result.edits == []
    async with _client(_chat("", status=503), _chat("", status=503)) as client:
        with pytest.raises(llm.LLMUnavailable):
            await llm.improve("Text.", "clarity", client=client)
    async with _client(httpx.ConnectError("down"), _chat('{"edits": []}')) as client:
        assert (await llm.improve("Text.", "clarity", client=client)).edits == []


async def test_a_refused_key_or_a_timeout_is_unavailable():
    async with _client(_chat("", status=401)) as client:
        with pytest.raises(llm.LLMUnavailable):
            await llm.improve("Text.", "clarity", client=client)
    async with _client(httpx.ReadTimeout("slow")) as client:
        with pytest.raises(llm.LLMUnavailable):
            await llm.improve("Text.", "clarity", client=client)
    async with _client(_chat("", status=400)) as client:
        with pytest.raises(llm.LLMUnavailable):
            await llm.improve("Text.", "clarity", client=client)


async def test_at_most_twelve_edits_are_kept():
    many = json.dumps(
        {"edits": [{"original": f"w{n}", "replacement": f"W{n}"} for n in range(30)]}
    )
    async with _client(_chat(many)) as client:
        assert (
            len((await llm.improve("Text.", "clarity", client=client)).edits)
            == llm.MAX_EDITS
        )


@pytest.mark.usefixtures("client")
class TestRoute:
    async def test_only_the_gateway_may_call(self, client, monkeypatch):
        from app.core.config import settings

        monkeypatch.setattr(settings, "internal_api_token", "gateway-secret")
        response = await client.post(
            "/api/v1/ai/improve", json={"text": "Hi.", "goal": "clarity"}
        )
        assert response.status_code == 403
        response = await client.post(
            "/api/v1/ai/improve",
            json={"text": "Hi.", "goal": "clarity"},
            headers={"X-Internal-Token": "wrong"},
        )
        assert response.status_code == 403

    async def test_answers_map_to_statuses(self, client, monkeypatch):
        from app.core.config import settings
        from app.schemas.ai import ImproveResponse

        monkeypatch.setattr(settings, "internal_api_token", "gateway-secret")
        headers = {"X-Internal-Token": "gateway-secret"}

        async def ok(text, goal):
            return ImproveResponse(edits=[], model="m")

        monkeypatch.setattr(llm, "improve", ok)
        response = await client.post(
            "/api/v1/ai/improve",
            json={"text": "Hi.", "goal": "clarity"},
            headers=headers,
        )
        assert response.status_code == 200 and response.json()["model"] == "m"

        async def busy(text, goal):
            raise llm.LLMUnavailable("busy", retry_after=9, status=429)

        monkeypatch.setattr(llm, "improve", busy)
        response = await client.post(
            "/api/v1/ai/improve",
            json={"text": "Hi.", "goal": "clarity"},
            headers=headers,
        )
        assert response.status_code == 429 and response.headers["retry-after"] == "9"

        async def garbled(text, goal):
            raise llm.LLMBadOutput("x")

        monkeypatch.setattr(llm, "improve", garbled)
        response = await client.post(
            "/api/v1/ai/improve",
            json={"text": "Hi.", "goal": "clarity"},
            headers=headers,
        )
        assert response.status_code == 502

        for bad in (
            {"text": "", "goal": "clarity"},
            {"text": "Hi.", "goal": "poetry"},
            {"text": "x" * 6001, "goal": "clarity"},
        ):
            response = await client.post(
                "/api/v1/ai/improve", json=bad, headers=headers
            )
            assert response.status_code == 422, bad
