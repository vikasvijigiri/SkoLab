"""Task 5 — agent routes typed; validation errors return the envelope."""

import pytest

from app.api.dependencies import (
    get_agent_service,
    get_optional_user,
    get_pipeline_services,
    get_verified_user,
)
from app.schemas.agent import AgentChatResponse, ChatWithAuthorResponse


class _FakeAgentService:
    async def process_agent_chat(self, req, base_url=None):
        return {"reply": "hello from the fake agent"}

    async def process_upload_document(
        self, content, filename, content_type, *, user_id
    ):
        return {"id": 1, "filename": filename, "extracted_text": "body"}


class _FakePipeline:
    async def chat_with_author(self, **kwargs):
        return {
            "author_id": kwargs.get("author_id", "A1"),
            "author_name": "Ada Lovelace",
            "reply": "synthesised reply",
        }


@pytest.fixture(autouse=True)
def _overrides(app):
    app.dependency_overrides[get_verified_user] = lambda: {"uid": "u1"}
    app.dependency_overrides[get_optional_user] = lambda: {"uid": "u1"}
    app.dependency_overrides[get_agent_service] = lambda: _FakeAgentService()
    app.dependency_overrides[get_pipeline_services] = lambda: _FakePipeline()
    yield
    app.dependency_overrides.clear()


async def test_agent_chat_parses(client):
    r = await client.post("/api/v1/agent/chat", json={"message": "hi"})
    assert r.status_code == 200, r.text
    AgentChatResponse(**r.json())


async def test_agent_chat_rejects_overlong_message(client):
    r = await client.post("/api/v1/agent/chat", json={"message": "x" * 5000})
    assert r.status_code == 422
    body = r.json()
    assert body["code"] == "validation_error"


async def test_upload_document_requires_authentication(client, app):
    app.dependency_overrides.pop(get_verified_user)

    r = await client.post(
        "/api/v1/agent/upload-document",
        files={"file": ("notes.txt", b"private notes", "text/plain")},
    )

    assert r.status_code == 401


async def test_upload_document_accepts_bounded_authenticated_text(client):
    r = await client.post(
        "/api/v1/agent/upload-document",
        files={"file": ("notes.txt", b"research notes", "text/plain")},
    )

    assert r.status_code == 200, r.text
    assert r.json()["filename"] == "notes.txt"


async def test_upload_document_rejects_invalid_pdf_signature(client):
    r = await client.post(
        "/api/v1/agent/upload-document",
        files={"file": ("malformed.pdf", b"not a PDF", "application/pdf")},
    )

    assert r.status_code == 415


async def test_chat_with_author_parses(client):
    r = await client.post(
        "/api/v1/agent/chat-with-author",
        json={
            "author_id": "A1",
            "paper_title": "On Computable Numbers",
            "user_message": "Explain the halting problem.",
            "history": [],
        },
    )
    assert r.status_code == 200, r.text
    ChatWithAuthorResponse(**r.json())
