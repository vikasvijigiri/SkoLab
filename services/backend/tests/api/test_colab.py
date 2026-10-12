import base64
import os
import sys
from types import ModuleType
from pathlib import Path

import pytest
from fastapi import HTTPException
from fastapi.security import HTTPAuthorizationCredentials

from app.api.v1.endpoints import colab
from app.api.colab_auth import require_firebase_user
from pydantic import ValidationError

from app.schemas.colab import (
    MAX_FILES,
    MAX_FILES_BYTES,
    CompileFile,
    CompileRequest,
    CompileResponse,
    valid_path,
)


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
        lambda _source, _files=None: CompileResponse(status="compiled", pdf_base64="cGRm"),
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
        r"\openin5=/proc/self/environ",
        r"\newread\f",
        r"\read5 to \x",
        r"\immediate\write18{id}",
        r"\newwrite\o",
        r"\input|ls",
        r"\input{|ls}",
        r"\include{|ls}",
        r"\verbatiminput{a}",
        r"\lstinputlisting{a}",
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
        # Project files: kpathsea's openin_any=p confines these reads.
        r"\input{chapters/intro}",
        r"\include{appendix}",
        r"\InputIfFileExists{x}{}{}",
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
        colab, "_compile_source", lambda _s, _f=None: CompileResponse(status="compiled")
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
        colab, "_compile_source", lambda _s, _f=None: CompileResponse(status="compiled")
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
    # \input is allowed for project files; kpathsea refuses the absolute path.
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


# ── project files ───────────────────────────────────────────────────────────


def _b64(data: bytes) -> str:
    return base64.b64encode(data).decode("ascii")


def _files(*pairs):
    return [CompileFile(path=p, content_base64=_b64(d)) for p, d in pairs]


@pytest.mark.parametrize(
    "path",
    [
        "refs.bib",
        "chapters/intro.tex",
        "figs/a b/(1)+x,y-z_w.png",
        "sub/main.tex",
        "a/b/c/d/e/f.tex",
        "a" * 80,
    ],
)
def test_valid_project_paths(path):
    assert valid_path(path)
    CompileRequest(latex_source="x", files=[{"path": path, "content_base64": ""}])


@pytest.mark.parametrize(
    "path",
    [
        "",
        "../x",
        "/etc/x",
        ".hidden",
        "a/.git/config",
        "a//b",
        "a/",
        "a\\b",
        "main.tex",
        "MAIN.TEX",
        "main.pdf",
        "main.log",
        "main.aux",
        "main.bbl",
        "compile.out",
        "a/b/c/d/e/f/g.tex",
        "caf\u00e9.tex",
        "a" * 81,
        "a/" * 100 + "b",
    ],
)
def test_invalid_project_paths_are_422(path):
    assert not valid_path(path)
    with pytest.raises(ValidationError):
        CompileRequest(latex_source="x", files=[{"path": path, "content_base64": ""}])


@pytest.mark.parametrize(
    "files",
    [
        [{"path": "a.tex", "content_base64": "not base64!"}],
        [{"path": "a.tex", "content_base64": "aGk"}],
        [{"path": "a.tex", "content_base64": "aG\nk="}],
        [{"path": "a.tex"}, {"path": "A.tex"}],
        [{"path": "a"}, {"path": "a/b.tex"}],
        [{"path": "a.bin", "content_base64": _b64(bytes(MAX_FILES_BYTES + 1))}],
        [
            {"path": "a.bin", "content_base64": _b64(bytes(MAX_FILES_BYTES // 2 + 1))},
            {"path": "b.bin", "content_base64": _b64(bytes(MAX_FILES_BYTES // 2))},
        ],
        [{"path": f"f{i}.tex"} for i in range(MAX_FILES + 1)],
    ],
)
def test_invalid_project_files_are_rejected(files):
    with pytest.raises(ValidationError):
        CompileRequest(latex_source="x", files=files)


async def test_invalid_project_files_are_422_on_the_route(client, app):
    from app.api.dependencies import get_verified_user

    app.dependency_overrides[get_verified_user] = lambda: {"uid": "careless"}
    try:
        response = await client.post(
            "/api/v1/colab/compile",
            json={"latex_source": "x", "files": [{"path": "../x", "content_base64": ""}]},
        )
    finally:
        app.dependency_overrides.pop(get_verified_user, None)
    assert response.status_code == 422


async def test_route_passes_project_files_to_the_compiler(client, app, monkeypatch):
    from app.api.dependencies import get_verified_user

    seen = {}

    def fake_compile(source, files=None):
        seen["files"] = [(f.path, f.data()) for f in files or []]
        return CompileResponse(status="compiled")

    monkeypatch.setattr(colab, "_compile_source", fake_compile)
    app.dependency_overrides[get_verified_user] = lambda: {"uid": "project-user"}
    try:
        response = await client.post(
            "/api/v1/colab/compile",
            json={
                "latex_source": "x",
                "files": [{"path": "ch/one.tex", "content_base64": _b64(b"hi")}],
            },
        )
    finally:
        app.dependency_overrides.pop(get_verified_user, None)
    assert response.status_code == 200
    assert seen["files"] == [("ch/one.tex", b"hi")]


def test_project_files_are_written_privately_in_subfolders(monkeypatch):
    monkeypatch.setattr(colab.shutil, "which", lambda name: name)
    seen = {}

    def fake_run(command, cwd, env, timeout):
        root = Path(cwd)
        seen["intro"] = (root / "chapters" / "one" / "intro.tex").read_bytes()
        seen["png"] = (root / "figs" / "a.png").read_bytes()
        if os.name == "posix":
            seen["modes"] = {
                p: (root / p).stat().st_mode & 0o777
                for p in ("chapters", "chapters/one", "chapters/one/intro.tex", "main.tex")
            }
        (root / "main.pdf").write_bytes(b"%PDF-1.7")
        return 0, "", False

    monkeypatch.setattr(colab, "_run_bounded", fake_run)
    files = _files(("chapters/one/intro.tex", b"hi"), ("figs/a.png", b"\x89PNG"))
    assert colab._compile_source(_DOC % r"\input{chapters/one/intro}", files).status == "compiled"
    assert seen["intro"] == b"hi" and seen["png"] == b"\x89PNG"
    if os.name == "posix":
        assert seen["modes"] == {
            "chapters": 0o700,
            "chapters/one": 0o700,
            "chapters/one/intro.tex": 0o600,
            "main.tex": 0o600,
        }


def test_write_files_never_overwrites_or_follows_links(tmp_path):
    files = _files(("a.tex", b"x"))
    colab._write_files(tmp_path, files)
    with pytest.raises(OSError):
        colab._write_files(tmp_path, files)
    if os.name == "posix":
        outside = tmp_path / "outside"
        outside.mkdir()
        work = tmp_path / "work"
        work.mkdir()
        (work / "link").symlink_to(outside)
        with pytest.raises(OSError):
            colab._write_files(work, _files(("link/x.tex", b"x")))
        (work / "y.tex").symlink_to(outside / "y.tex")
        with pytest.raises(OSError):
            colab._write_files(work, _files(("y.tex", b"x")))
        assert list(outside.iterdir()) == []


def test_forbidden_primitive_in_an_included_file_is_refused(monkeypatch):
    monkeypatch.setattr(colab.shutil, "which", lambda _: "pdflatex")

    def boom(*_a, **_k):
        raise AssertionError("a process must not start for refused source")

    monkeypatch.setattr(colab, "_run_bounded", boom)
    result = colab._compile_source(
        _DOC % r"\input{sub/evil}", _files(("sub/evil.tex", rb"\newread\f"))
    )
    assert result.status == "error"
    assert "Unsupported construct" in result.errors[0] and "sub/evil.tex" in result.errors[0]


def test_data_files_are_not_scanned_as_tex():
    assert colab._find_forbidden_in_project("x", _files(("data.txt", rb"\newread"))) is None


def test_bibtex_runs_once_then_two_more_passes(monkeypatch):
    monkeypatch.setattr(colab.shutil, "which", lambda name: "/usr/bin/" + name)
    commands = []

    def fake_run(command, cwd, env, timeout):
        commands.append(Path(command[-2] if command[-1] == "main" else command[-1]).name)
        assert env["openin_any"] == "p"
        root = Path(cwd)
        (root / "main.aux").write_text(r"\citation{k}\bibdata{refs}")
        (root / "main.log").write_text("Rerun to get cross-references right.")
        (root / "main.pdf").write_bytes(b"%PDF-1.7")
        return 0, "", False

    monkeypatch.setattr(colab, "_run_bounded", fake_run)
    result = colab._compile_source(_DOC % r"\cite{k}\bibliography{refs}", _files(("refs.bib", b"@misc{k}")))
    assert result.status == "compiled"
    # pdflatex, bibtex, then passes until settled (the rerun hint never
    # clears here, so the 4-pass cap stops it).
    assert commands == ["main.tex", "bibtex", "main.tex", "main.tex", "main.tex"]


def test_no_bibtex_without_bibdata(monkeypatch):
    monkeypatch.setattr(colab.shutil, "which", lambda name: "/usr/bin/" + name)
    commands = []

    def fake_run(command, cwd, env, timeout):
        commands.append(command)
        (Path(cwd) / "main.aux").write_text(r"\relax")
        (Path(cwd) / "main.pdf").write_bytes(b"%PDF-1.7")
        return 0, "", False

    monkeypatch.setattr(colab, "_run_bounded", fake_run)
    assert colab._compile_source(_DOC % "Hi").status == "compiled"
    assert len(commands) == 1


@needs_tex
def test_real_compile_inputs_project_files():
    files = _files(
        ("chapters/intro.tex", rb"\section{Intro}\label{s}See~\ref{s}. \message{INTRO-WAS-READ}"),
        ("appendix.tex", b"Appendix text."),
    )
    result = colab._compile_source(
        _DOC % r"\input{chapters/intro}\include{appendix}", files
    )
    assert result.status == "compiled", result.log
    assert "INTRO-WAS-READ" in result.log


@needs_tex
@pytest.mark.skipif(colab.shutil.which("bibtex") is None, reason="bibtex not installed")
def test_real_compile_runs_bibtex():
    bib = (
        b"@book{knuth, author={Donald Knuth}, title={The {\\TeX}book},"
        b" year={1984}, publisher={Addison-Wesley}}"
    )
    result = colab._compile_source(
        _DOC % r"See \cite{knuth}.\bibliographystyle{plain}\bibliography{bib/refs}",
        _files(("bib/refs.bib", bib)),
    )
    assert result.status == "compiled", result.log
    assert "Citation `knuth' on page 1 undefined" not in result.log
