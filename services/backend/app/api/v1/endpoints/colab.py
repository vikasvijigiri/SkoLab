import asyncio
import base64
import re
import shutil
import subprocess
import tempfile
from pathlib import Path

from fastapi import APIRouter, Depends, HTTPException

from app.api.colab_auth import require_firebase_user
from app.schemas.colab import CompileRequest, CompileResponse

router = APIRouter()
_MAX_PDF_BYTES = 8 * 1024 * 1024
_COMPILE_TIMEOUT_SECONDS = 20
_ERROR_LINE = re.compile(r"^(.*?):(\d+):\s*(.*)$")


def _compile_source(source: str) -> CompileResponse:
    engine = shutil.which("pdflatex")
    if not engine:
        raise HTTPException(status_code=503, detail="LaTeX compiler is not provisioned on this worker.")

    with tempfile.TemporaryDirectory(prefix="skolab-tex-") as raw_dir:
        work_dir = Path(raw_dir)
        source_path = work_dir / "main.tex"
        source_path.write_text(source, encoding="utf-8")
        try:
            completed = subprocess.run(
                [engine, "-no-shell-escape", "-interaction=nonstopmode", "-halt-on-error", "-file-line-error", "main.tex"],
                cwd=work_dir,
                capture_output=True,
                text=True,
                errors="replace",
                timeout=_COMPILE_TIMEOUT_SECONDS,
                check=False,
            )
        except subprocess.TimeoutExpired as exc:
            log = (exc.stdout or "")[-20_000:]
            return CompileResponse(status="timeout", log=log, errors=["Compilation exceeded the 20 second limit."])

        log = ((completed.stdout or "") + "\n" + (completed.stderr or ""))[-20_000:]
        pdf_path = work_dir / "main.pdf"
        if completed.returncode != 0 or not pdf_path.exists():
            errors = []
            for line in log.splitlines():
                match = _ERROR_LINE.match(line.strip())
                if match:
                    errors.append(f"line {match.group(2)}: {match.group(3)}")
            return CompileResponse(status="error", log=log, errors=errors[:20] or ["LaTeX compilation failed."])

        pdf = pdf_path.read_bytes()
        if len(pdf) > _MAX_PDF_BYTES:
            return CompileResponse(status="error", log=log, errors=["Compiled PDF exceeds the 8 MB limit."])
        return CompileResponse(status="compiled", pdf_base64=base64.b64encode(pdf).decode("ascii"), log=log)


@router.post("/colab/compile", response_model=CompileResponse)
async def compile_latex(req: CompileRequest, _user: dict = Depends(require_firebase_user)) -> CompileResponse:
    """Compile an authenticated researcher's source in an isolated temp dir.

    The subprocess is moved off the event loop and is bounded by both source
    size and wall-clock limits. A future queue worker can preserve this exact
    response contract while making compilation asynchronous.
    """

    return await asyncio.to_thread(_compile_source, req.latex_source)
