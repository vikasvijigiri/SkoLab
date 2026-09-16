from pydantic import BaseModel, Field


class CompileRequest(BaseModel):
    """Bounded source payload for a CoLab LaTeX compilation request."""

    latex_source: str = Field(..., min_length=1, max_length=100_000)
    engine: str = Field(default="pdflatex", pattern="^pdflatex$")


class CompileResponse(BaseModel):
    status: str
    pdf_base64: str | None = None
    log: str = ""
    errors: list[str] = Field(default_factory=list)
