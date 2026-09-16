import base64
from pathlib import Path

import pytest
from fastapi import HTTPException

from app.api.v1.endpoints import colab
from app.api.colab_auth import require_firebase_user
from app.schemas.colab import CompileResponse


def test_compile_reports_missing_worker(monkeypatch):
    monkeypatch.setattr(colab.shutil, "which", lambda _: None)
    with pytest.raises(HTTPException) as exc:
        colab._compile_source("\\documentclass{article}\\begin{document}Hi\\end{document}")
    assert getattr(exc.value, "status_code", None) == 503


def test_compile_returns_bounded_pdf(monkeypatch):
    monkeypatch.setattr(colab.shutil, "which", lambda _: "pdflatex")

    def fake_run(command, **kwargs):
        assert "-no-shell-escape" in command
        output_dir = Path(kwargs["cwd"])
        (output_dir / "main.pdf").write_bytes(b"%PDF-1.7 test")
        return type("Completed", (), {"returncode": 0, "stdout": "Output written", "stderr": ""})()

    monkeypatch.setattr(colab.subprocess, "run", fake_run)
    result = colab._compile_source("\\documentclass{article}\\begin{document}Hi\\end{document}")
    assert result.status == "compiled"
    assert base64.b64decode(result.pdf_base64 or "") == b"%PDF-1.7 test"


async def test_compile_route_requires_firebase_auth(client):
    response = await client.post(
        "/api/v1/colab/compile",
        json={"latex_source": "\\documentclass{article}"},
    )
    assert response.status_code == 401


async def test_compile_route_returns_typed_response(client, app, monkeypatch):
    app.dependency_overrides[require_firebase_user] = lambda: {"uid": "researcher-1"}
    monkeypatch.setattr(
        colab,
        "_compile_source",
        lambda _source: CompileResponse(status="compiled", pdf_base64="cGRm"),
    )
    try:
        response = await client.post(
            "/api/v1/colab/compile",
            headers={"Authorization": "Bearer test-token"},
            json={"latex_source": "\\documentclass{article}"},
        )
    finally:
        app.dependency_overrides.pop(require_firebase_user, None)
    assert response.status_code == 200
    assert response.json()["status"] == "compiled"
