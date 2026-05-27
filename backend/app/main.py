import sys
import io

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
from collections import namedtuple
# Monkey-patch platform to avoid blocking WMI queries in sandboxed environment
platform.machine = lambda: "AMD64"
platform.uname = lambda: namedtuple("uname_result", ["system", "node", "release", "version", "machine", "processor"])(
    "Windows", "localhost", "10", "10.0.19045", "AMD64", "Intel64 Family 6 Model 158 Stepping 10, GenuineIntel"
)

from dotenv import load_dotenv

# MUST happen BEFORE any import that auto-initialises Firebase or reads env vars
load_dotenv()

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

app = FastAPI(
    title="Skolab API",
    description="The backend API for the Skolab platform",
    version="1.0.0",
)

# Configure CORS
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Serve static downloads folder
from fastapi.staticfiles import StaticFiles
os.makedirs("downloads", exist_ok=True)
app.mount("/downloads", StaticFiles(directory="downloads"), name="downloads")

# Include aggregate router
app.include_router(api_router)

# ── mDNS service advertisement ───────────────────────────────────────────────
# The Android app uses NSD (Network Service Discovery) to find this server
# automatically — no IP address is ever hardcoded on either side.
_zeroconf: AsyncZeroconf | None = None
_mdns_info: ServiceInfo | None = None


@app.on_event("startup")
async def init_postgres() -> None:
    from app.db.database import init_db
    print("[Postgres] Initializing local database schema...", flush=True)
    try:
        await init_db()
        print("[Postgres] Database initialization successful.", flush=True)
    except Exception as e:
        print(f"[Postgres] Database initialization failed: {e}", flush=True)


@app.on_event("startup")
async def verify_firestore() -> None:
    """Check if Firestore connection is responsive. If not, bypass to fallback."""
    from app.services.researcher_worker import check_connection_sync, set_firestore_available
    import concurrent.futures
    
    print("[Firestore] Verifying Firestore connection on startup...", flush=True)
    
    # Wipe stale caches on startup to force fresh loading of updated schemas/authors
    try:
        await suggestions_cache.clear()
        await profile_cache.clear()
        await daily_feed_cache.clear()
        await network_collaborators_cache.clear()
        print("[Cache] All startup caches cleared successfully!", flush=True)
    except Exception as e:
        print(f"[Cache] Startup clear failed: {e}", flush=True)

    loop = asyncio.get_event_loop()
    try:
        executor = concurrent.futures.ThreadPoolExecutor(max_workers=1)
        future = loop.run_in_executor(executor, check_connection_sync)
        success = await asyncio.wait_for(future, timeout=3.0)
        set_firestore_available(success)
        executor.shutdown(wait=False)
    except asyncio.TimeoutError:
        print("[Firestore] Connection check timed out after 3.0s. Firestore is disabled.", flush=True)
        set_firestore_available(False)
    except Exception as e:
        print(f"[Firestore] Connection check failed: {e}. Firestore is disabled.", flush=True)
        set_firestore_available(False)


@app.on_event("startup")
async def register_mdns() -> None:
    """Advertise this server on the LAN so mobile clients can discover it."""
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
            name=settings.mdns_fqdn,
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


@app.on_event("shutdown")
async def unregister_mdns() -> None:
    """Clean up the mDNS advertisement on shutdown."""
    global _zeroconf, _mdns_info
    if _zeroconf and _mdns_info:
        await _zeroconf.async_unregister_service(_mdns_info)
        await _zeroconf.async_close()
        print(f"[mDNS] '{settings.mdns_service_name}' unregistered")
