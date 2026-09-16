import base64
from pathlib import Path

import pytest
from fastapi import HTTPException

from app.api.v1.endpoints import colab


def test_compile_reports_missing_worker(monkeypatch):
    monkeypatch.setattr(colab.shutil, "which", lambda _: None)
    with pytest.raises(HTTPException) as exc:
        colab._compile_source("\\documentclass{article}\\begin{document}Hi\\end{document}")
    assert getattr(exc.value, "status_code", None) == 503


def test_compile_returns_bounded_pdf(monkeypatch):
    monkeypatch.setattr(colab.shutil, "which", lambda _: "pdflatex")

    def fake_run(command, **_kwargs):
        output_dir = Path(command[command.index("-output-directory") + 1])
        (output_dir / "main.pdf").write_bytes(b"%PDF-1.7 test")
        return type("Completed", (), {"returncode": 0, "stdout": "Output written", "stderr": ""})()

    monkeypatch.setattr(colab.subprocess, "run", fake_run)
    result = colab._compile_source("\\documentclass{article}\\begin{document}Hi\\end{document}")
    assert result.status == "compiled"
    assert base64.b64decode(result.pdf_base64 or "") == b"%PDF-1.7 test"
