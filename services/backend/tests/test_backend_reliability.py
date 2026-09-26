import os
import pytest
import httpx
from unittest.mock import patch, MagicMock
from app.main import app
from app.api.dependencies import get_agent_service, get_verified_user

# Configure HTTPX AsyncClient transport compatibility
try:
    transport = httpx.ASGITransport(app=app)
    client_args = {"transport": transport}
except AttributeError:
    client_args = {"app": app}


@pytest.mark.anyio
async def test_upload_document_size_limit():
    """Verify that an authenticated oversized upload fails before parsing."""
    app.dependency_overrides[get_verified_user] = lambda: {"uid": "u1"}
    try:
        async with httpx.AsyncClient(base_url="http://testserver", **client_args) as ac:
            large_content = b"a" * (10 * 1024 * 1024 + 1)
            files = {"file": ("test.pdf", large_content, "application/pdf")}
            response = await ac.post("/api/v1/agent/upload-document", files=files)
            assert response.status_code == 413
            assert "exceeds the 10 MB limit" in response.json()["detail"]
    finally:
        app.dependency_overrides.pop(get_verified_user, None)


@pytest.mark.anyio
async def test_upload_document_mime_validation():
    """Verify that an authenticated upload rejects an unsupported extension."""
    app.dependency_overrides[get_verified_user] = lambda: {"uid": "u1"}
    try:
        async with httpx.AsyncClient(base_url="http://testserver", **client_args) as ac:
            invalid_content = b"some executable code"
            files = {
                "file": ("malicious.exe", invalid_content, "application/octet-stream")
            }
            response = await ac.post("/api/v1/agent/upload-document", files=files)
            assert response.status_code == 415
            assert "Unsupported filename" in response.json()["detail"]
    finally:
        app.dependency_overrides.pop(get_verified_user, None)


@pytest.mark.anyio
async def test_upload_document_success():
    """Verify that a valid PDF file upload works successfully."""
    # Mock agent_service to avoid writing file to disk
    mock_agent_service = MagicMock()

    async def dummy_process(content, filename, content_type, *, user_id):
        return {"id": 123, "filename": filename, "extracted_text": "Sample text"}

    mock_agent_service.process_upload_document = dummy_process

    app.dependency_overrides[get_agent_service] = lambda: mock_agent_service
    app.dependency_overrides[get_verified_user] = lambda: {"uid": "u1"}
    try:
        async with httpx.AsyncClient(base_url="http://testserver", **client_args) as ac:
            valid_content = b"%PDF-1.4\n"
            files = {"file": ("paper.pdf", valid_content, "application/pdf")}
            response = await ac.post("/api/v1/agent/upload-document", files=files)
            assert response.status_code == 200
            data = response.json()
            assert data["id"] == 123
            assert data["filename"] == "paper.pdf"
    finally:
        app.dependency_overrides.clear()


@pytest.mark.anyio
async def test_trace_id_propagation_via_traceparent():
    """Verify that W3C traceparent header is correctly propagated to log context and response headers."""
    async with httpx.AsyncClient(base_url="http://testserver", **client_args) as ac:
        traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
        response = await ac.get("/health", headers={"traceparent": traceparent})
        assert response.status_code == 200
        assert response.headers.get("traceparent") is not None
        assert "4bf92f3577b34da6a3ce929d0e0e4736" in response.headers.get("traceparent")
