# Retire the hand-rolled infra in `services/backend/app/main.py`

- **Status:** implemented on `feat/infra-metrics-ratelimit-to-go`
- **Date:** 2026-09-04
- **Rule in force (user, this session):** every non-LLM concern leaves the
  Python FastAPI service; Python is for LLM / model-inference only.
- **Base:** `origin/main` = `02001fe`

## Goal

`app/main.py` carried ~200 lines of non-LLM infrastructure the Go gateway
already owns or should own: a per-process token-bucket rate limiter, a
per-process Prometheus `MetricsStore` + `GET /metrics`, and three
`security_guard_middleware` guards. Remove what the gateway owns, keep what is
genuinely liveness/observability, and record every call.

## Keep / delete / move decisions

| Component | Decision | Reason | What replaces it |
|---|---|---|---|
| `TokenBucket` class | **delete** | Per-process token bucket; wrong under >1 worker. Only wired into the Python rate-limit guard. | `internal/middleware.NewRateLimiter` (per-IP, `golang.org/x/time/rate`) — applied globally in `services/backend-go/main.go:56`. |
| `RateLimiter` class + `rate_limiter` singleton | **delete** | Same; the Redis path was best-effort and the in-memory fallback is per-process. | Same gateway limiter. Gateway also has a tighter 5 rps limiter on `/api/v1/recommendations/*`. |
| `security_guard_middleware` — rate-limit block (guard 4) | **delete** | Redundant with the gateway limiter; `/agent/chat` "5/min" was the only real strict path and it is now bounded by the gateway's global limit. | Gateway limiter. A per-route strict limit for `/agent/chat` on the gateway is a follow-up, not this change. |
| `MetricsStore` class + `metrics_store` singleton | **delete** (replaced by a 6-line no-op shim, see below) | Per-process counters/histograms; meaningless to scrape one of N workers. | `services/backend-go` request path is where gateway-level request metrics belong. Gateway has **no Prometheus endpoint yet** — see `[NEEDS DECISION]`. |
| `structured_log_middleware` — `metrics_store.increment_active_requests` / `record_request` / `decrement_active_requests` | **delete those 3 calls only** | They fed `MetricsStore`. | Nothing — the gateway counts requests. |
| `structured_log_middleware` — structured JSON log line, `traceparent`, `X-Request-ID`, telemetry span | **keep** | This is observability (structured logs + W3C trace context), not a metrics store. Clients and Promtail depend on the JSON log shape. | unchanged |
| `GET /metrics` endpoint | **delete** | Prometheus text exposition built from the per-process `MetricsStore`. | `infrastructure/prometheus.yml` repointed at the gateway `:8080` with a `[NEEDS DECISION]` marker (gateway endpoint not built yet). |
| `security_guard_middleware` — kill-switch guard (guard 1) | **keep** in Python | Genuine SRE control (`KILL_SWITCHES=feature` → 503 without redeploy); the gateway has no equivalent. Removing it is a capability regression. | unchanged. Follow-up: move to the gateway so it also covers Go-native routes. |
| `security_guard_middleware` — admin-access guard (guard 2, `/metrics` + `/ai_status` subnet/SRE-token gate) | **delete** | Its only real target was `/metrics`, now removed. It also gated `/api/v1/ai_status`, which is otherwise a public system-status route. | Nothing. See `[NEEDS DECISION]` on `ai_status`'s `key_prefix` field. |
| `security_guard_middleware` — device-signature guard (guard 3) | **delete** | Theatre. HMAC key is `settings.database_encryption_key` — a server-only secret no client can hold, so no legitimate client could ever produce a valid signature. No Android/web client sends `X-Device-Signature` / `X-Device-Timestamp` / `X-User-Id`. Guards no real Python route (all mutating Python routes are LLM: `agent/chat`, `agent/upload_document`, `discovery/*`, `user_memory/events` — none keyed by `?user_id=`). `POST /users/{id}/export` in the test does not exist as a route. | Firebase ID token (`Authorization: Bearer`) verified by `auth.VerifyUser` (gateway) / `get_verified_user` (Python) — the real write auth. See `[NEEDS DECISION]`. |
| `livez` / `readyz` / `health` | **keep** | Liveness/readiness probes, not metrics. `/health` stays byte-identical for the gateway and existing clients. | unchanged |

### The `metrics_store` no-op shim

`app/api/v1/endpoints/authors.py` (owned by a **parallel stream** — out of
scope for this branch) still calls
`metrics_store.increment_background_tasks()` /
`decrement_background_tasks()` from its `track_teleport_researcher`
background task, and that import is not wrapped in `try/except`. Deleting
`metrics_store` outright would break that stream's file at runtime.

Mitigation: `main.py` keeps a 6-line `_BackgroundTaskGauge` shim bound to the
name `metrics_store`, exposing only those two async methods (plus the
`background_tasks_active` int they touch). `telemetry.py`'s `metrics_store`
uses are removed in this branch. Once the authors stream drops its gauge
calls, the shim can be deleted. Flagged `[NEEDS DECISION]`.

## File changes

| File | Change |
|---|---|
| `services/backend/app/main.py` | Delete `TokenBucket`, `RateLimiter`, `rate_limiter`, `MetricsStore`, the `GET /metrics` route. Slim `security_guard_middleware` to the kill-switch guard only. Drop the 3 `metrics_store` calls from `structured_log_middleware`. Add the `_BackgroundTaskGauge` shim. Update the FastAPI `description` (drop the stale per-route rate-limit copy) and the `system` tag description. |
| `services/backend/app/core/telemetry.py` | Remove the `metrics_store` import + `increment_openalex_requests_sync` / `record_outbound_request` calls from `_traced_async_send` and `_traced_sync_send`; keep span creation + `traceparent` injection. |
| `services/backend/tests/test_observability.py` | Drop `metrics_store` from the import. Remove `test_metrics_endpoint`, `test_openalex_api_requests_metric_increment`, `test_background_tasks_metrics_tracking`. Keep the Sentry, `/health`, and kill-switch tests. |
| `services/backend/tests/test_monitoring.py` | Remove `test_metrics_endpoint_host_metrics`, `test_outbound_metrics_collection`. Keep `test_status_endpoint`. |
| `services/backend/tests/test_threat_modeling.py` | Remove `from app.main import rate_limiter` and the `rate_limiter.buckets.clear()` fixture lines (keep `engine.dispose()`). Replace `test_admin_access_guard` → `test_metrics_endpoint_is_gone` (404). Replace `test_device_signature_validation` → `test_device_signature_headers_are_ignored` (no 401 from middleware). Replace `test_path_specific_rate_limiting` → `test_python_no_longer_rate_limits` (no 429 from Python). |
| `services/backend/tests/api/test_contract_guard.py` | Remove `/metrics` from `PERMANENT_ALLOWLIST`; update the docstring. |
| `services/backend/tests/api/test_auth_posture.py`, `tests/api/_route_walk.py` | Docstring-only: drop the `/metrics` mention. |
| `api-contracts/openapi.snapshot.json` | Regenerate (route set changed — `/metrics` gone). |
| `infrastructure/prometheus.yml` | Repoint the scrape target from `host.docker.internal:8000` (Python) to `:8080` (gateway) with a `[NEEDS DECISION]` comment: the gateway `/metrics` endpoint is not built yet. |
| `docs/backend-auth-posture.md` | Drop `/metrics` from the infra-probe row; note the admin-subnet gate was removed with it. |
| `docs/threat_model.md` | STRIDE map: mark the device-signature and admin-IP-gate rows as retired; rate-limiting is now the gateway's. |

## Out of scope (constraints)

- `authors.py`, `feed.py`, `pipeline/*`, `internal/author/*` — other streams.
- `main.go` — other streams edit it; no gateway `/metrics` added here (see below).
- `infrastructure/alertmanager-alerts.yml` — comments reference `/metrics`
  metric names; left as a follow-up once the gateway endpoint exists.

## `[NEEDS DECISION]`

1. **Gateway Prometheus endpoint.** The Go gateway has no `/metrics` and no
   `prometheus/client_golang` dependency (`internal/services/metrics` is the
   "10 research metrics" math package, unrelated). No Go toolchain is
   available in this dev environment to add and compile-check a new
   dependency, and `main.go` is edited by other streams. Recommendation: a
   follow-up task adds a minimal `promhttp` handler + gin route. Until then
   Prometheus has no gateway target — `infrastructure/prometheus.yml` carries
   the marker.
2. **Device-signature guard — removed.** Assessed as theatre (server-only
   HMAC key, no client sends it, guards no real route). Removed rather than
   kept-with-flag because the evidence is unambiguous. Reviewer should
   confirm no private deployment tooling relied on it.
3. **`metrics_store` shim.** Kept only for the parallel `authors.py` stream.
   Delete once that stream drops its `increment_background_tasks` calls.
4. **`GET /api/v1/ai_status` is now public** (admin gate removed). Its
   response includes `key_prefix` = first 7 chars of `GROQ_API`. Minor
   info-disclosure; dropping that field is a one-line change in
   `app/api/v1/endpoints/system.py` (LLM-status route, left untouched here).
