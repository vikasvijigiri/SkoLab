from fastapi import APIRouter, Depends, UploadFile, File, HTTPException, Request
from app.schemas.core import AgentChatRequest, ChatRequest
from app.schemas.agent import (
    AgentChatResponse,
    ChatWithAuthorResponse,
    UploadDocumentResponse,
)
from app.services.ai.agent_service import AgentService
from app.services.platform.pipeline_services import PipelineServices
from typing import Optional
from app.api.dependencies import (
    get_agent_service,
    get_pipeline_services,
    get_verified_user,
    get_optional_user,
)

router = APIRouter()


@router.post("/agent/chat", response_model=AgentChatResponse)
async def agent_chat(
    req: AgentChatRequest,
    request: Request,
    agent_service: AgentService = Depends(get_agent_service),
    _user: dict = Depends(get_verified_user),
):
    base_url = str(request.base_url).rstrip("/")
    return await agent_service.process_agent_chat(req, base_url=base_url)


@router.post("/agent/upload_document", response_model=UploadDocumentResponse)
async def upload_document(
    file: UploadFile = File(...),
    agent_service: AgentService = Depends(get_agent_service),
):
    content = await file.read()
    # 10MB limit
    if len(content) > 10 * 1024 * 1024:
        raise HTTPException(status_code=400, detail="File size exceeds the 10MB limit.")

    allowed_types = ["application/pdf", "text/plain", "text/markdown", "text/csv"]
    content_type = file.content_type or ""
    filename = file.filename or ""
    is_valid = (
        content_type in allowed_types
        or filename.endswith(".pdf")
        or filename.endswith(".txt")
        or filename.endswith(".md")
        or filename.endswith(".csv")
    )
    if not is_valid:
        raise HTTPException(
            status_code=400,
            detail="Unsupported file type. Only PDF, TXT, MD, and CSV files are allowed.",
        )

    return await agent_service.process_upload_document(content, filename, content_type)


@router.post("/chat_with_author", response_model=ChatWithAuthorResponse)
async def chat_with_author(
    req: ChatRequest,
    pipeline_services: PipelineServices = Depends(get_pipeline_services),
    _user: Optional[dict] = Depends(get_optional_user),
):
    hist_dict = [{"role": h.role, "content": h.content} for h in req.history]
    # Real, server-verified uid — never a client-supplied value — so chat
    # history is keyed to who's actually authenticated, not shared across
    # every caller (see chat_with_author's docstring for the prior bug).
    real_user_id = _user.get("uid") if _user else None
    return await pipeline_services.chat_with_author(
        author_id=req.author_id,
        paper_title=req.paper_title,
        user_message=req.user_message,
        history=hist_dict,
        user_id=real_user_id,
    )
