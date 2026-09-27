"""Every paid / private route must answer 401 to an anonymous caller.

Complements test_auth_posture.py (which pins *which* routes are authed by
dependency-tree inspection) with a black-box check that a request carrying no
token is actually refused before any handler runs.
"""

import pytest

from app.services.platform.connectors import TOOLS_SCHEMA, execute_tool_call

_ANON_ROUTES = [
    ("GET", "/api/v1/papers/summarize", {"params": {"title": "t"}}),
    ("GET", "/api/v1/papers/analyze", {"params": {"title": "t"}}),
    ("GET", "/api/v1/papers/presentation-outline", {"params": {"title": "t"}}),
    ("GET", "/api/v1/papers/semantic-trending", {"params": {"author_id": "A1"}}),
    ("GET", "/api/v1/feed/daily", {"params": {"author_id": "A1"}}),
    ("GET", "/api/v1/feed/conjecture", {"params": {"author_id": "A1"}}),
    ("GET", "/api/v1/feed/industry-opportunities", {"params": {"focus": "AI"}}),
    ("GET", "/api/v1/feed/roadmap", {"params": {"author_id": "A1"}}),
    (
        "GET",
        "/api/v1/collaborator-synergy",
        {"params": {"author_id": "A1", "collaborator_id": "A2"}},
    ),
    ("GET", "/api/v1/match-grants", {"params": {"author_id": "A1"}}),
    ("GET", "/api/v1/journal-advisor", {"params": {"author_id": "A1"}}),
    ("POST", "/api/v1/discovery/predict", {"json": {"field": "physics"}}),
    (
        "POST",
        "/api/v1/discovery/nexus-chat",
        {"json": {"papers": [], "messages": []}},
    ),
    (
        "POST",
        "/api/v1/agent/chat-with-author",
        {
            "json": {
                "author_id": "A1",
                "paper_title": "t",
                "user_message": "hi",
                "history": [],
            }
        },
    ),
]


@pytest.mark.parametrize(("method", "path", "kwargs"), _ANON_ROUTES)
async def test_anonymous_request_is_401(client, app, method, path, kwargs):
    app.dependency_overrides.clear()
    r = await client.request(method, path, **kwargs)
    assert r.status_code == 401, f"{method} {path} -> {r.status_code}: {r.text}"


@pytest.mark.parametrize(("method", "path", "kwargs"), _ANON_ROUTES)
async def test_garbage_bearer_token_is_401(client, app, method, path, kwargs):
    app.dependency_overrides.clear()
    r = await client.request(
        method, path, headers={"Authorization": "Bearer not-a-real-token"}, **kwargs
    )
    assert r.status_code == 401, f"{method} {path} -> {r.status_code}: {r.text}"


def test_agent_exposes_no_filesystem_tools():
    names = {t["function"]["name"] for t in TOOLS_SCHEMA}
    assert not names & {"read_local_file", "list_local_files"}


async def test_filesystem_tool_names_are_not_dispatchable(tmp_path):
    secret = tmp_path / "secret.txt"
    secret.write_text("TOP-SECRET-CONTENT")
    for name, args in (
        ("read_local_file", {"file_path": str(secret)}),
        ("list_local_files", {"directory_path": str(tmp_path)}),
    ):
        out = await execute_tool_call(name, args)
        assert "TOP-SECRET-CONTENT" not in out
        assert "secret.txt" not in out
