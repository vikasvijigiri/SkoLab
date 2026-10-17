"""The AI writing improver's model call, for the Go gateway only.

The gateway (internal/ai) authenticates the reader, checks their role and
daily allowance, protects math, citations and commands with placeholders,
and verifies every edit before anyone sees it. This route only asks the
model (app/services/llm.py), so it admits nothing but the gateway's
X-Internal-Token.
"""

from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException

from app.api.dependencies import require_gateway
from app.schemas.ai import ImproveRequest, ImproveResponse
from app.services import llm

router = APIRouter()


@router.post(
    "/ai/improve",
    response_model=ImproveResponse,
    dependencies=[Depends(require_gateway)],
)
async def improve(request: ImproveRequest) -> ImproveResponse:
    try:
        return await llm.improve(request.text, request.goal)
    except llm.LLMUnavailable as exc:
        headers = {"Retry-After": str(exc.retry_after)} if exc.retry_after else None
        raise HTTPException(
            status_code=exc.status, detail=str(exc), headers=headers
        ) from exc
    except llm.LLMBadOutput as exc:
        raise HTTPException(
            status_code=502,
            detail="The writing assistant gave an unusable answer; try again",
        ) from exc
