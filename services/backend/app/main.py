import sys
import io
from pathlib import Path

# Force stdout/stderr to use UTF-8 on Windows to avoid 'charmap' codec errors
if sys.stdout and getattr(sys.stdout, "encoding", None) != "utf-8":
    try:
        sys.stdout = io.TextIOWrapper(
            sys.stdout.buffer, encoding="utf-8", errors="replace"
        )
    except Exception:
        pass
if sys.stderr and getattr(sys.stderr, "encoding", None) != "utf-8":
    try:
        sys.stderr = io.TextIOWrapper(
            sys.stderr.buffer, encoding="utf-8", errors="replace"
        )
    except Exception:
        pass

from dotenv import load_dotenv

# MUST happen BEFORE any import that auto-initialises Firebase or reads env vars.
# Prefer backend/.env so the app behaves the same no matter where uvicorn starts.
_BACKEND_ROOT = Path(__file__).resolve().parents[1]
load_dotenv(_BACKEND_ROOT / ".env")
load_dotenv()

import time
import json
import logging
import uuid
from typing import Any
import contextvars
import re

# Context variables for logging context
request_id_var: contextvars.ContextVar[str] = contextvars.ContextVar(
    "request_id", default=""
)
user_id_var: contextvars.ContextVar[str] = contextvars.ContextVar("user_id", default="")
from app.core import security_events
from app.core.telemetry import (
    trace_id_var,
    span_id_var,
    LoopLagMonitor,
    Telemetry,
    TelemetryMiddleware,
    instrument_httpx,
    observe_saturation,
    propagator,
)

# PII Masking regex patterns
# Bearer tokens are masked first, so the other patterns never split one.
# Phone numbers must look like phone numbers (a leading + or the 3-3-4
# grouping with separators) and stand alone: a bare digit run is far more
# often an IP, port, timestamp, latency or ID than a phone number, and
# masking those made the logs useless ("127.0.0.1:8000" became
# "[MASKED_PHONE]:[MASKED_PHONE]").
_STANDALONE_START = r"(?<![\w.:/-])"
_STANDALONE_END = r"(?![\w:/-]|\.\w)"
PII_PATTERNS = [
    (
        re.compile(r"(bearer\s+)[A-Za-z0-9\-\._~\+\/]+=*", re.IGNORECASE),
        r"\1[MASKED_TOKEN]",
    ),
    (re.compile(r"[\w\.-]+@[\w\.-]+\.\w+"), "[MASKED_EMAIL]"),
    (
        re.compile(
            _STANDALONE_START
            + r"(?:\+\d{1,3}[\s.-]?(?:\(\d{1,4}\)|\d{1,4})(?:[\s.-]?\d{2,4}){2,4}"
            + r"|\(?\d{3}\)?[\s.-]\d{3}[\s.-]\d{4})"
            + _STANDALONE_END
        ),
        "[MASKED_PHONE]",
    ),
]


def mask_pii(text: str) -> str:
    if not isinstance(text, str):
        return text
    for pattern, replacement in PII_PATTERNS:
        text = pattern.sub(replacement, text)
    return text


class JSONFormatter(logging.Formatter):
    def format(self, record: logging.LogRecord) -> str:
        log_payload: dict[str, Any] = {
            "timestamp": self.formatTime(record, "%Y-%m-%dT%H:%M:%S") + ".000Z",
            "level": record.levelname,
            "service": "skolab-backend",
            "message": mask_pii(record.getMessage()),
            "request_id": request_id_var.get(),
            "user_id": user_id_var.get(),
            "trace_id": trace_id_var.get(),
            "span_id": span_id_var.get(),
            "environment": settings.environment,
        }
        for key in ["endpoint", "method", "status_code", "latency_ms"]:
            if hasattr(record, key):
                log_payload[key] = getattr(record, key)
            elif key in record.__dict__:
                log_payload[key] = record.__dict__[key]
            else:
                log_payload[key] = None
        # Security events (app/core/security_events.py) carry these; other
        # lines do not, so they are added only when present.
        for key in ["event", "outcome", "reason", "actor"]:
            if key in record.__dict__:
                log_payload[key] = record.__dict__[key]
        # Include full stack traces only in non-production environments.
        # In production, log only the sanitised error message to prevent leaking
        # internal file paths, module names, or sensitive variable values.
        if record.exc_info and settings.environment != "production":
            log_payload["stack_trace"] = self.formatException(record.exc_info)
        elif record.exc_info and settings.environment == "production":
            # Surface only the exception type and message — no traceback
            exc_type, exc_val, _ = record.exc_info
            if exc_type is not None:
                log_payload["error_type"] = exc_type.__name__
                log_payload["error_summary"] = mask_pii(str(exc_val))
        return json.dumps(log_payload)


# Replace all root handlers to output structured JSON
root_logger = logging.getLogger()
for h in list(root_logger.handlers):
    root_logger.removeHandler(h)
handler = logging.StreamHandler(sys.stdout)
handler.setFormatter(JSONFormatter())
root_logger.addHandler(handler)
root_logger.setLevel(logging.INFO)

# Make uvicorn and fastapi loggers propagate to root
for name in ["uvicorn", "uvicorn.access", "uvicorn.error", "fastapi"]:
    l = logging.getLogger(name)
    l.handlers.clear()
    l.propagate = True

logger = logging.getLogger("skolab")

# Override builtins.print to route stdout to JSON logging
import builtins

_original_print = builtins.print


def schoolab_print(*args, **kwargs):
    file = kwargs.get("file", None)
    if file is not None and file is not sys.stdout and file is not sys.stderr:
        _original_print(*args, **kwargs)
        return
    sep = kwargs.get("sep", " ")
    message = sep.join(str(arg) for arg in args)
    logger.info(message)


builtins.print = schoolab_print

# Re-entrancy flag so schoolab_print never recurses if the logging system itself
# tries to call print (e.g. from handleError / traceback.print_exception).
_in_schoolab_print = False


def schoolab_print_safe(*args, **kwargs):
    global _in_schoolab_print
    if _in_schoolab_print:
        # We are already inside the custom logger — fall back to real stdout to break the cycle.
        _original_print(*args, **kwargs)
        return
    _in_schoolab_print = True
    try:
        schoolab_print(*args, **kwargs)
    finally:
        _in_schoolab_print = False


builtins.print = schoolab_print_safe

from contextlib import asynccontextmanager
import asyncio
from fastapi import FastAPI, Request, Response
from fastapi.middleware.cors import CORSMiddleware

from app.core.config import settings
from app.core.observability import init_observability

# Initialise error aggregation before the app is built (no-op without SENTRY_DSN).
init_observability()

from app.api.v1.router import api_router
from app.api.errors import register_exception_handlers
from app.schemas.system import AppInfoResponse, LivenessResponse


@asynccontextmanager
async def lifespan(app: FastAPI):
    # Redis (quota's L1 tier — app/core/quota.py) & Postgres schema.
    from app.db.pg_cache import init_redis

    await init_redis()

    from app.db.database import init_db

    print("[Postgres] Initializing local database schema...", flush=True)
    try:
        await init_db()
        print("[Postgres] Database initialization successful.", flush=True)
    except Exception as e:
        print(f"[Postgres] Database initialization failed: {e}", flush=True)

    # Fail loud (in the deploy log, once) if the DB is behind alembic head.
    # Never blocks startup.
    try:
        from app.db.schema_guard import check_schema_current

        await check_schema_current()
    except Exception as e:  # pragma: no cover - guard must never break boot
        print(f"[schema_guard] drift check failed: {e}", flush=True)

    # SRE background maintenance: disk-capacity alerting. Fires only on
    # crossing an escalating band (80/85/90/95), re-alerts a standing
    # condition at most hourly, and rounds the percentage so Sentry groups
    # it into one issue (2026-09 audit — the old monitor logged CRITICAL
    # every 60 s and filed a new issue per 0.1 % wobble).
    from app.core.observability import DiskUsageAlerter, check_disk_usage

    async def sre_maintenance_loop():
        alerter = DiskUsageAlerter()
        while True:
            check_disk_usage(alerter)
            await asyncio.sleep(60.0)

    maintenance_task = asyncio.create_task(sre_maintenance_loop())
    loop_lag_task = asyncio.create_task(app.state.loop_lag.run())

    try:
        yield
    finally:
        for task in (maintenance_task, loop_lag_task):
            task.cancel()
            try:
                await task
            except asyncio.CancelledError:
                pass
        await asyncio.to_thread(app.state.telemetry.shutdown)


app = FastAPI(
    title="SkoLab API",
    description=(
        "Authentication, authorization, and CoLab (workspace collaboration + "
        "LaTeX compile) backend.\n\n"
        "**Auth**: Protected endpoints require a Firebase ID token in the "
        "`Authorization: Bearer <token>` header.\n\n"
        "**Rate limits**: enforced per-IP by the Go API gateway in front of "
        "this service, plus a per-user cost quota (app/core/quota.py) on "
        "/colab/compile."
    ),
    version="2.0.0",
    lifespan=lifespan,
    # Interactive docs and the raw schema are developer tools, not a public
    # surface — expose them everywhere except production (OWASP API8: reduce the
    # attack surface / avoid information disclosure).
    docs_url=None if settings.environment == "production" else "/docs",
    redoc_url=None if settings.environment == "production" else "/redoc",
    openapi_url=None if settings.environment == "production" else "/openapi.json",
    openapi_tags=[
        {
            "name": "CoLab",
            "description": "LaTeX compile (sandboxed) and workspace access.",
        },
        {"name": "users", "description": "User account management and GDPR deletion."},
    ],
    swagger_ui_parameters={"persistAuthorization": True},
)

# App-level error envelope: consistent ErrorResponse shape, no leaked internals.
register_exception_handlers(app)

# Configure CORS origins dynamically and restrict from wildcard
import os

_IS_PROD = settings.environment == "production"

# localhost/loopback origins are for local dev only. With allow_credentials=True
# a page on a dev server could otherwise make credentialed calls against
# production, so production trusts only APP_BASE_URL and CORS_ORIGINS.
origins: list[str] = []
if not _IS_PROD:
    origins += [
        "http://localhost",
        "http://localhost:8000",
        "http://localhost:3000",
        "http://127.0.0.1",
        "http://127.0.0.1:8000",
        "http://127.0.0.1:3000",
    ]
# app_base_url defaults to http://localhost:8000 — don't treat that default as a
# real production origin; only an explicitly configured value counts.
if settings.app_base_url and not (
    _IS_PROD and settings.app_base_url == "http://localhost:8000"
):
    origins.append(settings.app_base_url)
env_origins = os.environ.get("CORS_ORIGINS", "")
if env_origins:
    for o in env_origins.split(","):
        o_clean = o.strip()
        if o_clean:
            origins.append(o_clean)
# Remove duplicates
origins = list(set(origins))
if _IS_PROD and not origins:
    logger.warning(
        "CORS: no production origins configured. Set APP_BASE_URL or CORS_ORIGINS "
        "to the web app's real URL; browser clients will be blocked until then."
    )

app.add_middleware(
    CORSMiddleware,
    allow_origins=origins,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

if settings.force_https:
    from fastapi.middleware.httpsredirect import HTTPSRedirectMiddleware

    app.add_middleware(HTTPSRedirectMiddleware)


# ── SRE kill-switch guard ───────────────────────────────────────────────────
# KILL_SWITCHES=feature1,feature2 makes any route whose path contains that
# fragment return 503 without a redeploy. Per-IP rate limiting is the Go
# gateway's job (middleware.NewRateLimiter) — not duplicated here.
@app.middleware("http")
async def security_guard_middleware(request: Request, call_next):
    path = request.url.path.lower()

    kill_switches = [
        x.strip().lower()
        for x in os.environ.get("KILL_SWITCHES", "").split(",")
        if x.strip()
    ]
    if path not in ["/", "/health", "/health/"]:
        for feature in kill_switches:
            if feature in path:
                from fastapi.responses import JSONResponse

                return JSONResponse(
                    status_code=503,
                    content={
                        "detail": f"Feature '{feature}' is temporarily disabled via SRE kill switch."
                    },
                )

    return await call_next(request)


@app.middleware("http")
async def structured_log_middleware(request: Request, call_next):
    from opentelemetry import trace

    # OTel middleware owns the server span; logging creates no second span.
    context = trace.get_current_span().get_span_context()
    trace_id = (
        format(context.trace_id, "032x")
        if context.is_valid
        else str(uuid.uuid4()).replace("-", "")
    )
    request_id = request.headers.get("x-request-id") or trace_id
    request_token = request_id_var.set(request_id)
    trace_token = trace_id_var.set(trace_id)
    span_token = span_id_var.set(
        format(context.span_id, "016x") if context.is_valid else ""
    )
    start_time = time.perf_counter()
    response = None
    try:
        response = await call_next(request)
        response.headers["X-Request-ID"] = request_id
        carrier: dict[str, str] = {}
        propagator.inject(carrier)
        if "traceparent" in carrier:
            response.headers["traceparent"] = carrier["traceparent"]
        elif request.headers.get("traceparent"):
            # Probes are intentionally excluded from metrics/traces, but valid
            # incoming trace context still round-trips for diagnostics.
            from opentelemetry.trace import get_current_span

            extracted = get_current_span(
                propagator.extract(dict(request.headers))
            ).get_span_context()
            if extracted.is_valid:
                response.headers["traceparent"] = request.headers["traceparent"]
        return response
    except Exception:
        logger.exception("Uncaught request exception")
        raise
    finally:
        route = getattr(request.scope.get("route"), "path", "unmatched")
        logger.info(
            "%s %s - %s",
            request.method,
            route,
            response.status_code if response else 500,
            extra={
                "endpoint": route,
                "method": request.method,
                "status_code": response.status_code if response else 500,
                "latency_ms": int((time.perf_counter() - start_time) * 1000),
            },
        )
        request_id_var.reset(request_token)
        trace_id_var.reset(trace_token)
        span_id_var.reset(span_token)


# Expose one canonical API prefix. Clients must use /api/v1.
app.include_router(api_router, prefix="/api/v1")


@app.get("/", response_model=AppInfoResponse)
async def root():
    """Root endpoint returning API metadata for client verification."""
    return {"app": "Skolab API", "status": "online", "version": "2.0.0"}


async def check_readiness() -> tuple[bool, dict[str, str]]:
    """Probe the DB and cache. Never raises — failures land in the status dict."""
    db_status = "unhealthy"
    cache_status = "unhealthy"

    from app.db.database import AsyncSessionLocal
    from sqlalchemy import text

    try:
        async with AsyncSessionLocal() as session:
            await session.execute(text("SELECT 1"))
            db_status = "healthy"
    except Exception as e:
        logger.error(f"Database health check failed: {e}")

    # Read-only cache probe. This endpoint is hit every few seconds by the load
    # balancer and the status page, so it must not write. Redis-backed L2: PING.
    # Otherwise the L2 is Postgres, whose reachability the SELECT 1 above
    # already proved.
    try:
        from app.db.pg_cache import _redis_active, _redis_client

        if _redis_active and _redis_client is not None:
            await _redis_client.ping()
            cache_status = "healthy"
        else:
            cache_status = db_status
    except Exception as e:
        logger.error(f"Cache health check failed: {e}")

    ok = db_status == "healthy" and cache_status == "healthy"
    return ok, {"database": db_status, "cache": cache_status}


@app.get("/livez", response_model=LivenessResponse)
async def livez():
    """Liveness probe — the process is up. Makes NO dependency calls, so a DB
    or cache blip never triggers an orchestrator restart of a healthy pod."""
    return {"status": "alive"}


@app.get("/readyz")
async def readyz():
    """Readiness probe — should traffic route here? 503 drains this instance."""
    ok, detail = await check_readiness()
    return Response(
        content=json.dumps({"status": "ready" if ok else "not ready", **detail}),
        media_type="application/json",
        status_code=200 if ok else 503,
    )


@app.get("/health")
async def health():
    """Dynamic status check verifying database and cache connectivity."""
    ok, detail = await check_readiness()
    return Response(
        content=json.dumps(
            {
                "status": "healthy" if ok else "unhealthy",
                "database": detail["database"],
                "cache": detail["cache"],
            }
        ),
        media_type="application/json",
        status_code=200 if ok else 503,
    )


# Each worker pushes OTLP with its own service.instance.id. No scrape endpoint.
app.state.telemetry = Telemetry()
app.state.loop_lag = LoopLagMonitor()
security_events.use_meter(app.state.telemetry.metrics.get_meter("skolab.security"))


def _db_pool():
    from app.db.database import engine

    return engine.sync_engine.pool


observe_saturation(
    app.state.telemetry,
    pool=_db_pool,
    max_connections=settings.db_pool_size + settings.db_max_overflow,
    loop_lag=app.state.loop_lag,
)
app.add_middleware(TelemetryMiddleware, telemetry=app.state.telemetry)
instrument_httpx()
