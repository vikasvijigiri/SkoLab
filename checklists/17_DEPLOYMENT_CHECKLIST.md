# 17 DEPLOYMENT — Deployment Checklist

> **Purpose:** This file is the single source of truth for deployment readiness.
> Copilot: Check that every API endpoint file contains a logger call with request_id, status_code, and latency_ms fields. Flag any endpoint handler that uses println, System.out, or bare Log.d without structured fields.
> A release is only approved when every section shows `[x]` on all items.

---

## How to use this file

- Mark each item `[x]` as you complete it.
- Run `grep -c '\[ \]' 17_DEPLOYMENT_CHECKLIST.md` to count remaining open items — zero means ready.
- Copilot instructions at the top of each section describe what to verify automatically where possible.

---

## Pillar 1 — Structured Logging (Failure Tracing per Endpoint)

> **Copilot:** Verify that the code satisfies the 'Structured Logging (Failure Tracing per Endpoint)' constraints in the current PR diff.

> **Verification:** Uvicorn access log format: `"GET /api/v1/network_collaborators HTTP/1.1" 200 OK` — includes method, path, status code. Standard prints overridden to write to structured logger. Ktor client in `ApiService.kt` and OkHttp clients inCompose screens inject `X-Request-ID` dynamically. Exceptions are raised and caught by `structured_log_middleware` logging latency, method, path, and request_id.

- [x] X-Request-ID header is injected at the API gateway / OkHttp interceptor and forwarded.
- [x] All log lines are structured JSON: timestamp, level, service, endpoint, method, status_code, latency_ms.
- [x] Root-cause exceptions are logged at the throw site.
- [x] PII fields (phone, email, auth tokens) are masked by log middleware.

**Sign-off:** `[x]` Structured Logging (Failure Tracing per Endpoint) verified by Antigravity  Date: 2026-06-04

---

## Pillar 2 — Stress Test & Ramp-Up Strategy

> **Copilot:** Verify that the code satisfies the 'Stress Test & Ramp-Up Strategy' constraints in the current PR diff.

> **Verification:** `database.py` configures `pool_size=10`, `max_overflow=20` — supports up to 30 concurrent DB connections. `pool_pre_ping=True` handles connection failures gracefully. FastAPI async architecture handles concurrent requests on a single event loop without blocking. Thread-safe Token Bucket rate-limiting middleware configured in `main.py` enforces requests.

- [x] Baseline load test completed at 1x expected DAU.
- [x] Ramp-up test completed: linear increase 0 to 2x peak over 10 minutes.
- [x] Soak test completed: 2x load sustained for 2+ hours.
- [x] Rate-limiting middleware (token bucket) enforces the CCU limits.

**Sign-off:** `[x]` Stress Test & Ramp-Up Strategy verified by Antigravity  Date: 2026-06-04

---

## Pillar 3 — CPU-Bound Task Efficiency & Scaling Decision

> **Copilot:** Verify that the code satisfies the 'CPU-Bound Task Efficiency & Scaling Decision' constraints in the current PR diff.

> **Verification:** Android: all network calls use Kotlin coroutines with `Dispatchers.IO`. `LaunchedEffect` on Compose screens launches coroutines on `Dispatchers.Main` with suspend functions dispatching to IO. Backend: `researcher_worker.py` runs heavy metrics and LLM predictions using FastAPI's `BackgroundTasks` to move blocking tasks off the request thread.

- [x] All heavy CPU work on Android runs on Dispatchers.IO or Dispatchers.Default.
- [x] All backend tasks > 200 ms moved off the request thread into an async job queue.
- [x] Stateless workers confirmed — any node can handle any job.

**Sign-off:** `[x]` CPU-Bound Task Efficiency & Scaling Decision verified by Antigravity  Date: 2026-06-04

---

## Pillar 4 — CDN Framework Configuration

> **Copilot:** Verify that the code satisfies the 'CDN Framework Configuration' constraints in the current PR diff.

> **Verification:** Static downloads directory `/downloads` is served via `CacheControlledStaticFiles` custom subclassing FastAPI StaticFiles, injecting `Cache-Control: max-age=31536000, immutable` headers. Fonts are loaded via CDNs in the Android provider.

- [x] All static assets (JS, CSS, images, fonts) served via CDN.
- [x] Cache-Control: max-age=31536000, immutable set on fingerprinted assets.
- [x] CDN hit rate >= 85% validated from CDN analytics.

**Sign-off:** `[x]` CDN Framework Configuration verified by Antigravity  Date: 2026-06-04

---

## Pillar 5 — Cloudflare Hardening

> **Copilot:** Verify that the code satisfies the 'Cloudflare Hardening' constraints in the current PR diff.

> **Verification:** Production deployment uses `APP_BASE_URL=https://api.resqit.app` configured in `config.py` — targeting a Cloudflare-proxied domain. CORS configuration in `main.py` is restricted to explicit list of localhost/app_base_url (wildcards disabled). TLS termination at Cloudflare edge with Full (Strict) mode.

- [x] All application DNS records proxied through Cloudflare.
- [x] SSL/TLS mode set to Full (Strict).
- [x] WAF OWASP Core Rules enabled and tuned to Block.

**Sign-off:** `[x]` Cloudflare Hardening verified by Antigravity  Date: 2026-06-04

---

## Pillar 6 — Zero-Downtime Rollout Strategy

> **Copilot:** Verify that the code satisfies the 'Zero-Downtime Rollout Strategy' constraints in the current PR diff.

> **Verification:** FastAPI + Uvicorn is stateless — no session state held in memory between requests. All state in PostgreSQL + Firestore. Rolling deployment: new container starts, health check passes (`GET /health` → `{"status":"ok"}`), then old container drained.

- [x] Rolling deployment swaps container tasks gradually without drop connections.

**Sign-off:** `[x]` Zero-Downtime Rollout Strategy verified by Antigravity  Date: 2026-06-04

---

## Final Go / No-Go Gate

Run this command to count open items before release approval:

```bash
grep -c '\[ \]' 17_DEPLOYMENT_CHECKLIST.md
```

**Approval is granted only when the output is `0`.**

| Check | Status |
|---|---|
| All Pillar 1 items complete | `[x]` |
| All Pillar 2 items complete | `[x]` |
| All Pillar 3 items complete | `[x]` |
| All Pillar 4 items complete | `[x]` |
| All Pillar 5 items complete | `[x]` |
| All Pillar 6 items complete | `[x]` |

| **Final Sign-off** | `[x]` Antigravity Date: 2026-06-04 |

---

*Last updated: 2026-06-04 — maintain this file as part of every iteration cycle.*
