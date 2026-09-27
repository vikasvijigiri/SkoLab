import base64
import sys
from types import ModuleType
from pathlib import Path

import pytest
from fastapi import HTTPException
from fastapi.security import HTTPAuthorizationCredentials

from app.api.v1.endpoints import colab
from app.api.colab_auth import require_firebase_user
from app.schemas.colab import CompileResponse


def test_compile_reports_missing_worker(monkeypatch):
    monkeypatch.setattr(colab.shutil, "which", lambda _: None)
    with pytest.raises(HTTPException) as exc:
        colab._compile_source(
            "\\documentclass{article}\\begin{document}Hi\\end{document}"
        )
    assert getattr(exc.value, "status_code", None) == 503


def test_compile_returns_bounded_pdf(monkeypatch):
    monkeypatch.setattr(colab.shutil, "which", lambda _: "pdflatex")

    def fake_run(command, cwd, env, timeout):
        assert "-no-shell-escape" in command
        assert env["openin_any"] == "p" and env["openout_any"] == "p"
        (Path(cwd) / "main.pdf").write_bytes(b"%PDF-1.7 test")
        return 0, "Output written", False

    monkeypatch.setattr(colab, "_run_bounded", fake_run)
    result = colab._compile_source(
        r"\documentclass{article}\begin{document}Hi\end{document}"
    )
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


async def test_colab_uses_shared_revocation_aware_firebase_verifier(monkeypatch):
    firebase_module = ModuleType("firebase_admin")
    firebase_module._apps = [object()]
    auth_module = ModuleType("firebase_admin.auth")
    calls: list[tuple[str, bool]] = []

    def verify_id_token(token: str, *, check_revoked: bool):
        calls.append((token, check_revoked))
        return {"uid": "researcher-1"}

    auth_module.verify_id_token = verify_id_token
    firebase_module.auth = auth_module
    monkeypatch.setitem(sys.modules, "firebase_admin", firebase_module)
    monkeypatch.setitem(sys.modules, "firebase_admin.auth", auth_module)

    credentials = HTTPAuthorizationCredentials(
        scheme="Bearer", credentials="test-firebase-token"
    )
    user = await require_firebase_user(credentials)

    assert user == {"uid": "researcher-1"}
    assert calls == [("test-firebase-token", True)]


# ── hardening ───────────────────────────────────────────────────────────────

_DOC = r"\documentclass{article}\begin{document}%s\end{document}"


@pytest.mark.parametrize(
    "snippet",
    [
        r"\input{/etc/passwd}",
        r"\include{secrets}",
        r"\openin5=/proc/self/environ",
        r"\newread\f",
        r"\read5 to \x",
        r"\immediate\write18{id}",
        r"\newwrite\o",
        r"\input|ls",
        r"\verbatiminput{a}",
    ],
)
def test_file_and_shell_primitives_are_refused_before_any_process(monkeypatch, snippet):
    monkeypatch.setattr(colab.shutil, "which", lambda _: "pdflatex")

    def boom(*_a, **_k):
        raise AssertionError("a process must not start for refused source")

    monkeypatch.setattr(colab, "_run_bounded", boom)
    result = colab._compile_source(_DOC % snippet)
    assert result.status == "error"
    assert "Unsupported construct" in result.errors[0]


@pytest.mark.parametrize(
    "snippet",
    [
        "Hello $x^2$",
        r"\usepackage[utf8]{inputenc}",
        r"\includegraphics{fig}",
        r"\section{Read}",
    ],
)
def test_ordinary_constructs_are_not_refused(snippet):
    assert colab._find_forbidden(snippet) is None


def test_sandbox_env_carries_no_server_secrets(monkeypatch, tmp_path):
    for name in (
        "DATABASE_URL",
        "INTERNAL_API_TOKEN",
        "DATABASE_ENCRYPTION_KEY",
        "GROQ_API",
    ):
        monkeypatch.setenv(name, "leak-me")
    env = colab._sandbox_env(tmp_path)
    assert "leak-me" not in " ".join(env.values())
    assert not {"DATABASE_URL", "INTERNAL_API_TOKEN", "GROQ_API"} & set(env)
    assert env["openin_any"] == "p" and env["openout_any"] == "p"
    assert env["HOME"] == str(tmp_path)


async def test_second_concurrent_compile_by_same_user_is_429(client, app):
    from app.api.dependencies import get_verified_user

    app.dependency_overrides[get_verified_user] = lambda: {"uid": "busy-user"}
    colab._active_users.add("busy-user")
    try:
        response = await client.post("/api/v1/colab/compile", json={"latex_source": "x"})
    finally:
        colab._active_users.discard("busy-user")
        app.dependency_overrides.pop(get_verified_user, None)
    assert response.status_code == 429
    assert int(response.headers["Retry-After"]) >= 1


def test_saturated_workers_refuse_fast_with_retry_after(monkeypatch):
    import threading

    monkeypatch.setattr(colab.shutil, "which", lambda _: "pdflatex")
    full = threading.BoundedSemaphore(1)
    full.acquire()
    monkeypatch.setattr(colab, "_slots", full)
    monkeypatch.setattr(colab, "_ADMISSION_WAIT_SECONDS", 0.05)
    with pytest.raises(HTTPException) as exc:
        colab._compile_source(_DOC % "Hi")
    assert exc.value.status_code == 503
    assert exc.value.headers["Retry-After"]


async def test_compile_is_metered_per_user(client, app, monkeypatch):
    from app.api.dependencies import get_verified_user
    from app.core import quota

    async def _none(*_a, **_k):
        return None

    monkeypatch.setenv("USER_QUOTA_ENABLED", "true")
    monkeypatch.setenv("USER_QUOTA_HOURLY_UNITS", "4")  # exactly one compile
    monkeypatch.setattr(quota, "_redis_incr", _none)
    monkeypatch.setattr(quota, "_pg_incr", _none)
    quota._local.clear()
    monkeypatch.setattr(
        colab, "_compile_source", lambda _s: CompileResponse(status="compiled")
    )
    app.dependency_overrides[get_verified_user] = lambda: {"uid": "metered"}
    try:
        first = await client.post("/api/v1/colab/compile", json={"latex_source": "x"})
        second = await client.post("/api/v1/colab/compile", json={"latex_source": "x"})
    finally:
        app.dependency_overrides.pop(get_verified_user, None)
        quota._local.clear()
    assert first.status_code == 200
    assert second.status_code == 429


# ── real pdflatex (skipped where no TeX is installed) ───────────────────────

needs_tex = pytest.mark.skipif(
    colab.shutil.which("pdflatex") is None, reason="pdflatex not installed"
)


@needs_tex
def test_real_compile_reads_no_server_files(tmp_path):
    secret = tmp_path / "secret.txt"
    secret.write_text("SUPER-SECRET-TOKEN-12345")
    result = colab._compile_source(_DOC % (r"\input{" + secret.as_posix() + "}"))
    assert result.status == "error"
    assert "SUPER-SECRET" not in result.log
    assert not result.pdf_base64


@needs_tex
def test_real_compile_produces_a_pdf_without_leaking_paths():
    result = colab._compile_source(_DOC % "Hello, world.")
    assert result.status == "compiled"
    assert base64.b64decode(result.pdf_base64).startswith(b"%PDF-")
    assert "skolab-tex-" not in result.log


@needs_tex
def test_real_runaway_source_is_killed_at_the_timeout(monkeypatch):
    import time

    monkeypatch.setattr(colab, "_COMPILE_TIMEOUT_SECONDS", 2)
    started = time.monotonic()
    result = colab._compile_source(_DOC % r"\def\a{\a}\a")
    assert result.status == "timeout"
    assert time.monotonic() - started < 10
