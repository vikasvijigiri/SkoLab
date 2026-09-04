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

import platform
import sys as _sys
from collections import namedtuple

# On Windows, zeroconf can trigger a blocking WMI query via platform.uname().
# We monkey-patch it to return real values from the standard library instead
# of going through WMI — this is safe on all platforms.
if _sys.platform == "win32":
    _node = platform.node() or "localhost"
    _release = platform.release() or "10"
    _version = platform.version() or ""
    _machine = platform.machine() or "AMD64"
    _processor = platform.processor() or _machine
    _UnameTuple = namedtuple(
        "uname_result", ["system", "node", "release", "version", "machine", "processor"]
    )
    platform.uname = lambda: _UnameTuple(
        "Windows", _node, _release, _version, _machine, _processor
    )
    platform.machine = lambda: _machine

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
import contextvars
import re

# Context variables for logging context
request_id_var: contextvars.ContextVar[str] = contextvars.ContextVar(
    "request_id", default=""
)
user_id_var: contextvars.ContextVar[str] = contextvars.ContextVar("user_id", default="")
from app.core.telemetry import trace_id_var, span_id_var

# PII Masking regex patterns
PII_PATTERNS = [
    (re.compile(r"[\w\.-]+@[\w\.-]+\.\w+"), "[MASKED_EMAIL]"),
    (
        re.compile(
            r"\+?\d{1,4}[-.\s]?\(?\d{1,3}\)?[-.\s]?\d{1,4}[-.\s]?\d{1,4}[-.\s]?\d{1,9}"
        ),
        "[MASKED_PHONE]",
    ),
    (
        re.compile(r"(bearer\s+)[A-Za-z0-9\-\._~\+\/]+=*", re.IGNORECASE),
        r"\1[MASKED_TOKEN]",
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
        log_payload = {
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
import socket
from fastapi import FastAPI, Request, Response
from fastapi.middleware.cors import CORSMiddleware

try:
    from zeroconf import ServiceInfo
    from zeroconf.asyncio import AsyncZeroconf

    _ZEROCONF_AVAILABLE = True
except (ImportError, Exception) as _zeroconf_import_err:
    _ZEROCONF_AVAILABLE = False
    ServiceInfo = None  # type: ignore
    AsyncZeroconf = None  # type: ignore
    # Use _original_print here — settings is not yet imported so the JSON
    # formatter would crash if we went through the logging system.
    _original_print(
        f"[mDNS] zeroconf unavailable — mDNS service discovery disabled. "
        f"Reason: {_zeroconf_import_err}"
    )

from app.core.config import settings
from app.core.observability import init_observability

# Initialise error aggregation before the app is built (no-op without SENTRY_DSN).
init_observability()

from app.core.cache import (
    suggestions_cache,
    profile_cache,
    daily_feed_cache,
    network_collaborators_cache,
)
from app.api.v1.router import api_router
from app.api.errors import register_exception_handlers
from app.schemas.system import AppInfoResponse, LivenessResponse

_zeroconf: AsyncZeroconf | None = None
_mdns_info: ServiceInfo | None = None


@asynccontextmanager
async def lifespan(app: FastAPI):
    # 1. Redis Cache & Postgres Schema
    from app.db.pg_cache import init_redis

    await init_redis()

    from app.db.database import init_db

    print("[Postgres] Initializing local database schema...", flush=True)
    print("[Storage] Strategy:", flush=True)
    print(
        "  PostgreSQL → hot data: users, connections, caches, agent history, search metadata",
        flush=True,
    )
    print(
        "  Firestore  → large docs: enriched profiles, works arrays, LLM outputs",
        flush=True,
    )
    try:
        await init_db()
        print("[Postgres] Database initialization successful.", flush=True)
    except Exception as e:
        print(f"[Postgres] Database initialization failed: {e}", flush=True)

    # 2. Verify Firestore & Clear Cache
    from app.services.data.researcher_worker import (
        check_connection_sync,
        set_firestore_available,
    )
    import concurrent.futures

    print("[Firestore] Verifying cloud connection on startup...", flush=True)

    # Clear all PgBackedCache entries on startup so stale in-mem L1 is wiped
    try:
        await suggestions_cache.clear()
        await profile_cache.clear()
        await daily_feed_cache.clear()
        await network_collaborators_cache.clear()
        print("[Cache] Startup in-memory L1 caches cleared.", flush=True)
    except Exception as e:
        print(f"[Cache] Startup clear failed: {e}", flush=True)

    loop = asyncio.get_event_loop()
    try:
        executor = concurrent.futures.ThreadPoolExecutor(max_workers=1)
        future = loop.run_in_executor(executor, check_connection_sync)
        success = await asyncio.wait_for(future, timeout=3.0)
        set_firestore_available(success)
        executor.shutdown(wait=False)
        if success:
            print(
                "[Firestore] Connected ✔ — large docs (enriched profiles, works) will be stored here.",
                flush=True,
            )
        else:
            print(
                "[Firestore] Unavailable — falling back to PostgreSQL-only mode.",
                flush=True,
            )
    except asyncio.TimeoutError:
        print(
            "[Firestore] Connection timed out after 3s — Firestore disabled.",
            flush=True,
        )
        set_firestore_available(False)
    except Exception as e:
        print(
            f"[Firestore] Connection check failed: {e} — Firestore disabled.",
            flush=True,
        )
        set_firestore_available(False)

    # 3. Register mDNS (optional — skipped if zeroconf is blocked by antivirus)
    global _zeroconf, _mdns_info
    import traceback

    if not _ZEROCONF_AVAILABLE:
        print(
            "[mDNS] Skipped — zeroconf library unavailable (likely blocked by antivirus). "
            "Android clients will connect via manual IP or emulator loopback.",
            flush=True,
        )
    else:
        try:
            ips = []
            try:
                for info in socket.getaddrinfo(socket.gethostname(), None):
                    ip = info[4][0]
                    if (
                        "." in ip
                        and not ip.startswith("127.")
                        and not ip.startswith("169.254")
                    ):
                        if ip not in ips:
                            ips.append(ip)
            except Exception as e:
                print(f"[mDNS] Failed to get IPs via getaddrinfo: {e}", flush=True)

            if not ips:
                ips = [settings.lan_ip]

            addresses = [socket.inet_aton(ip) for ip in ips]
            print(f"[mDNS] Advertising backend on IPs: {ips}", flush=True)

            _mdns_info = ServiceInfo(
                type_=settings.mdns_service_type,
                name=settings.mdns_service_name,
                addresses=addresses,
                port=settings.mdns_port,
                properties={"path": "/", "version": "1"},
            )
            _zeroconf = AsyncZeroconf()
            await _zeroconf.async_register_service(_mdns_info, allow_name_change=True)
            print(
                f"[mDNS] '{settings.mdns_service_name}' registered at {ips}:{settings.mdns_port}"
            )
        except Exception as exc:
            print(f"[mDNS] Registration failed: {exc}")
            traceback.print_exc()

    # Start SRE Host Disk Capacity Monitor background task
    async def monitor_disk_space():
        import shutil

        while True:
            try:
                disk = shutil.disk_usage("/")
                pct = (disk.used / disk.total) * 100
                if pct > 80.0:
                    logger.critical(
                        f"[CRITICAL_ALERT] Disk capacity crossed 80% boundary! Current usage: {pct:.1f}%",
                        extra={"disk_usage_percent": pct},
                    )
            except Exception as e:
                logger.error(f"Disk space monitor failed: {e}")
            await asyncio.sleep(60.0)

    disk_monitor_task = asyncio.create_task(monitor_disk_space())

    yield

    # ── [Shutdown] ──
    disk_monitor_task.cancel()
    try:
        await disk_monitor_task
    except asyncio.CancelledError:
        pass

    # Release the shared outbound HTTP clients' keep-alive connections cleanly.
    try:
        from app.services.ai.llm_service import aclose_http_client

        await aclose_http_client()
    except Exception as e:
        print(f"[Shutdown] LLM HTTP client close failed: {e}", flush=True)
    try:
        from app.services.data.openalex_service import aclose_openalex_client

        await aclose_openalex_client()
    except Exception as e:
        print(f"[Shutdown] OpenAlex HTTP client close failed: {e}", flush=True)

    if _zeroconf and _mdns_info:
        await _zeroconf.async_unregister_service(_mdns_info)
        await _zeroconf.async_close()
        print(f"[mDNS] '{settings.mdns_service_name}' unregistered")


app = FastAPI(
    title="SkoLab API",
    description=(
        "Research intelligence backend powering the SkoLab Android app.\n\n"
        "**Auth**: Protected endpoints require a Firebase ID token in the "
        "`Authorization: Bearer <token>` header.\n\n"
        "**Rate limits**: enforced per-IP by the Go API gateway in front of "
        "this service, not here."
    ),
    version="1.1.0",
    lifespan=lifespan,
    # Interactive docs and the raw schema are developer tools, not a public
    # surface — expose them everywhere except production (OWASP API8: reduce the
    # attack surface / avoid information disclosure). Regenerate the committed
    # snapshot with scripts/gen_openapi_snapshot.py, which builds the app
    # directly rather than fetching /openapi.json.
    docs_url=None if settings.environment == "production" else "/docs",
    redoc_url=None if settings.environment == "production" else "/redoc",
    openapi_url=None if settings.environment == "production" else "/openapi.json",
    openapi_tags=[
        {
            "name": "agent",
            "description": "AI research agent — chat, document upload, cover letters.",
        },
        {"name": "papers", "description": "Paper search, feed, and recommendations."},
        {
            "name": "authors",
            "description": "Researcher profiles, metrics, and co-author graphs.",
        },
        {"name": "users", "description": "User account management and GDPR deletion."},
        {"name": "feed", "description": "Personalised daily feed and trending items."},
        {"name": "system", "description": "Health, readiness, and AI status endpoints."},
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
# What used to live here — a per-process token-bucket rate limiter, a per-IP
# admin-subnet gate for /metrics + /ai_status, and a device-signature check —
# was retired (docs/plans/2026-09-04-retire-python-infra.md):
#   • Per-IP rate limiting is the Go gateway's job (middleware.NewRateLimiter,
#     applied globally in services/backend-go/main.go). The Python copy was
#     redundant and per-worker.
#   • The admin gate only ever protected /metrics, which is gone (below).
#   • The device signature was keyed by settings.database_encryption_key — a
#     server-only secret no client can hold — so it could never pass for a
#     real client and guarded no real route.
# The kill switch stays: KILL_SWITCHES=feature1,feature2 makes any route whose
# path contains that fragment return 503 without a redeploy.
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


class _BackgroundTaskGauge:
    """Compat shim for the retired ``MetricsStore``.

    The full metrics store (Prometheus counters, latency histograms, outbound
    HTTP stats and the ``GET /metrics`` endpoint) was removed — the Go gateway
    owns request metrics now. ``app/api/v1/endpoints/authors.py`` is owned by a
    parallel stream and still calls these two methods from its teleport
    background task with an unguarded ``from app.main import metrics_store``.
    This keeps that import working until that stream drops the gauge calls, at
    which point the shim can be deleted. Nothing reads ``background_tasks_active``.
    """

    def __init__(self) -> None:
        self.background_tasks_active = 0

    async def increment_background_tasks(self) -> None:
        self.background_tasks_active += 1

    async def decrement_background_tasks(self) -> None:
        self.background_tasks_active = max(0, self.background_tasks_active - 1)


metrics_store = _BackgroundTaskGauge()


@app.middleware("http")
async def structured_log_middleware(request: Request, call_next):
    # W3C traceparent extraction and validation
    traceparent = request.headers.get("traceparent")
    trace_id = ""
    if traceparent:
        parts = traceparent.split("-")
        if len(parts) >= 3:
            trace_id = parts[1]

    if not trace_id:
        trace_id = (
            request.headers.get("x-trace-id")
            or request.headers.get("x-request-id")
            or str(uuid.uuid4()).replace("-", "")
        )

    # Set context variables temporarily so telemetry Span initialization reads them
    trace_id_token = trace_id_var.set(trace_id)

    request_id = request.headers.get("x-request-id") or trace_id
    request_id_token = request_id_var.set(request_id)

    # Try to extract user ID from query parameters
    user_id = (
        request.query_params.get("user_id") or request.query_params.get("userId") or ""
    )
    user_id_token = user_id_var.set(user_id)

    # Start a span for the router path
    from app.core.telemetry import tracer

    span_name = f"{request.method} {request.url.path}"

    start_time = time.perf_counter()
    response = None

    # Use the telemetry Span context manager
    with tracer.start_as_current_span(span_name) as span:
        try:
            response = await call_next(request)
            return response
        except Exception as e:
            latency_ms = int((time.perf_counter() - start_time) * 1000)
            logger.error(
                f"Uncaught exception: {str(e)}",
                exc_info=True,
                extra={
                    "endpoint": request.url.path,
                    "method": request.method,
                    "status_code": 500,
                    "latency_ms": latency_ms,
                },
            )
            raise e
        finally:
            latency_ms = int((time.perf_counter() - start_time) * 1000)
            status_code = response.status_code if response else 500

            logger.info(
                f"{request.method} {request.url.path} - {status_code} - {latency_ms}ms",
                extra={
                    "endpoint": request.url.path,
                    "method": request.method,
                    "status_code": status_code,
                    "latency_ms": latency_ms,
                },
            )
            if response:
                response.headers["X-Request-ID"] = request_id
                response.headers["traceparent"] = (
                    f"00-{span.trace_id}-{span.span_id}-01"
                )
            request_id_var.reset(request_id_token)
            user_id_var.reset(user_id_token)
            trace_id_var.reset(trace_id_token)


# Serve static downloads folder — path is always relative to backend root,
# never relative to CWD so it works regardless of where uvicorn is launched from.
from fastapi.staticfiles import StaticFiles


class CacheControlledStaticFiles(StaticFiles):
    async def get_response(self, path: str, scope) -> Response:
        response = await super().get_response(path, scope)
        response.headers["Cache-Control"] = "max-age=31536000, immutable"
        return response


_DOWNLOADS_DIR = settings.downloads_dir  # resolved absolute path from config
app.mount(
    "/downloads",
    CacheControlledStaticFiles(directory=str(_DOWNLOADS_DIR)),
    name="downloads",
)

# Single mount under /api/v1. The bare-prefix mount was removed (Stream B
# security hardening): on a public URL it doubled every route's attack surface
# and defeated the "one canonical path per endpoint" assumption the auth
# posture and rate-limit fragments rely on. Clients must use /api/v1.
app.include_router(api_router, prefix="/api/v1")


@app.get("/", response_model=AppInfoResponse)
async def root():
    """Root endpoint returning API metadata for mobile client verification and discovery."""
    return {"app": "Skolab API", "status": "online", "version": "1.0.0"}


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
    # balancer and the status page, so it must not write — the previous
    # `cache.set()` here put a row into `cache_entries` on every call and made
    # /health ~10x slower than any real read. Redis-backed L2: PING. Otherwise
    # the L2 is Postgres, whose reachability the SELECT 1 above already proved.
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


# NOTE: GET /metrics was removed here (docs/plans/2026-09-04-retire-python-infra.md).
# It served a Prometheus text exposition built from a per-process MetricsStore —
# scraping one of N uvicorn workers gave a partial, misleading picture. Request
# metrics belong at the Go gateway. infrastructure/prometheus.yml points there
# now; the gateway's own /metrics endpoint is tracked as follow-up.
