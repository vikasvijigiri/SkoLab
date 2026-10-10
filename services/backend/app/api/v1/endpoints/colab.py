"""CoLab LaTeX compile worker.

Untrusted TeX source is code execution in all but name, so this route is
layered defence, not a single control:

1. Gateway only + identity: the call must carry the gateway's
   X-Internal-Token and a verified Firebase user. The gateway charges the
   per-user compile quota before forwarding, so it is not charged again here.
2. Admission control: one compile per user at a time, and a bounded number of
   concurrent compiles per worker; excess load is refused fast (503 +
   Retry-After) instead of queueing unbounded CPU-heavy processes.
3. Source policy (first line, not the last): raw file-I/O primitives are
   refused, in main.tex and every project ``.tex`` file, before any process
   starts. TeX can obfuscate, so this is never relied on alone.
4. Sandboxed process: scrubbed environment (no server secrets), private
   temp dir as cwd/HOME holding main.tex and the project files (dirs 0700,
   files 0600), ``-no-shell-escape``, kpathsea "paranoid" file access
   (``openin_any=p`` / ``openout_any=p``: no absolute paths, no ``..``, no
   dotfiles; this is what confines ``\\input``/``\\include`` to the work dir),
   OS resource limits via ``prlimit`` (CPU, memory, file size, open files),
   own process group killed on timeout. bibtex, when the document has a
   bibliography, runs under the same environment, limits and deadline.
5. Bounded output: PDF size cap, log tail only, temp paths scrubbed from the
   log returned to the client.
"""

import asyncio
import base64
import hashlib
import logging
import os
import re
import shutil
import subprocess
import tempfile
import threading
import time
from pathlib import Path

from fastapi import APIRouter, Depends, HTTPException

from app.api.dependencies import get_verified_user, require_gateway
from app.schemas.colab import CompileFile, CompileRequest, CompileResponse

logger = logging.getLogger("skolab.colab")

router = APIRouter()

_MAX_PDF_BYTES = 8 * 1024 * 1024
_COMPILE_TIMEOUT_SECONDS = 20
# Cross-references, citations and tables of contents settle on a later pass
# (what latexmk does). All passes share the one time budget above.
_MAX_PASSES = 3
# A bibtex run adds a pass: the bibliography lands on the pass after it and
# its citations resolve on the one after that.
_MAX_PASSES_WITH_BIBTEX = 4
_AUX_SCAN_BYTES = 8 * 1024 * 1024
_RERUN_HINT = re.compile(
    rb"Rerun to get|Rerun LaTeX|Please rerun LaTeX|Label\(s\) may have changed|"
    rb"\(rerunfilecheck\)\s+Rerun"
)
_RERUN_SCAN_BYTES = 64 * 1024
_LOG_TAIL_CHARS = 20_000
_ERROR_LINE = re.compile(r"^(.*?):(\d+):\s*(.*)$")

_MAX_CONCURRENT = max(1, int(os.environ.get("COLAB_MAX_CONCURRENT_COMPILES", "2")))
_ADMISSION_WAIT_SECONDS = 5
_RETRY_AFTER_SECONDS = 5

# OS limits applied through prlimit(1): CPU seconds, address space, max file
# written, open files. pdflatex needs well under these for a normal paper.
_PRLIMIT_ARGS = (
    "--cpu=30",
    f"--as={1024 * 1024 * 1024}",
    f"--fsize={64 * 1024 * 1024}",
    "--nofile=256",
)

# A compile is main.tex plus the project's files, so \input and \include are
# allowed: kpathsea's openin_any=p keeps their reads inside the work dir. The
# raw read/write primitives (the file-exfiltration surface) and the pipe form
# stay refused.
_FORBIDDEN_PRIMITIVES = re.compile(
    r"\\(openin|openout|read|readline|write|newread|newwrite|"
    r"verbatiminput|lstinputlisting)(?![a-zA-Z])"
)
_FORBIDDEN_PIPE = re.compile(r"\\(?:input|include|openin|openout)\s*\{?\s*\|")

_slots = threading.BoundedSemaphore(_MAX_CONCURRENT)
_active_users: set[str] = set()
_warned_no_prlimit = False


def _find_forbidden(source: str) -> str | None:
    match = _FORBIDDEN_PIPE.search(source) or _FORBIDDEN_PRIMITIVES.search(source)
    return match.group(0).strip() if match else None


def _find_forbidden_in_project(
    source: str, files: list[CompileFile]
) -> tuple[str, str] | None:
    """The first refused construct and the file it is in, checking main.tex
    and every project .tex file."""
    hit = _find_forbidden(source)
    if hit:
        return hit, "main.tex"
    for item in files:
        if item.path.lower().endswith(".tex"):
            hit = _find_forbidden(item.data().decode("utf-8", errors="replace"))
            if hit:
                return hit, item.path
    return None


def _write_files(work_dir: Path, files: list[CompileFile]) -> None:
    """Write the (already validated) project files under ``work_dir``.

    Folders are created 0700 one segment at a time and must be real
    directories; files are created 0600 with O_EXCL (and O_NOFOLLOW where the
    OS has it), so an existing name or a symlink is an error, never a write
    elsewhere.
    """
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0)
    flags |= getattr(os, "O_BINARY", 0)
    for item in files:
        parts = item.path.split("/")
        parent = work_dir
        for segment in parts[:-1]:
            parent = parent / segment
            try:
                os.mkdir(parent, 0o700)
            except FileExistsError:
                pass
            if parent.is_symlink() or not parent.is_dir():
                raise OSError(f"{parent.name} is not a folder")
        fd = os.open(parent / parts[-1], flags, 0o600)
        with os.fdopen(fd, "wb") as fh:
            fh.write(item.data())


def _sandbox_env(work_dir: Path) -> dict[str, str]:
    """Minimal environment: nothing from the server's own configuration."""
    env = {
        "PATH": os.environ.get("PATH", ""),
        "HOME": str(work_dir),
        "TMPDIR": str(work_dir),
        "TEXMFOUTPUT": str(work_dir),
        "LANG": "C.UTF-8",
        # kpathsea "paranoid": only files under cwd, no dotfiles, no parents.
        "openin_any": "p",
        "openout_any": "p",
        "shell_escape": "f",
    }
    if os.name == "nt":  # dev machines: MiKTeX needs its profile locations
        for name in ("SYSTEMROOT", "USERPROFILE", "APPDATA", "LOCALAPPDATA"):
            if name in os.environ:
                env[name] = os.environ[name]
        env["TEMP"] = env["TMP"] = str(work_dir)
    return env


def _limited(command: list[str]) -> list[str]:
    global _warned_no_prlimit
    prlimit = shutil.which("prlimit") if os.name == "posix" else None
    if prlimit:
        return [prlimit, *_PRLIMIT_ARGS, *command]
    if os.name == "posix" and not _warned_no_prlimit:
        _warned_no_prlimit = True
        logger.warning("prlimit not found — LaTeX compiles run without OS limits")
    return command


def _run_bounded(command, cwd, env, timeout):
    """Run ``command`` in its own process group; kill the whole group on timeout.

    Returns ``(returncode, output, timed_out)``. Output goes to a file so a
    runaway log cannot exhaust memory; only its tail is read back.
    """
    out_path = Path(cwd) / "compile.out"
    with open(out_path, "wb") as sink:
        proc = subprocess.Popen(
            command,
            cwd=cwd,
            env=env,
            stdin=subprocess.DEVNULL,
            stdout=sink,
            stderr=subprocess.STDOUT,
            start_new_session=(os.name == "posix"),
        )
        timed_out = False
        try:
            proc.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            timed_out = True
            if os.name == "posix":
                import signal

                try:
                    os.killpg(proc.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
            else:
                proc.kill()
            proc.wait()
    with open(out_path, "rb") as fh:
        fh.seek(0, os.SEEK_END)
        fh.seek(max(0, fh.tell() - _LOG_TAIL_CHARS))
        tail = fh.read().decode("utf-8", errors="replace")
    return proc.returncode, tail, timed_out


def _compile_source(
    source: str, files: list[CompileFile] | None = None
) -> CompileResponse:
    files = files or []
    engine = shutil.which("pdflatex")
    if not engine:
        raise HTTPException(
            status_code=503, detail="LaTeX compiler is not provisioned on this worker."
        )

    found = _find_forbidden_in_project(source, files)
    if found:
        forbidden, where = found
        location = "" if where == "main.tex" else f" in {where}"
        return CompileResponse(
            status="error",
            errors=[
                f"Unsupported construct '{forbidden}'{location}: file access and "
                "shell-adjacent primitives are disabled in the CoLab compiler."
            ],
        )

    if not _slots.acquire(timeout=_ADMISSION_WAIT_SECONDS):
        raise HTTPException(
            status_code=503,
            detail="Compile workers are busy. Try again shortly.",
            headers={"Retry-After": str(_RETRY_AFTER_SECONDS)},
        )
    try:
        return _compile_in_sandbox(engine, source, files)
    finally:
        _slots.release()


def _needs_rerun(work_dir: Path) -> bool:
    """Whether pdflatex's own log asks for another pass (read from its tail)."""
    try:
        with open(work_dir / "main.log", "rb") as fh:
            fh.seek(0, os.SEEK_END)
            fh.seek(max(0, fh.tell() - _RERUN_SCAN_BYTES))
            return _RERUN_HINT.search(fh.read()) is not None
    except OSError:
        return False


def _has_bibdata(work_dir: Path) -> bool:
    """Whether the first pass asked for a BibTeX bibliography (\\bibliography,
    or biblatex with backend=bibtex, writes \\bibdata to main.aux)."""
    try:
        with open(work_dir / "main.aux", "rb") as fh:
            return b"\\bibdata{" in fh.read(_AUX_SCAN_BYTES)
    except OSError:
        return False


def _compile_in_sandbox(
    engine: str, source: str, files: list[CompileFile] | None = None
) -> CompileResponse:
    # mkdtemp creates the directory 0700.
    with tempfile.TemporaryDirectory(prefix="skolab-tex-") as raw_dir:
        work_dir = Path(raw_dir)
        (work_dir / "main.tex").write_text(source, encoding="utf-8")
        os.chmod(work_dir / "main.tex", 0o600)
        try:
            _write_files(work_dir, files or [])
        except OSError:
            logger.exception("colab: could not write project files")
            return CompileResponse(
                status="error", errors=["Could not write the project files."]
            )
        command = _limited(
            [
                engine,
                "-no-shell-escape",
                "-interaction=nonstopmode",
                "-halt-on-error",
                "-file-line-error",
                "main.tex",
            ]
        )
        deadline = time.monotonic() + _COMPILE_TIMEOUT_SECONDS

        def remaining() -> float:
            return max(deadline - time.monotonic(), 0.1)

        passes, limit, forced, bibtex_ran = 0, _MAX_PASSES, 0, False
        while passes < limit:
            returncode, tail, timed_out = _run_bounded(
                command, work_dir, _sandbox_env(work_dir), remaining()
            )
            passes += 1
            if timed_out or returncode != 0:
                break
            if not bibtex_ran and passes == 1 and _has_bibdata(work_dir):
                bibtex = shutil.which("bibtex")
                if bibtex:
                    bibtex_ran = True
                    _code, bib_tail, timed_out = _run_bounded(
                        _limited([bibtex, "main"]),
                        work_dir,
                        _sandbox_env(work_dir),
                        remaining(),
                    )
                    if timed_out:
                        tail = bib_tail
                        break
                    # bibtex's own exit status is not fatal: its warnings and
                    # errors show as undefined citations in the passes below.
                    limit, forced = _MAX_PASSES_WITH_BIBTEX, 2
                    continue
            if forced:
                forced -= 1
                if forced:
                    continue
            if not _needs_rerun(work_dir):
                break
        # Never hand the client our filesystem layout.
        log = tail.replace(str(work_dir), ".").replace(raw_dir, ".")

        if timed_out:
            return CompileResponse(
                status="timeout",
                log=log,
                errors=[f"Compilation exceeded the {_COMPILE_TIMEOUT_SECONDS} second limit."],
            )

        pdf_path = work_dir / "main.pdf"
        if returncode != 0 or not pdf_path.exists():
            errors = []
            for line in log.splitlines():
                match = _ERROR_LINE.match(line.strip())
                if match:
                    errors.append(f"line {match.group(2)}: {match.group(3)}")
            return CompileResponse(
                status="error",
                log=log,
                errors=errors[:20] or ["LaTeX compilation failed."],
            )

        if pdf_path.stat().st_size > _MAX_PDF_BYTES:
            return CompileResponse(
                status="error", log=log, errors=["Compiled PDF exceeds the 8 MB limit."]
            )
        return CompileResponse(
            status="compiled",
            pdf_base64=base64.b64encode(pdf_path.read_bytes()).decode("ascii"),
            log=log,
        )


@router.post("/colab/compile", response_model=CompileResponse)
async def compile_latex(
    req: CompileRequest,
    _gateway: None = Depends(require_gateway),
    user: dict = Depends(get_verified_user),
) -> CompileResponse:
    """Compile an authenticated researcher's source in an isolated sandbox.

    The subprocess runs off the event loop. See the module docstring for the
    full list of controls; a future queue worker can keep this response
    contract while making compilation asynchronous.
    """
    uid = str(user.get("uid") or "")
    if uid in _active_users:
        raise HTTPException(
            status_code=429,
            detail="You already have a compile in progress.",
            headers={"Retry-After": str(_RETRY_AFTER_SECONDS)},
        )
    _active_users.add(uid)
    started = time.monotonic()
    result = None
    try:
        result = await asyncio.to_thread(_compile_source, req.latex_source, req.files)
        return result
    finally:
        _active_users.discard(uid)
        logger.info(
            "colab.compile user=%s status=%s ms=%d src_bytes=%d files=%d",
            hashlib.sha256(uid.encode()).hexdigest()[:10],
            getattr(result, "status", "exception"),
            int((time.monotonic() - started) * 1000),
            len(req.latex_source),
            len(req.files),
        )
