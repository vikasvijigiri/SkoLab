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


def test_compile_reruns_until_references_settle(monkeypatch):
    monkeypatch.setattr(colab.shutil, "which", lambda _: "pdflatex")
    timeouts = []

    def fake_run(command, cwd, env, timeout):
        timeouts.append(timeout)
        hint = "LaTeX Warning: Label(s) may have changed. Rerun to get cross-references right."
        (Path(cwd) / "main.log").write_text(hint if len(timeouts) == 1 else "done")
        (Path(cwd) / "main.pdf").write_bytes(b"%%PDF-1.7 pass %d" % len(timeouts))
        return 0, "Output written", False

    monkeypatch.setattr(colab, "_run_bounded", fake_run)
    result = colab._compile_source(r"\documentclass{article}\begin{document}\ref{x}\end{document}")
    assert result.status == "compiled"
    assert base64.b64decode(result.pdf_base64 or "") == b"%PDF-1.7 pass 2"
    assert len(timeouts) == 2
    # Both passes share one budget.
    assert timeouts[1] <= timeouts[0] <= colab._COMPILE_TIMEOUT_SECONDS


def test_compile_stops_after_the_last_allowed_pass(monkeypatch):
    monkeypatch.setattr(colab.shutil, "which", lambda _: "pdflatex")
    passes = []

    def fake_run(command, cwd, env, timeout):
        passes.append(timeout)
        (Path(cwd) / "main.log").write_text("Package rerunfilecheck Warning: (rerunfilecheck) Rerun to get outlines right")
        (Path(cwd) / "main.pdf").write_bytes(b"%PDF-1.7")
        return 0, "", False

    monkeypatch.setattr(colab, "_run_bounded", fake_run)
    assert colab._compile_source(_DOC % "Hi").status == "compiled"
    assert len(passes) == colab._MAX_PASSES


def test_compile_does_not_rerun_a_failed_pass(monkeypatch):
    monkeypatch.setattr(colab.shutil, "which", lambda _: "pdflatex")
    passes = []

    def fake_run(command, cwd, env, timeout):
        passes.append(timeout)
        (Path(cwd) / "main.log").write_text("Rerun to get cross-references right")
        return 1, "./main.tex:3: Undefined control sequence.", False

    monkeypatch.setattr(colab, "_run_bounded", fake_run)
    result = colab._compile_source(_DOC % "Hi")
    assert result.status == "error"
    assert result.errors == ["line 3: Undefined control sequence."]
    assert len(passes) == 1


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
    from types import SimpleNamespace

    from app.api import dependencies

    dependencies._user_status.clear()
    firebase_module = ModuleType("firebase_admin")
    firebase_module._apps = [object()]
    auth_module = ModuleType("firebase_admin.auth")
    calls: list[str] = []

    # Signature checked locally; revocation via the cached account status.
    def verify_id_token(token: str):
        calls.append(token)
        return {"uid": "researcher-1", "auth_time": 2_000}

    def get_user(uid: str):
        calls.append("get_user:" + uid)
        return SimpleNamespace(tokens_valid_after_timestamp=1_000_000, disabled=False)

    auth_module.verify_id_token = verify_id_token
    auth_module.get_user = get_user
    firebase_module.auth = auth_module
    monkeypatch.setitem(sys.modules, "firebase_admin", firebase_module)
    monkeypatch.setitem(sys.modules, "firebase_admin.auth", auth_module)

    credentials = HTTPAuthorizationCredentials(
        scheme="Bearer", credentials="test-firebase-token"
    )
    user = await require_firebase_user(credentials)

    assert user["uid"] == "researcher-1"
    assert calls == ["test-firebase-token", "get_user:researcher-1"]


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
        response = await client.post(
            "/api/v1/colab/compile", json={"latex_source": "x"}
        )
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


async def test_compile_is_not_charged_again_behind_the_gateway(client, app, monkeypatch):
    """The gateway charges the compile quota; Python must not charge it twice."""
    from app.api.dependencies import get_verified_user
    from app.core import quota

    async def _none(*_a, **_k):
        return None

    monkeypatch.setenv("USER_QUOTA_ENABLED", "true")
    monkeypatch.setenv("USER_QUOTA_HOURLY_UNITS", "4")  # one compile's worth
    monkeypatch.setattr(quota, "_redis_incr", _none)
    monkeypatch.setattr(quota, "_pg_incr", _none)
    quota._local.clear()
    monkeypatch.setattr(
        colab, "_compile_source", lambda _s: CompileResponse(status="compiled")
    )
    app.dependency_overrides[get_verified_user] = lambda: {"uid": "metered"}
    try:
        responses = [
            await client.post("/api/v1/colab/compile", json={"latex_source": "x"})
            for _ in range(3)
        ]
    finally:
        app.dependency_overrides.pop(get_verified_user, None)
        quota._local.clear()
    assert [r.status_code for r in responses] == [200, 200, 200]


@pytest.mark.parametrize(
    ("header", "expected"),
    [(None, 403), ("wrong-token", 403), ("gateway-secret", 200)],
)
async def test_compile_only_accepts_calls_forwarded_by_the_gateway(
    client, app, monkeypatch, header, expected
):
    from types import SimpleNamespace

    from app.api import dependencies
    from app.api.dependencies import get_verified_user

    # Settings is a frozen dataclass: swap the reference the check reads.
    monkeypatch.setattr(
        dependencies, "settings", SimpleNamespace(internal_api_token="gateway-secret")
    )
    monkeypatch.setattr(
        colab, "_compile_source", lambda _s: CompileResponse(status="compiled")
    )
    app.dependency_overrides[get_verified_user] = lambda: {"uid": "direct-caller"}
    try:
        response = await client.post(
            "/api/v1/colab/compile",
            json={"latex_source": "x"},
            headers={"X-Internal-Token": header} if header else {},
        )
    finally:
        app.dependency_overrides.pop(get_verified_user, None)
    assert response.status_code == expected


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


# The keyword denylist is a fast first refusal, not the security boundary:
# TeX can spell any primitive without naming it (\csname, catcodes). These
# sources slip past it on purpose, so the test proves the real controls --
# kpathsea's paranoid openin/openout and -no-shell-escape -- still hold.
_BYPASSES = {
    "csname absolute read": r"\csname input\endcsname{%(secret)s}",
    "catcode absolute read": r"\catcode`\@=0 @input %(secret)s ",
    "csname parent read": r"\csname input\endcsname{../../../../../../..%(secret)s}",
    "csname pipe read": r"\csname input\endcsname{|cat %(secret)s}",
    "csname shell escape": r"\immediate\csname write\endcsname18{touch %(marker)s}",
    "csname absolute write": (
        r"\csname newwrite\endcsname\f\immediate\csname openout\endcsname\f=%(marker)s.tex "
        r"\immediate\csname write\endcsname\f{x}\immediate\closeout\f "
    ),
}


@needs_tex
@pytest.mark.parametrize("name", sorted(_BYPASSES))
def test_real_compile_contains_denylist_bypasses(tmp_path, name):
    secret = tmp_path / "secret.txt"
    # Reading the file prints the marker into the log (PDFs are compressed).
    secret.write_text(r"\message{SUPER-SECRET-TOKEN-12345}")
    marker = tmp_path / "pwned"
    body = _BYPASSES[name] % {"secret": secret.as_posix(), "marker": marker.as_posix()}
    source = _DOC % (body + "x")
    assert colab._find_forbidden(source) is None, "bypass no longer bypasses; rewrite it"
    result = colab._compile_source(source)
    pdf = base64.b64decode(result.pdf_base64 or "")
    assert "SUPER-SECRET" not in (result.log or "") and b"SUPER-SECRET" not in pdf
    assert not marker.exists() and not (tmp_path / "pwned.tex").exists()


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
