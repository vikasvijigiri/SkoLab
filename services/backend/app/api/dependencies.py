from typing import AsyncGenerator, Awaitable, Callable, Optional
from sqlalchemy.ext.asyncio import AsyncSession
from fastapi import Depends, HTTPException, Request, Security, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from app.db.database import AsyncSessionLocal
from app.services.ai.agent_service import AgentService
from app.services.platform.pipeline_services import PipelineServices
from app.services.ai.summarization_service import SummarizationService
from app.services.ai.prediction_service import PredictionService
from app.services.data.scraping_service import ScrapingService
from app.services.data.openalex_service import OpenAlexService
from app.domains.quest.service import QuestsService
from app.core.cache import history_summary_cache

_bearer_scheme = HTTPBearer(auto_error=False)


async def get_verified_user(
    credentials: Optional[HTTPAuthorizationCredentials] = Security(_bearer_scheme),
) -> dict:
    """
    Verifies the Firebase ID token from the Authorization: Bearer <token> header.
    Returns the decoded token payload (contains uid, email, etc.).
    Raises HTTP 401 if missing or invalid.
    """
    if credentials is None or not credentials.credentials:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Missing Authorization header.",
            headers={"WWW-Authenticate": "Bearer"},
        )

    id_token = credentials.credentials
    try:
        import os
        import firebase_admin
        from firebase_admin import auth as firebase_auth

        if not firebase_admin._apps:
            cred_path = os.environ.get("GOOGLE_APPLICATION_CREDENTIALS", "")
            if cred_path and os.path.exists(cred_path):
                from firebase_admin import credentials as fb_credentials

                firebase_admin.initialize_app(fb_credentials.Certificate(cred_path))
            else:
                firebase_admin.initialize_app()

        decoded = firebase_auth.verify_id_token(id_token)
        return decoded
    except Exception as exc:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid or expired Firebase token.",
            headers={"WWW-Authenticate": "Bearer"},
        ) from exc


async def get_optional_user(
    credentials: Optional[HTTPAuthorizationCredentials] = Security(_bearer_scheme),
) -> Optional[dict]:
    """
    Like get_verified_user but returns None when no token is provided (for
    endpoints that work for both authenticated and anonymous users).
    """
    if credentials is None or not credentials.credentials:
        return None
    try:
        return await get_verified_user(credentials)
    except HTTPException:
        return None


def require_owner(*id_params: str) -> Callable[..., Awaitable[dict]]:
    """
    Dependency factory for routes that act on behalf of the *requesting* user
    and take that user's own identifier as a path or query parameter.

    The returned dependency verifies the Firebase ID token (via
    ``get_verified_user``) **and** asserts the verified ``uid`` equals the
    ``user_id`` / ``author_id`` the request carries:

    - missing / invalid token  → HTTP 401 (raised by ``get_verified_user``)
    - token uid ≠ requested id  → HTTP 403 (IDOR attempt)
    - no identifier in request  → HTTP 400

    Pass the parameter name(s) to check, most-specific first; defaults to
    ``("user_id", "author_id")``. Only use this where the identifier is the
    caller's own Firebase uid — a route that merely looks up *another*
    researcher's public OpenAlex profile stays public. See
    ``docs/backend-auth-posture.md``.
    """
    names: tuple[str, ...] = id_params or ("user_id", "author_id")

    async def _require_owner(
        request: Request,
        user: dict = Depends(get_verified_user),
    ) -> dict:
        claimed: Optional[str] = None
        for name in names:
            if name in request.path_params:
                claimed = str(request.path_params[name])
                break
            value = request.query_params.get(name)
            if value is not None:
                claimed = value
                break
        if claimed is None:
            raise HTTPException(
                status_code=status.HTTP_400_BAD_REQUEST,
                detail=f"Missing required owner identifier ({' or '.join(names)}).",
            )
        uid = (user or {}).get("uid")
        if not uid or uid != claimed:
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail="Authenticated user does not match the requested identifier.",
            )
        return user

    return _require_owner


async def get_db() -> AsyncGenerator[AsyncSession, None]:
    """
    FastAPI dependency that yields an async SQLAlchemy session.
    """
    async with AsyncSessionLocal() as session:
        yield session


async def get_agent_service(db: AsyncSession = Depends(get_db)) -> AgentService:
    """
    Dependency provider for AgentService.
    """
    return AgentService(history_summary_cache=history_summary_cache, db=db)


async def get_pipeline_services(db: AsyncSession = Depends(get_db)) -> PipelineServices:
    """
    Dependency provider for PipelineServices.
    """
    return PipelineServices(db=db)


async def get_quests_service(db: AsyncSession = Depends(get_db)) -> QuestsService:
    """
    Dependency provider for QuestsService.
    """
    return QuestsService(db=db)


def get_summarization_service() -> SummarizationService:
    """
    Dependency provider for SummarizationService.
    """
    return SummarizationService()


def get_prediction_service() -> PredictionService:
    """
    Dependency provider for PredictionService.
    """
    return PredictionService()


def get_scraping_service() -> ScrapingService:
    """
    Dependency provider for ScrapingService.
    """
    return ScrapingService()


def get_openalex_service() -> OpenAlexService:
    """
    Dependency provider for OpenAlexService.
    """
    return OpenAlexService()


def get_openalex_headers(
    openalex_service: OpenAlexService = Depends(get_openalex_service),
) -> dict:
    """
    Returns default headers for OpenAlex requests.
    """
    return openalex_service.get_headers()
