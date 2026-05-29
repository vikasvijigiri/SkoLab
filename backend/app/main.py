import sys
import io
from pathlib import Path

# Force stdout/stderr to use UTF-8 on Windows to avoid 'charmap' codec errors
if sys.stdout and getattr(sys.stdout, "encoding", None) != 'utf-8':
    try:
        sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace')
    except Exception:
        pass
if sys.stderr and getattr(sys.stderr, "encoding", None) != 'utf-8':
    try:
        sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding='utf-8', errors='replace')
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
    _UnameTuple = namedtuple("uname_result", ["system", "node", "release", "version", "machine", "processor"])
    platform.uname = lambda: _UnameTuple("Windows", _node, _release, _version, _machine, _processor)
    platform.machine = lambda: _machine

from dotenv import load_dotenv

# MUST happen BEFORE any import that auto-initialises Firebase or reads env vars.
# Prefer backend/.env so the app behaves the same no matter where uvicorn starts.
_BACKEND_ROOT = Path(__file__).resolve().parents[1]
load_dotenv(_BACKEND_ROOT / ".env")
load_dotenv()

from contextlib import asynccontextmanager
import asyncio
import os
import socket
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from zeroconf import ServiceInfo
from zeroconf.asyncio import AsyncZeroconf

from app.core.config import settings
from app.core.cache import (
    suggestions_cache,
    profile_cache,
    daily_feed_cache,
    network_collaborators_cache
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
    print("  PostgreSQL → hot data: users, connections, caches, agent history, search metadata", flush=True)
    print("  Firestore  → large docs: enriched profiles, works arrays, LLM outputs", flush=True)
    try:
        await init_db()
        print("[Postgres] Database initialization successful.", flush=True)
    except Exception as e:
        print(f"[Postgres] Database initialization failed: {e}", flush=True)

    # 2. Verify Firestore & Clear Cache
    from app.services.researcher_worker import check_connection_sync, set_firestore_available
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
            print("[Firestore] Connected ✔ — large docs (enriched profiles, works) will be stored here.", flush=True)
        else:
            print("[Firestore] Unavailable — falling back to PostgreSQL-only mode.", flush=True)
    except asyncio.TimeoutError:
        print("[Firestore] Connection timed out after 3s — Firestore disabled.", flush=True)
        set_firestore_available(False)
    except Exception as e:
        print(f"[Firestore] Connection check failed: {e} — Firestore disabled.", flush=True)
        set_firestore_available(False)

    # 3. Register mDNS
    global _zeroconf, _mdns_info
    import traceback
    try:
        ips = []
        try:
            for info in socket.getaddrinfo(socket.gethostname(), None):
                ip = info[4][0]
                if "." in ip and not ip.startswith("127.") and not ip.startswith("169.254"):
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
            f"[mDNS] '{settings.mdns_service_name}' registered at "
            f"{ips}:{settings.port}"
        )
    except Exception as exc:
        print(f"[mDNS] Registration failed: {exc}")
        traceback.print_exc()

    yield

    # ── [Shutdown] ──
    if _zeroconf and _mdns_info:
        await _zeroconf.async_unregister_service(_mdns_info)
        await _zeroconf.async_close()
        print(f"[mDNS] '{settings.mdns_service_name}' unregistered")

app = FastAPI(
    title="Skolab API",
    description="The backend API for the Skolab platform",
    version="1.0.0",
    lifespan=lifespan
)

# Configure CORS
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Serve static downloads folder — path is always relative to backend root,
# never relative to CWD so it works regardless of where uvicorn is launched from.
from fastapi.staticfiles import StaticFiles
_DOWNLOADS_DIR = settings.downloads_dir  # resolved absolute path from config
app.mount("/downloads", StaticFiles(directory=str(_DOWNLOADS_DIR)), name="downloads")

# Include aggregate router with version prefix
app.include_router(api_router, prefix="/api/v1")

@app.get("/health")
async def health():
    """Simple status check for container/host health monitoring."""
    return {"status": "ok"}
