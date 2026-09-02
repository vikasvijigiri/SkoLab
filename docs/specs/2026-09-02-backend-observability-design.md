# Backend observability — Sentry + liveness/readiness split (design)

**Date:** 2026-09-02
**Status:** proposed — for `task-analysis` to scope, Gate 1 to approve
**Base:** `main` @ `76e9b4b` (PR #6 + #7 merged)
**Scope:** `services/backend` (FastAPI). This is **SP-2** of the 3-part backlog
(SP-1 auth-posture — merged; SP-3 web Firestore — later).

---

## 1. The problem

Two gaps in the backend's operability:

| Gap | Current state |
|---|---|
| **No error aggregation** | Unhandled exceptions hit the PR #6 catch-all → logged as JSON to stdout with `request_id`/`trace_id`, and that is all. No grouping, no alert, no trend. `.claude/rules/error-monitoring.md`: "a production app without error monitoring is not production-grade." `sentry-sdk` is not a dependency. |
| **`/health` conflates liveness and readiness** | The one `/health` route checks DB + `PgBackedCache` and returns 200/503. A deploy platform (k8s, Render, Fly) needs **two** probes: *liveness* (is the process wedged? → restart) must **not** depend on the DB, or a DB blip restarts healthy pods in a loop; *readiness* (should traffic route here? → drain) must. There is no dependency-free probe today. |

The repo already has: structured JSON logging with trace context
(`app/main.py` `JSONFormatter`), `trace_id`/`span_id` contextvars
(`app/core/telemetry.py`), a Prometheus `/metrics` endpoint, and the PR #6
`ErrorResponse` envelope. The missing pieces are **error aggregation** and a
**dependency-free liveness probe** — not a whole new telemetry stack.

## 2. Constraints

- **Sentry inert until a DSN is set.** Mirror the web app
  (`apps/web/src/sentry.server.config.ts`): `enabled: Boolean(dsn)`. No DSN in
  the repo, no DSN filled in by the agent (`.claude/rules/error-monitoring.md`).
- **`/health` stays, unchanged, for back-compat.** Existing clients / the Go
  gateway may probe it.
- **`/livez` must not touch the DB or cache.** That is the whole point.
- **New routes must satisfy SP-1's guards.** `test_auth_posture.py`: `/livez`
  and `/readyz` are unauthenticated → they land in `public` automatically and
  `test_public_is_the_remainder` still holds (they are not in `EXPECTED_AUTHED`
  / `EXPECTED_OPTIONAL`). `test_contract_guard.py`: each needs a `response_model`
  **or** a `PERMANENT_ALLOWLIST` entry with a reason.
- Python 3.10 CI pin, Pydantic v2, dataclass `Settings` in `app/core/config.py`.
- `docs/backend-auth-posture.md` (SP-1) must be updated to list the two new
  public routes.

## 3. Approaches considered

### A — Sentry via a guarded init + a 3-route health split (chosen)

- `app/core/observability.py` (new): `init_observability()` reads
  `settings.sentry_dsn`; if truthy, `sentry_sdk.init(dsn=..., environment=...,
  traces_sample_rate=<see marker>, integrations=[FastApiIntegration(),
  StarletteIntegration()])`; if falsy, does nothing and logs one line at INFO
  ("Sentry disabled — no DSN"). Called once from `main.py` near the other
  early init.
- `Settings.sentry_dsn: str = field(default_factory=lambda:
  os.environ.get("SENTRY_DSN", ""))`.
- `requirements.txt`: `sentry-sdk[fastapi]==<pinned>` (plan resolves the exact
  version against the 3.10 wheel; `[fastapi]` extra pulls the Starlette/FastAPI
  integrations).
- **Health split:** extract `/health`'s DB+cache probe into
  `check_readiness() -> tuple[bool, dict]` (module-level in `main.py`, or in
  `observability.py`). Then:
  - `GET /livez` → `response_model=LivenessResponse` (`{status: "alive"}`),
    always 200, **no dependency calls**.
  - `GET /readyz` → calls `check_readiness()`, returns a raw `Response`
    200/503 with the same body shape `/health` uses today. `PERMANENT_ALLOWLIST`
    entry (`# infra: raw Response, dynamic status`).
  - `GET /health` → now also calls `check_readiness()` (identical behavior,
    zero client-visible change) — just deduplicated.
- Tests: `tests/api/test_health.py` — `/livez` 200 + parses `LivenessResponse`
  and makes **no DB call** (monkeypatch `AsyncSessionLocal` to explode, assert
  `/livez` still 200 while `/readyz` 503); `/readyz` 200 in CI (DB+cache up).
  `tests/test_observability.py` (exists) — add: `init_observability()` with no
  `SENTRY_DSN` does not raise and leaves `sentry_sdk.Hub.current.client` unset.

**Wins:** ~60 LOC + one dep; fills exactly the two gaps; reuses the existing
`/health` probe logic; deploy-probe-correct.
**Loses:** no OTel spans / distributed tracing beyond what `telemetry.py`
already does; Sentry perf tracing is minimal by design (see marker).

### B — Full OpenTelemetry + Sentry + Prometheus enrichment

Add `opentelemetry-*`, an OTLP exporter, span middleware, richer `/metrics`.

**Wins:** vendor-neutral traces, RED metrics.
**Loses:** large dependency surface (OTel is ~8 packages), a collector to run
or a vendor to configure, and it overlaps `telemetry.py` and `/metrics` which
already exist. Disproportionate for "we have no error grouping."

### C — Sentry only, defer the health split

Just wire Sentry; leave `/health` as the single probe.

**Loses:** the liveness/readiness distinction is the concrete
deploy-readiness item. Deferring it means the first real deployment
(a Phase-3 concern) re-opens this. The split is ~15 lines now.

## 4. Chosen — A, and why

- **Over B:** the repo already has trace context, a metrics endpoint, and
  structured logs. The real gap is *error aggregation* (Sentry) and a
  *dependency-free liveness probe*. OTel would add a collector, ~8 packages,
  and overlap what exists — cost far above the gap.
- **Over C:** `/livez` vs `/readyz` is the thing that makes the app safe to put
  behind an orchestrator; it is 15 lines and it reuses `/health`'s existing
  probe. No reason to split it into its own later cycle.

**What would change the choice:** if a deployment target is picked that ships
its own OTel-native APM (e.g. Grafana Cloud, already an MCP server in this
layer), B's exporter becomes worth it — revisit at Phase 3.

**What A gives up:** distributed tracing across the Go gateway ↔ Python
boundary (Sentry can do this with trace propagation headers, but wiring the Go
side is out of scope here); rich performance monitoring.

## 5. Design

### 5.1 `app/core/observability.py`

- `init_observability() -> None`:
  - `dsn = settings.sentry_dsn`
  - `if not dsn: logger.info("Sentry disabled — no SENTRY_DSN set"); return`
  - `sentry_sdk.init(dsn=dsn, environment=settings.environment,
    traces_sample_rate=<marker>, send_default_pii=False,
    integrations=[FastApiIntegration(), StarletteIntegration()])`
  - `logger.info("Sentry enabled for environment=%s", settings.environment)`
- Import is lazy-safe: `sentry_sdk` import at module top is fine (it is a
  dependency); `init` is the only side-effecting call and it is guarded.

### 5.2 `main.py` wiring

- `from app.core.observability import init_observability` near the other
  `app.*` imports.
- Call `init_observability()` **before** `app = FastAPI(...)` (so the SDK's
  ASGI/error hooks wrap the app), or immediately after — plan picks the exact
  line; the `FastApiIntegration` attaches on `init`, order-independent for
  error capture but cleaner before.
- No change to the two existing `@app.middleware("http")` blocks or the PR #6
  exception handlers — Sentry's integration captures unhandled exceptions
  itself; the catch-all still returns the generic 500 envelope. (Verify in the
  plan that Sentry sees the exception *before* the catch-all swallows it — if
  not, add an explicit `sentry_sdk.capture_exception` in the catch-all.)

### 5.3 Health routes

- `check_readiness() -> tuple[bool, dict[str, str]]` — the current `/health`
  body, factored out. Returns `(ok, {"database": ..., "cache": ...})`.
- `@app.get("/livez", response_model=LivenessResponse)` → `{"status": "alive"}`.
- `@app.get("/readyz")` → `ok, detail = check_readiness()`; `Response(json,
  200 if ok else 503)`.
- `@app.get("/health")` → same as `/readyz` (call `check_readiness()`), body
  unchanged.
- `LivenessResponse(BaseModel)` in `app/schemas/system.py` (SP-1's file).

### 5.4 Guards + docs

- `tests/api/test_contract_guard.py::PERMANENT_ALLOWLIST` += `/readyz`
  (`# infra: raw Response, dynamic 200/503`). `/livez` is typed, not
  allow-listed. `/health` is already allow-listed.
- `docs/backend-auth-posture.md` — add `/livez`, `/readyz` to the public table
  with a one-line "infra probe, unauthenticated by design" note.
- `.env.example` — add `SENTRY_DSN=` (empty) with a comment.

### 5.5 Tests

- `tests/api/test_health.py` (new):
  - `test_livez_is_always_200_even_when_db_down` — monkeypatch
    `app.db.database.AsyncSessionLocal` to raise; `GET /livez` → 200
    `LivenessResponse`; `GET /readyz` → 503.
  - `test_readyz_ok_in_ci` — `GET /readyz` → 200 (CI Postgres + cache up).
  - `test_health_unchanged` — `GET /health` body still has
    `status`/`database`/`cache` keys.
- `tests/test_observability.py` (exists) — add
  `test_init_observability_is_inert_without_dsn`: with `SENTRY_DSN` unset,
  `init_observability()` returns `None`, does not raise, and
  `sentry_sdk.Hub.current.client is None`.
- No test asserts Sentry *sends* anything (no DSN in CI; never mock a live
  send).

## 6. Out of scope

- Choosing / configuring a real Sentry project or DSN (user, via MCP auth).
- The Go gateway's own observability / trace propagation.
- OpenTelemetry, an APM vendor, `/metrics` enrichment, log shipping.
- Readiness-gating the app at startup (refusing traffic until `init_db`
  succeeds) — a Phase-3 deployment concern.
- SP-3 (web Firestore).

## 7. Open markers (batched at Gate 1)

1. §5.1 — `traces_sample_rate`: `0.0` (errors only — protects the 5M-spans/month
   free-tier budget; recommended), `0.1` (mirror the web app), or another value?
2. §5.3 — env var name `SENTRY_DSN` (recommended, matches sentry-sdk's own
   default env var) vs `BACKEND_SENTRY_DSN` (namespaced, since the web app uses
   `NEXT_PUBLIC_SENTRY_DSN` — a different project/DSN is likely wanted anyway)?
