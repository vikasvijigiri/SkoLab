import asyncio
import logging
import os
import threading
import time
from typing import AsyncGenerator, Awaitable, Callable, Optional
from sqlalchemy.ext.asyncio import AsyncSession
from fastapi import Depends, HTTPException, Request, Response, Security, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from app.core import security_events
from app.db.database import AsyncSessionLocal
from app.core.quota import QuotaExceeded, consume as consume_quota

_bearer_scheme = HTTPBearer(auto_error=False)
logger = logging.getLogger("skolab")


# Account status cache: revocation is checked against Firebase's per-user
# "tokens valid after" timestamp, cached briefly per process, instead of a
# Firebase round trip on every request (check_revoked=True). A revocation,
# disabled account or deletion therefore takes effect within the TTL.
_USER_STATUS_TTL_SECONDS = 60.0
_USER_STATUS_MAX_STALE_SECONDS = 900.0  # serve a known user through a Firebase blip
_USER_STATUS_MAX_ENTRIES = 100_000
# uid -> (tokens_valid_after_ms, disabled, fetched_at_monotonic)
_user_status: dict[str, tuple[int, bool, float]] = {}
_user_status_lock = threading.Lock()


def _unauthorized(detail: str) -> HTTPException:
    return HTTPException(
        status_code=status.HTTP_401_UNAUTHORIZED,
        detail=detail,
        headers={"WWW-Authenticate": "Bearer"},
    )


def _auth_unavailable() -> HTTPException:
    return HTTPException(
        status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
        detail="Authentication is temporarily unavailable.",
    )


def _firebase_auth():
    """The initialised firebase_admin.auth module (initialised once)."""
    import firebase_admin
    from firebase_admin import auth as firebase_auth

    if not firebase_admin._apps:
        cred_path = os.environ.get("GOOGLE_APPLICATION_CREDENTIALS", "")
        if cred_path and os.path.exists(cred_path):
            from firebase_admin import credentials as fb_credentials

            firebase_admin.initialize_app(fb_credentials.Certificate(cred_path))
        else:
            firebase_admin.initialize_app()
    return firebase_auth


async def _ensure_session_valid(firebase_auth, decoded: dict) -> None:
    """Reject a token whose sign-in predates the account's revocation point,
    or whose account is disabled or deleted. Fails closed (503) when Firebase
    is unreachable and no recent status is known."""
    uid = decoded["uid"]
    auth_time = int(decoded.get("auth_time", 0))
    now = time.monotonic()
    with _user_status_lock:
        entry = _user_status.get(uid)

    if entry is None or now - entry[2] >= _USER_STATUS_TTL_SECONDS:
        not_found = getattr(firebase_auth, "UserNotFoundError", ())
        try:
            # get_user is blocking network I/O: keep it off the event loop.
            user = await asyncio.to_thread(firebase_auth.get_user, uid)
        except not_found:
            with _user_status_lock:
                _user_status.pop(uid, None)
            security_events.record(
                security_events.AUTH_SESSION_REVOKED,
                security_events.DENIED,
                reason="account_deleted",
                actor=uid,
            )
            raise _unauthorized("Session is no longer valid; sign in again.")
        except Exception as exc:
            if entry is None or now - entry[2] >= _USER_STATUS_MAX_STALE_SECONDS:
                logger.error(
                    "Firebase account status unavailable: %s", type(exc).__name__
                )
                security_events.record(
                    security_events.AUTH_UNAVAILABLE, security_events.FAILED, actor=uid
                )
                raise _auth_unavailable() from exc
        else:
            entry = (
                int(user.tokens_valid_after_timestamp or 0),
                bool(user.disabled),
                now,
            )
            with _user_status_lock:
                if len(_user_status) >= _USER_STATUS_MAX_ENTRIES:
                    _user_status.clear()  # bounded memory; refills on demand
                _user_status[uid] = entry

    valid_after_ms, disabled, _ = entry
    if disabled or auth_time * 1000 < valid_after_ms:
        security_events.record(
            security_events.AUTH_SESSION_REVOKED,
            security_events.DENIED,
            reason="disabled" if disabled else "revoked",
            actor=uid,
        )
        raise _unauthorized("Session is no longer valid; sign in again.")


def _enforce_account_policy(decoded: dict) -> None:
    """Refuse anonymous sessions and unverified email/password accounts (403:
    the token is genuine, the account is not yet allowed). Federated
    providers vouch for their identities. Mirrors the gateway's
    auth.accountPolicy, including the AUTH_REQUIRE_VERIFIED_EMAIL=false
    rollout switch."""
    provider = (decoded.get("firebase") or {}).get("sign_in_provider")
    uid = decoded.get("uid")
    if provider == "anonymous":
        security_events.record(
            security_events.AUTH_ANONYMOUS_REFUSED, security_events.DENIED, actor=uid
        )
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Sign in with an account to use this service.",
        )
    require_verified = (
        os.environ.get("AUTH_REQUIRE_VERIFIED_EMAIL", "true").lower() != "false"
    )
    if (
        provider == "password"
        and not decoded.get("email_verified", False)
        and require_verified
    ):
        security_events.record(
            security_events.AUTH_EMAIL_UNVERIFIED, security_events.DENIED, actor=uid
        )
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Verify your email address to continue.",
        )


async def get_verified_user(
    credentials: Optional[HTTPAuthorizationCredentials] = Security(_bearer_scheme),
) -> dict:
    """
    Verifies the Firebase ID token from the Authorization: Bearer <token> header.
    Returns the decoded token payload (contains uid, email, etc.).
    Raises HTTP 401 if missing, invalid or revoked; 503 if Firebase cannot be
    used to decide.
    """
    if credentials is None or not credentials.credentials:
        raise _unauthorized("Missing Authorization header.")

    try:
        firebase_auth = _firebase_auth()
    except Exception as exc:
        logger.error("Firebase initialisation failed: %s", type(exc).__name__)
        security_events.record(
            security_events.AUTH_UNAVAILABLE,
            security_events.FAILED,
            reason="firebase_not_configured",
        )
        raise _auth_unavailable() from exc

    try:
        # Signature, expiry, issuer and audience are checked locally against
        # Google's cached keys -- off the event loop, since a key refresh is
        # a blocking HTTP call.
        decoded = await asyncio.to_thread(
            firebase_auth.verify_id_token, credentials.credentials
        )
    except Exception as exc:
        security_events.record(
            security_events.AUTH_TOKEN_INVALID, security_events.DENIED
        )
        raise _unauthorized("Invalid or expired Firebase token.") from exc

    # A local check on the token, so before the network-backed lookup.
    _enforce_account_policy(decoded)
    await _ensure_session_valid(firebase_auth, decoded)
    return decoded


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
    researcher's public profile stays public.
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


def require_quota(cost: int) -> Callable[..., Awaitable[dict]]:
    """Verified user + charge ``cost`` units against their hourly/daily budget.

    401 without a valid token (via ``get_verified_user``); 429 with
    ``Retry-After`` once the account's budget is spent. See app/core/quota.py.
    """

    async def _require_quota(
        response: Response,
        user: dict = Depends(get_verified_user),
    ) -> dict:
        uid = str((user or {}).get("uid") or "")
        if not uid:
            raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED)
        try:
            remaining = await consume_quota(uid, cost)
        except QuotaExceeded as exc:
            raise HTTPException(
                status_code=status.HTTP_429_TOO_MANY_REQUESTS,
                detail=f"Your {exc.window} usage budget is spent. Try again later.",
                headers={"Retry-After": str(exc.retry_after)},
            ) from exc
        if remaining:
            response.headers["X-Quota-Remaining-Hour"] = str(remaining["hourly"])
            response.headers["X-Quota-Remaining-Day"] = str(remaining["daily"])
        return user

    return _require_quota


async def get_db() -> AsyncGenerator[AsyncSession, None]:
    """
    FastAPI dependency that yields an async SQLAlchemy session.
    """
    async with AsyncSessionLocal() as session:
        yield session
