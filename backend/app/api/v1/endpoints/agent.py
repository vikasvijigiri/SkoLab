from fastapi import APIRouter, Depends, UploadFile, File, HTTPException, Request
from app.schemas.core import AgentChatRequest, ChatRequest
from app.services.agent_service import AgentService
from app.services.pipeline_services import PipelineServices
from app.api.dependencies import get_agent_service, get_pipeline_services

router = APIRouter()

@router.post("/agent/chat")
async def agent_chat(
    req: AgentChatRequest,
    request: Request,
    agent_service: AgentService = Depends(get_agent_service)
):
    try:
        base_url = str(request.base_url).rstrip("/")
        return await agent_service.process_agent_chat(req, base_url=base_url)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@router.post("/agent/upload_document")
async def upload_document(
    file: UploadFile = File(...),
    agent_service: AgentService = Depends(get_agent_service)
):
    try:
        content = await file.read()
        return await agent_service.process_upload_document(
            content,
            file.filename or "unknown",
            file.content_type
        )
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

@router.post("/chat_with_author")
async def chat_with_author(
    req: ChatRequest,
    pipeline_services: PipelineServices = Depends(get_pipeline_services)
):
    try:
        hist_dict = [{"role": h.role, "content": h.content} for h in req.history]
        data = await pipeline_services.chat_with_author(
            author_id=req.author_id,
            paper_title=req.paper_title,
            user_message=req.user_message,
            history=hist_dict
        )
        return data
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))
