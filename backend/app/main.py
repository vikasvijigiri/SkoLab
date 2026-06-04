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
request_id_var: contextvars.ContextVar[str] = contextvars.ContextVar("request_id", default="")
user_id_var: contextvars.ContextVar[str] = contextvars.ContextVar("user_id", default="")
from app.core.telemetry import trace_id_var, span_id_var

# PII Masking regex patterns
PII_PATTERNS = [
    (re.compile(r"[\w\.-]+@[\w\.-]+\.\w+"), "[MASKED_EMAIL]"),
    (re.compile(r"\+?\d{1,4}[-.\s]?\(?\d{1,3}\)?[-.\s]?\d{1,4}[-.\s]?\d{1,4}[-.\s]?\d{1,9}"), "[MASKED_PHONE]"),
    (re.compile(r"(bearer\s+)[A-Za-z0-9\-\._~\+\/]+=*", re.IGNORECASE), r"\1[MASKED_TOKEN]"),
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

from contextlib import asynccontextmanager
import asyncio
import socket
from fastapi import FastAPI, Request, Response
from fastapi.middleware.cors import CORSMiddleware
from zeroconf import ServiceInfo
from zeroconf.asyncio import AsyncZeroconf

from app.core.config import settings
from app.core.cache import (
    suggestions_cache,
    profile_cache,
    daily_feed_cache,
    network_collaborators_cache,
)
from app.api.v1.router import api_router

_zeroconf: AsyncZeroconf | None = None
_mdns_info: ServiceInfo | None = None


@asynccontextmanager
async def lifespan(app: FastAPI):
    # ── [Startup] ──
    # 1. Postgres Schema
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
    from app.services.researcher_worker import (
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

    # 3. Register mDNS
    global _zeroconf, _mdns_info
    import traceback

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
            port=settings.port,
            properties={"path": "/", "version": "1"},
        )
        _zeroconf = AsyncZeroconf()
        await _zeroconf.async_register_service(_mdns_info, allow_name_change=True)
        print(
            f"[mDNS] '{settings.mdns_service_name}' registered at {ips}:{settings.port}"
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
                        extra={"disk_usage_percent": pct}
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

    if _zeroconf and _mdns_info:
        await _zeroconf.async_unregister_service(_mdns_info)
        await _zeroconf.async_close()
        print(f"[mDNS] '{settings.mdns_service_name}' unregistered")


app = FastAPI(
    title="Skolab API",
    description="The backend API for the Skolab platform",
    version="1.0.0",
    lifespan=lifespan,
)

# Configure CORS origins dynamically and restrict from wildcard
import os

origins = [
    "http://localhost",
    "http://localhost:8000",
    "http://localhost:3000",
    "http://127.0.0.1",
    "http://127.0.0.1:8000",
    "http://127.0.0.1:3000",
]
if settings.app_base_url:
    origins.append(settings.app_base_url)
env_origins = os.environ.get("CORS_ORIGINS", "")
if env_origins:
    for o in env_origins.split(","):
        o_clean = o.strip()
        if o_clean:
            origins.append(o_clean)
# Remove duplicates
origins = list(set(origins))

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


# Token Bucket Rate Limiter for Pillar 2 Compliance
class TokenBucket:
    def __init__(self, capacity: float, refill_rate: float):
        self.capacity = capacity
        self.refill_rate = refill_rate
        self.tokens = float(capacity)
        self.last_update = time.perf_counter()
        self.lock = asyncio.Lock()

    async def consume(self) -> bool:
        async with self.lock:
            now = time.perf_counter()
            elapsed = now - self.last_update
            self.last_update = now
            self.tokens = min(self.capacity, self.tokens + elapsed * self.refill_rate)
            if self.tokens >= 1.0:
                self.tokens -= 1.0
                return True
            return False


class RateLimiter:
    def __init__(self, capacity: float = 60.0, refill_rate: float = 1.0):
        self.capacity = capacity
        self.refill_rate = refill_rate
        self.buckets = {}
        self.last_cleanup = time.perf_counter()
        self.lock = asyncio.Lock()

    async def check_rate_limit(self, client_ip: str, capacity: float = None, refill_rate: float = None) -> bool:
        cap = capacity if capacity is not None else self.capacity
        refill = refill_rate if refill_rate is not None else self.refill_rate

        async with self.lock:
            now = time.perf_counter()
            if now - self.last_cleanup > 60.0:
                inactive_keys = [
                    key for key, bucket in self.buckets.items()
                    if now - bucket.last_update > 300.0
                ]
                for key in inactive_keys:
                    del self.buckets[key]
                self.last_cleanup = now

            bucket_key = f"{client_ip}:{cap}"
            if bucket_key not in self.buckets:
                self.buckets[bucket_key] = TokenBucket(cap, refill)

            return await self.buckets[bucket_key].consume()


rate_limiter = RateLimiter()


@app.middleware("http")
async def kill_switch_middleware(request: Request, call_next):
    # Retrieve active kill switches dynamically from environment
    kill_switches = [x.strip().lower() for x in os.environ.get("KILL_SWITCHES", "").split(",") if x.strip()]
    path = request.url.path.lower()

    # Allow health and metrics checks to bypass kill switches
    if path not in ["/", "/health", "/health/", "/metrics", "/metrics/"]:
        for feature in kill_switches:
            if feature in path:
                from fastapi.responses import JSONResponse
                return JSONResponse(
                    status_code=503,
                    content={"detail": f"Feature '{feature}' is temporarily disabled via SRE kill switch."}
                )
    return await call_next(request)


@app.middleware("http")
async def scraper_blocking_middleware(request: Request, call_next):
    user_agent = request.headers.get("user-agent", "").lower()
    bot_keywords = ["python-requests", "urllib", "curl", "wget", "scrapy", "playwright", "puppeteer"]
    if any(keyword in user_agent for keyword in bot_keywords):
        from fastapi.responses import JSONResponse
        return JSONResponse(
            status_code=403,
            content={"detail": "Access Denied: Automated scraping signatures detected."}
        )
    return await call_next(request)


@app.middleware("http")
async def admin_access_guard_middleware(request: Request, call_next):
    path = request.url.path
    if path in ["/metrics", "/metrics/", "/ai_status", "/ai_status/", "/api/v1/ai_status"]:
        import os
        client_ip = request.client.host if request.client else "unknown"
        is_private = False

        # Check standard private subnet prefixes
        if client_ip in ["127.0.0.1", "::1", "localhost", "testserver"]:
            is_private = True
        elif client_ip.startswith("10.") or client_ip.startswith("192.168."):
            is_private = True
        elif client_ip.startswith("172."):
            try:
                parts = client_ip.split(".")
                second_octet = int(parts[1])
                if 16 <= second_octet <= 31:
                    is_private = True
            except Exception:
                pass

        sre_token = request.headers.get("X-SRE-Token")
        expected_token = os.environ.get("SRE_SECURITY_TOKEN", "sre_bypass_secret_2026")

        if not is_private and sre_token != expected_token:
            from fastapi.responses import JSONResponse
            return JSONResponse(
                status_code=403,
                content={"detail": "Forbidden: Administrative access restricted to local subnet or valid SRE VPN context."}
            )
    return await call_next(request)


@app.middleware("http")
async def device_signature_middleware(request: Request, call_next):
    if request.method in ["POST", "PUT", "DELETE", "PATCH"]:
        import hmac
        import hashlib
        user_id = request.headers.get("X-User-Id") or request.query_params.get("user_id") or request.query_params.get("userId")
        if user_id:
            timestamp_str = request.headers.get("X-Device-Timestamp")
            signature = request.headers.get("X-Device-Signature")

            if not timestamp_str or not signature:
                from fastapi.responses import JSONResponse
                return JSONResponse(
                    status_code=401,
                    content={"detail": "Unauthorized: Missing device signature headers."}
                )

            try:
                timestamp = int(timestamp_str)
                # Check replay attack (5 minutes window)
                if abs(time.time() - timestamp) > 300:
                    from fastapi.responses import JSONResponse
                    return JSONResponse(
                        status_code=401,
                        content={"detail": "Unauthorized: Device signature timestamp expired."}
                    )

                # Verify HMAC
                device_secret = str(settings.database_encryption_key).encode("utf-8")
                payload = f"{user_id}:{timestamp_str}"
                expected = hmac.new(device_secret, payload.encode("utf-8"), hashlib.sha256).hexdigest()
                if not hmac.compare_digest(signature, expected):
                    from fastapi.responses import JSONResponse
                    return JSONResponse(
                        status_code=401,
                        content={"detail": "Unauthorized: Invalid device signature."}
                    )
            except Exception:
                from fastapi.responses import JSONResponse
                return JSONResponse(
                    status_code=401,
                    content={"detail": "Unauthorized: Invalid device signature format."}
                )
    return await call_next(request)


@app.middleware("http")
async def rate_limiting_middleware(request: Request, call_next):
    path = request.url.path
    if path in ["/", "/health", "/health/"]:
        return await call_next(request)

    client_ip = request.client.host if request.client else "unknown"

    # Path-specific rate limits
    strict_paths = ["/api/v1/agent/chat", "/api/v1/papers/search", "/api/v1/authors/search", "/api/v1/users/download-export", "/export"]
    is_strict = any(sp in path for sp in strict_paths)

    if is_strict:
        allowed = await rate_limiter.check_rate_limit(client_ip, capacity=5.0, refill_rate=5.0/60.0)
    else:
        allowed = await rate_limiter.check_rate_limit(client_ip, capacity=60.0, refill_rate=1.0)

    if not allowed:
        from fastapi.responses import JSONResponse
        return JSONResponse(
            status_code=429,
            content={"detail": "Too many requests. Please try again later."},
            headers={"Retry-After": "5"}
        )
    return await call_next(request)


class MetricsStore:
    def __init__(self):
        self.request_counts = {}  # (method, endpoint, status) -> count
        self.request_latency = {}  # (method, endpoint) -> [latencies]
        self.active_requests = 0  # Gauge
        self.background_tasks_active = 0  # Gauge
        self.openalex_api_requests_total = 0  # Counter
        self.error_counts = 0
        # Buckets for latency histogram: 0.005, 0.01, 0.025, 0.05, 0.075, 0.1, 0.25, 0.5, 0.75, 1.0, 2.5, 5.0, 7.5, 10.0
        self.latency_buckets = [0.005, 0.01, 0.025, 0.05, 0.075, 0.1, 0.25, 0.5, 0.75, 1.0, 2.5, 5.0, 7.5, 10.0]
        self.histogram_buckets = {}  # (method, endpoint, le) -> count
        self.lock = asyncio.Lock()

        # Outbound HTTP Client metrics (thread-safe for sync/async wrappers)
        import threading
        self.outbound_lock = threading.Lock()
        self.outbound_request_counts = {}
        self.outbound_request_latency = {}

    def record_outbound_request(self, host: str, status: int, latency_ms: float):
        latency_sec = latency_ms / 1000.0
        with self.outbound_lock:
            key = (host, str(status))
            self.outbound_request_counts[key] = self.outbound_request_counts.get(key, 0) + 1

            if host not in self.outbound_request_latency:
                self.outbound_request_latency[host] = []
            self.outbound_request_latency[host].append(latency_sec)
            if len(self.outbound_request_latency[host]) > 1000:
                self.outbound_request_latency[host].pop(0)

    async def increment_active_requests(self):
        async with self.lock:
            self.active_requests += 1

    async def decrement_active_requests(self):
        async with self.lock:
            self.active_requests = max(0, self.active_requests - 1)

    async def increment_background_tasks(self):
        async with self.lock:
            self.background_tasks_active += 1

    async def decrement_background_tasks(self):
        async with self.lock:
            self.background_tasks_active = max(0, self.background_tasks_active - 1)

    async def increment_openalex_requests(self):
        async with self.lock:
            self.openalex_api_requests_total += 1

    def increment_openalex_requests_sync(self):
        self.openalex_api_requests_total += 1

    async def record_request(self, method: str, endpoint: str, status: int, latency_ms: float):
        latency_sec = latency_ms / 1000.0
        async with self.lock:
            key = (method, endpoint, str(status))
            self.request_counts[key] = self.request_counts.get(key, 0) + 1

            lat_key = (method, endpoint)
            if lat_key not in self.request_latency:
                self.request_latency[lat_key] = []
            self.request_latency[lat_key].append(latency_sec)
            if len(self.request_latency[lat_key]) > 1000:
                self.request_latency[lat_key].pop(0)

            # Histogram buckets
            for bucket in self.latency_buckets:
                bucket_key = (method, endpoint, f"{bucket:.3f}")
                if latency_sec <= bucket:
                    self.histogram_buckets[bucket_key] = self.histogram_buckets.get(bucket_key, 0) + 1
            # Add +Inf bucket
            inf_key = (method, endpoint, "+Inf")
            self.histogram_buckets[inf_key] = self.histogram_buckets.get(inf_key, 0) + 1

            if status >= 400:
                self.error_counts += 1

metrics_store = MetricsStore()


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
        trace_id = request.headers.get("x-trace-id") or request.headers.get("x-request-id") or str(uuid.uuid4()).replace("-", "")

    # Set context variables temporarily so telemetry Span initialization reads them
    trace_id_token = trace_id_var.set(trace_id)

    request_id = request.headers.get("x-request-id") or trace_id
    request_id_token = request_id_var.set(request_id)

    # Try to extract user ID from query parameters
    user_id = request.query_params.get("user_id") or request.query_params.get("userId") or ""
    user_id_token = user_id_var.set(user_id)

    # Start a span for the router path
    from app.core.telemetry import tracer
    span_name = f"{request.method} {request.url.path}"

    await metrics_store.increment_active_requests()
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
                }
            )
            raise e
        finally:
            latency_ms = int((time.perf_counter() - start_time) * 1000)
            status_code = response.status_code if response else 500
            await metrics_store.record_request(request.method, request.url.path, status_code, latency_ms)
            await metrics_store.decrement_active_requests()

            logger.info(
                f"{request.method} {request.url.path} - {status_code} - {latency_ms}ms",
                extra={
                    "endpoint": request.url.path,
                    "method": request.method,
                    "status_code": status_code,
                    "latency_ms": latency_ms,
                }
            )
            if response:
                response.headers["X-Request-ID"] = request_id
                response.headers["traceparent"] = f"00-{span.trace_id}-{span.span_id}-01"
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
app.mount("/downloads", CacheControlledStaticFiles(directory=str(_DOWNLOADS_DIR)), name="downloads")

# Include aggregate router at both root (for direct client pings) and /api/v1 (for versioned compatibility)
app.include_router(api_router)
app.include_router(api_router, prefix="/api/v1")


@app.get("/")
async def root():
    """Root endpoint returning API metadata for mobile client verification and discovery."""
    return {"app": "Skolab API", "status": "online", "version": "1.0.0"}


@app.get("/health")
async def health():
    """Dynamic status check verifying database and cache connectivity."""
    db_status = "unhealthy"
    cache_status = "unhealthy"

    # Check Database
    from app.db.database import AsyncSessionLocal
    from sqlalchemy import text
    try:
        async with AsyncSessionLocal() as session:
            await session.execute(text("SELECT 1"))
            db_status = "healthy"
    except Exception as e:
        logger.error(f"Database health check failed: {e}")

    # Check Cache (PgBackedCache)
    try:
        await suggestions_cache.set("health_ping", "1")
        val = await suggestions_cache.get("health_ping")
        if val == "1":
            cache_status = "healthy"
    except Exception as e:
        logger.error(f"Cache health check failed: {e}")

    status_code = 200
    if db_status != "healthy" or cache_status != "healthy":
        status_code = 503  # Service Unavailable

    return Response(
        content=json.dumps({
            "status": "healthy" if status_code == 200 else "unhealthy",
            "database": db_status,
            "cache": cache_status
        }),
        media_type="application/json",
        status_code=status_code
    )


@app.get("/metrics")
async def metrics():
    """Prometheus metrics endpoint in plain text exposition format."""
    lines = []

    # http_requests_total
    lines.append("# HELP http_requests_total Total number of HTTP requests.")
    lines.append("# TYPE http_requests_total counter")
    for (method, endpoint, status), count in metrics_store.request_counts.items():
        lines.append(f'http_requests_total{{method="{method}",endpoint="{endpoint}",status="{status}"}} {count}')

    # http_active_requests
    lines.append("# HELP http_active_requests Current number of active HTTP requests.")
    lines.append("# TYPE http_active_requests gauge")
    lines.append(f"http_active_requests {metrics_store.active_requests}")

    # http_request_duration_seconds (Histogram)
    lines.append("# HELP http_request_duration_seconds HTTP request duration histogram.")
    lines.append("# TYPE http_request_duration_seconds histogram")
    for (method, endpoint), latencies in metrics_store.request_latency.items():
        if latencies:
            total_duration = sum(latencies)
            count = len(latencies)
            # Output buckets
            for bucket in metrics_store.latency_buckets:
                bucket_key = (method, endpoint, f"{bucket:.3f}")
                bucket_count = metrics_store.histogram_buckets.get(bucket_key, 0)
                lines.append(f'http_request_duration_seconds_bucket{{method="{method}",endpoint="{endpoint}",le="{bucket:.3f}"}} {bucket_count}')
            # Output +Inf
            inf_key = (method, endpoint, "+Inf")
            inf_count = metrics_store.histogram_buckets.get(inf_key, count)
            lines.append(f'http_request_duration_seconds_bucket{{method="{method}",endpoint="{endpoint}",le="+Inf"}} {inf_count}')
            # Sum and Count
            lines.append(f'http_request_duration_seconds_sum{{method="{method}",endpoint="{endpoint}"}} {total_duration:.6f}')
            lines.append(f'http_request_duration_seconds_count{{method="{method}",endpoint="{endpoint}"}} {count}')

    # background_tasks_active
    lines.append("# HELP background_tasks_active Current number of active background worker tasks.")
    lines.append("# TYPE background_tasks_active gauge")
    lines.append(f"background_tasks_active {metrics_store.background_tasks_active}")

    # openalex_api_requests_total
    lines.append("# HELP openalex_api_requests_total Total number of external requests to OpenAlex API.")
    lines.append("# TYPE openalex_api_requests_total counter")
    lines.append(f"openalex_api_requests_total {metrics_store.openalex_api_requests_total}")

    # system_errors_total
    lines.append("# HELP system_errors_total Total number of error responses.")
    lines.append("# TYPE system_errors_total counter")
    lines.append(f"system_errors_total {metrics_store.error_counts}")

    # host metrics
    import psutil
    import shutil
    try:
        cpu_usage = psutil.cpu_percent(interval=None)
        mem = psutil.virtual_memory()
        disk = shutil.disk_usage("/")

        lines.append("# HELP host_cpu_usage_percent Host CPU utilization percentage.")
        lines.append("# TYPE host_cpu_usage_percent gauge")
        lines.append(f"host_cpu_usage_percent {cpu_usage:.1f}")

        lines.append("# HELP host_memory_used_percent Host memory used percentage.")
        lines.append("# TYPE host_memory_used_percent gauge")
        lines.append(f"host_memory_used_percent {mem.percent:.1f}")

        disk_used_pct = (disk.used / disk.total) * 100
        lines.append("# HELP host_disk_used_percent Host disk capacity used percentage.")
        lines.append("# TYPE host_disk_used_percent gauge")
        lines.append(f"host_disk_used_percent {disk_used_pct:.1f}")
    except Exception as e:
        logger.error(f"Failed to gather host metrics: {e}")

    # outbound metrics
    lines.append("# HELP outbound_http_requests_total Total number of outbound HTTP requests.")
    lines.append("# TYPE outbound_http_requests_total counter")
    with metrics_store.outbound_lock:
        for (host, status), count in metrics_store.outbound_request_counts.items():
            lines.append(f'outbound_http_requests_total{{host="{host}",status="{status}"}} {count}')

    lines.append("# HELP outbound_http_request_duration_seconds Outbound HTTP request duration summary.")
    lines.append("# TYPE outbound_http_request_duration_seconds summary")
    with metrics_store.outbound_lock:
        for host, latencies in metrics_store.outbound_request_latency.items():
            if latencies:
                total_duration = sum(latencies)
                count = len(latencies)
                lines.append(f'outbound_http_request_duration_seconds_sum{{host="{host}"}} {total_duration:.6f}')
                lines.append(f'outbound_http_request_duration_seconds_count{{host="{host}"}} {count}')

    return Response(
        content="\n".join(lines) + "\n",
        media_type="text/plain; version=0.0.4"
    )
