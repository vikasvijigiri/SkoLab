from pathlib import PurePath

from fastapi import APIRouter, Depends, File, HTTPException, Request, UploadFile
from app.schemas.core import AgentChatRequest, ChatRequest
from app.schemas.agent import (
    AgentChatResponse,
    ChatWithAuthorResponse,
    UploadDocumentResponse,
)
from app.services.ai.agent_service import AgentService
from app.services.platform.pipeline_services import PipelineServices
from app.api.dependencies import (
    get_agent_service,
    get_pipeline_services,
    require_quota,
)

router = APIRouter()

_MAX_UPLOAD_BYTES = 10 * 1024 * 1024
_UPLOAD_CHUNK_BYTES = 64 * 1024
_ALLOWED_UPLOADS = {
    ".pdf": "application/pdf",
    ".txt": "text/plain",
    ".md": "text/markdown",
    ".csv": "text/csv",
}


def _safe_upload_filename(filename: str) -> tuple[str, str]:
    """Return a display-safe filename and its permitted extension."""
    basename = PurePath(filename.replace("\\", "/")).name.strip()
    suffix = PurePath(basename).suffix.lower()
    if not basename or len(basename) > 255 or suffix not in _ALLOWED_UPLOADS:
        raise HTTPException(
            status_code=415,
            detail="Unsupported filename. Only PDF, TXT, MD, and CSV files are allowed.",
        )
    return basename, suffix


async def _read_limited_upload(file: UploadFile) -> bytes:
    """Read incrementally and refuse the upload as soon as it exceeds the cap."""
    chunks: list[bytes] = []
    total = 0
    while chunk := await file.read(_UPLOAD_CHUNK_BYTES):
        total += len(chunk)
        if total > _MAX_UPLOAD_BYTES:
            raise HTTPException(
                status_code=413, detail="File size exceeds the 10 MB limit."
            )
        chunks.append(chunk)
    return b"".join(chunks)


@router.post("/agent/chat", response_model=AgentChatResponse)
async def agent_chat(
    req: AgentChatRequest,
    request: Request,
    agent_service: AgentService = Depends(get_agent_service),
    _user: dict = Depends(require_quota(3)),
):
    base_url = str(request.base_url).rstrip("/")
    return await agent_service.process_agent_chat(req, base_url=base_url)


@router.post("/agent/upload-document", response_model=UploadDocumentResponse)
async def upload_document(
    file: UploadFile = File(...),
    agent_service: AgentService = Depends(get_agent_service),
    user: dict = Depends(require_quota(2)),
):
    filename, suffix = _safe_upload_filename(file.filename or "")
    declared_length = file.headers.get("content-length")
    if (
        declared_length
        and declared_length.isdigit()
        and int(declared_length) > _MAX_UPLOAD_BYTES
    ):
        raise HTTPException(
            status_code=413, detail="File size exceeds the 10 MB limit."
        )

    content = await _read_limited_upload(file)
    if suffix == ".pdf" and not content.startswith(b"%PDF-"):
        raise HTTPException(
            status_code=415, detail="Uploaded PDF has an invalid signature."
        )

    user_id = str(user.get("uid") or "")
    if not user_id:
        raise HTTPException(status_code=401, detail="Invalid Firebase user identity.")

    return await agent_service.process_upload_document(
        content,
        filename,
        _ALLOWED_UPLOADS[suffix],
        user_id=user_id,
    )


@router.post("/agent/chat-with-author", response_model=ChatWithAuthorResponse)
async def chat_with_author(
    req: ChatRequest,
    pipeline_services: PipelineServices = Depends(get_pipeline_services),
    _user: dict = Depends(require_quota(3)),
):
    hist_dict = [{"role": h.role, "content": h.content} for h in req.history]
    # Real, server-verified uid — never a client-supplied value — so chat
    # history is keyed to who's actually authenticated, not shared across
    # every caller (see chat_with_author's docstring for the prior bug).
    real_user_id = _user.get("uid")
    return await pipeline_services.chat_with_author(
        author_id=req.author_id,
        paper_title=req.paper_title,
        user_message=req.user_message,
        history=hist_dict,
        user_id=real_user_id,
    )
