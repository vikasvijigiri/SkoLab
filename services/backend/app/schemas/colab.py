import base64
import binascii
import re

from pydantic import BaseModel, Field, field_validator, model_validator

# Project files sent with a compile (see services/backend-go/internal/texsandbox
# ValidPath/CheckFiles, which apply the same rules on the Go side).
MAX_FILES = 200
MAX_FILES_BYTES = 10 * 1024 * 1024
_MAX_PATH_BYTES = 200
_MAX_PATH_DEPTH = 6
_SEGMENT = re.compile(r"[A-Za-z0-9 _.,()+\-]{1,80}")
# Names the compile itself owns at the work-dir root (case-insensitive).
_RESERVED_ROOT = frozenset(
    {
        "main.tex",
        "main.pdf",
        "main.log",
        "main.aux",
        "main.bbl",
        "main.blg",
        "main.out",
        "compile.out",
    }
)


def valid_path(path: str) -> bool:
    """1..200 bytes of "/"-separated segments, each 1..80 characters of
    [A-Za-z0-9 _.,()+-] not starting with ".", at most 6 deep, and not a name
    the compile owns at the root. Nothing can be absolute, "..", or a dotfile."""
    if not path or len(path.encode("utf-8")) > _MAX_PATH_BYTES:
        return False
    segments = path.split("/")
    if len(segments) > _MAX_PATH_DEPTH:
        return False
    if any(not _SEGMENT.fullmatch(seg) or seg.startswith(".") for seg in segments):
        return False
    return len(segments) > 1 or path.lower() not in _RESERVED_ROOT


class CompileFile(BaseModel):
    """One project file written next to main.tex before the compile."""

    path: str = Field(..., max_length=_MAX_PATH_BYTES)
    content_base64: str = ""

    @field_validator("path")
    @classmethod
    def _check_path(cls, value: str) -> str:
        if not valid_path(value):
            raise ValueError("invalid project file path")
        return value

    @field_validator("content_base64")
    @classmethod
    def _check_base64(cls, value: str) -> str:
        # Bound before decoding; the decoded total is checked on the request.
        if len(value) > (MAX_FILES_BYTES + 2) // 3 * 4:
            raise ValueError("project file is too large")
        try:
            base64.b64decode(value, validate=True)
        except (binascii.Error, ValueError):
            raise ValueError("content_base64 is not valid base64") from None
        return value

    def data(self) -> bytes:
        return base64.b64decode(self.content_base64, validate=True)


class CompileRequest(BaseModel):
    """Bounded source payload for a CoLab LaTeX compilation request."""

    latex_source: str = Field(..., min_length=1, max_length=100_000)
    engine: str = Field(default="pdflatex", pattern="^pdflatex$")
    files: list[CompileFile] = Field(default_factory=list, max_length=MAX_FILES)

    @model_validator(mode="after")
    def _check_files(self) -> "CompileRequest":
        seen: set[str] = set()
        total = 0
        for item in self.files:
            key = item.path.lower()
            if key in seen:
                raise ValueError(f"duplicate project file path {item.path!r}")
            seen.add(key)
            # Exact decoded length without decoding again.
            encoded = item.content_base64
            total += len(encoded) // 4 * 3 - encoded[-2:].count("=")
        if total > MAX_FILES_BYTES:
            raise ValueError("project files exceed 10 MiB in total")
        for item in self.files:
            parts = item.path.split("/")
            for depth in range(1, len(parts)):
                if "/".join(parts[:depth]).lower() in seen:
                    raise ValueError(
                        f"project file {'/'.join(parts[:depth])!r} is also used as a folder"
                    )
        return self


class CompileResponse(BaseModel):
    status: str
    pdf_base64: str | None = None
    log: str = ""
    errors: list[str] = Field(default_factory=list)
