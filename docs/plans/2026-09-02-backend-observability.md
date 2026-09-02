# Backend Observability — Sentry + liveness/readiness split Implementation Plan

**Goal:** Wire `sentry-sdk` for the FastAPI backend (inert until `SENTRY_DSN` is set) and split the health probe into `/livez` (dependency-free) + `/readyz` (DB+cache), keeping `/health` unchanged.

**Source spec:** `docs/specs/2026-09-02-backend-observability-design.md` (approach A)

**Slug:** backend-observability (branch: `feat/backend-observability`, off `main` @ `76e9b4b`)

**Risk:** LOW–MEDIUM — one new dependency (`sentry-sdk[fastapi]`), a guarded `init` that is a no-op without a DSN, and ~20 lines of new routes reusing `/health`'s existing probe. Touches `main.py` (imports + 3 route additions), no existing route body changes beyond deduplicating `/health` into a shared helper.

**Blast radius:** `services/backend/requirements.txt` (+1 pin), `app/core/config.py` (+1 field), `app/core/observability.py` (new), `app/main.py` (import + `init_observability()` call + `check_readiness()` helper + `/livez` + `/readyz`), `app/schemas/system.py` (+`LivenessResponse`), `tests/api/test_contract_guard.py` (`PERMANENT_ALLOWLIST` += `/readyz`), `docs/backend-auth-posture.md`, `.env.example`. Runtime: two new public routes; Sentry captures unhandled exceptions when a DSN is present.

**Rollback:** `git revert` the branch. Without a `SENTRY_DSN` the Sentry code path is inert, so a revert changes nothing operationally; `/livez`/`/readyz` disappear and `/health` returns to its inline form.

**Architecture:** Approach A from the spec. `init_observability()` in a new `app/core/observability.py` guards `sentry_sdk.init` on `settings.sentry_dsn` (mirrors the web app's `enabled: Boolean(dsn)`). `main.py`'s `/health` body is factored into `check_readiness() -> tuple[bool, dict]`; `/readyz` reuses it and returns a raw 200/503 `Response` (→ `PERMANENT_ALLOWLIST`), `/livez` returns a typed `{status: "alive"}` making **zero** dependency calls, `/health` calls the helper (behaviour byte-identical).

**Tech stack and constraints:**

- `services/backend` only. FastAPI 0.141.1, Python 3.10 (CI pin), Pydantic v2, dataclass `Settings`.
- **Sentry inert without a DSN.** No DSN in the repo; the agent never fills one in.
- **`/health` unchanged** for the Go gateway / existing clients.
- **`/livez` must make no DB/cache call.**
- New routes must satisfy SP-1's guards: `/livez` typed, `/readyz` in
  `PERMANENT_ALLOWLIST`; both are unauthenticated so `test_auth_posture.py`'s
  `public == remainder` still holds with no change to that file.
- Dev env has no runnable backend Python (3.14) — pytest verification is CI, as for PR #6/#7. `ruff` + `py_compile` run locally.
- New tests auto-collected by `ci.yml`'s `pytest tests/`.

## Route inventory delta

| Route | Before | After | Guard treatment |
|---|---|---|---|
| `/health` | inline DB+cache check → `Response` 200/503 | calls `check_readiness()`; body identical | already in `PERMANENT_ALLOWLIST` |
| `/livez` | — | `{status: "alive"}`, always 200, no deps | `response_model=LivenessResponse` (typed) |
| `/readyz` | — | `check_readiness()` → `Response` 200/503 | `PERMANENT_ALLOWLIST` (`# infra: raw Response, dynamic status`) |

## File map

| File | Action | Owns afterward |
|---|---|---|
| `services/backend/requirements.txt` | Modify | `+ sentry-sdk[fastapi]==2.42.0` (plan confirms the pin builds on 3.10) |
| `services/backend/app/core/config.py` | Modify | `Settings.sentry_dsn` field from `SENTRY_DSN` env |
| `services/backend/app/core/observability.py` | Create | `init_observability()` — guarded `sentry_sdk.init` |
| `services/backend/app/main.py` | Modify | imports `init_observability`; calls it before `app = FastAPI(...)`; `check_readiness()` helper; `@app.get("/livez")` + `@app.get("/readyz")`; `/health` calls the helper |
| `services/backend/app/schemas/system.py` | Modify | `+ LivenessResponse` |
| `services/backend/tests/api/test_contract_guard.py` | Modify | `PERMANENT_ALLOWLIST` gains `/readyz` |
| `services/backend/tests/api/test_health.py` | Create | `/livez` always-200 (even DB down), `/readyz` 200 in CI, `/health` shape unchanged |
| `services/backend/tests/test_observability.py` | Modify | `+ test_init_observability_is_inert_without_dsn` |
| `docs/backend-auth-posture.md` | Modify | `/livez`, `/readyz` added to the public table |
| `.env.example` | Modify | `SENTRY_DSN=` with a comment |

## Progress
- [ ] Task 1 — `sentry-sdk` dep + `observability.py` + `Settings.sentry_dsn` + `main.py` wiring
- [ ] Task 2 — `check_readiness()` helper + `/livez` + `/readyz` + `LivenessResponse` + guard allow-list
- [ ] Task 3 — docs: `backend-auth-posture.md` + `.env.example`

## Constitution gate
- [x] I Evidence — each task names its pytest / ruff / py_compile command and expected result
- [x] II Test first — T1 adds the inert-without-DSN test; T2 adds `test_health.py` (livez-up-while-db-down is the behavioural assertion) alongside the routes
- [x] III Smallest change — one dep, one config field, one new module, 3 routes; `/health` only deduplicated, not changed
- [x] IV Reversibility — no migration, no credential; revert is a no-op without a DSN
- [x] V No silent degradation — the contract guard is updated (not loosened): `/readyz` gets a *named* permanent allow-list entry; `/livez` is typed
- [x] VI Mechanism — `test_health.py` (livez independence) + `test_observability.py` (inert init) + the contract guard are the enforcement
- [x] VII Secrets — `SENTRY_DSN` is read from env only; `.env.example` ships it empty; no DSN in the repo or CI

## Complexity tracking
- All boxes ticked. No exceptions.

## Tasks

### Task 1: `sentry-sdk` dependency + `observability.py` + config field + `main.py` wiring
**Purpose:** unhandled exceptions are captured by Sentry when a DSN is set, and nothing changes when it is not
**Files:**
- Modify: `services/backend/requirements.txt` — add `sentry-sdk[fastapi]==2.42.0` under a `# Observability` comment. If pip cannot resolve it against Python 3.10 + the pinned deps, drop to the newest 2.x that does and note the version in the commit.
- Modify: `services/backend/app/core/config.py` — in `class Settings`, add `sentry_dsn: str = field(default_factory=lambda: os.environ.get("SENTRY_DSN", ""))` next to the other `os.environ.get` fields.
- Create: `services/backend/app/core/observability.py` — `import logging`, `import sentry_sdk`, `from sentry_sdk.integrations.fastapi import FastApiIntegration`, `from sentry_sdk.integrations.starlette import StarletteIntegration`, `from app.core.config import settings`. `logger = logging.getLogger("skolab")`. `def init_observability() -> None:` → `dsn = settings.sentry_dsn`; `if not dsn: logger.info("Sentry disabled — no SENTRY_DSN set"); return`; `sentry_sdk.init(dsn=dsn, environment=settings.environment, traces_sample_rate=<Gate-1 marker>, send_default_pii=False, integrations=[FastApiIntegration(), StarletteIntegration()])`; `logger.info("Sentry enabled (environment=%s)", settings.environment)`.
- Modify: `services/backend/app/main.py` — after `from app.core.config import settings` (line ~199), `from app.core.observability import init_observability`; call `init_observability()` on the next line (before the cache imports and well before `app = FastAPI(...)`).
- Modify: `services/backend/tests/test_observability.py` — add `test_init_observability_is_inert_without_dsn`: `monkeypatch.delenv("SENTRY_DSN", raising=False)`; reload `app.core.config` + `app.core.observability` OR construct a fresh `Settings()`; call `init_observability()`; assert it returns `None`, does not raise, and `sentry_sdk.get_client().is_active() is False` (2.x API) — if that helper is absent in the pinned version, assert `sentry_sdk.Hub.current.client is None`.
**Dependencies:** none
**Preconditions:** none.
**Rollback:** `git checkout` the 4 files; delete `observability.py`.
**Implementation notes:** `sentry_sdk.init` with a falsy `dsn` is itself a no-op, but the explicit `if not dsn` keeps the log line honest and avoids constructing integrations pointlessly. Do **not** register Sentry as ASGI middleware manually — `FastApiIntegration` hooks on `init`. This task touches `requirements.txt` so `parallel_groups` gives it its own round.
**Verification:**
- Run: `cd services/backend && python -m ruff check app/core/observability.py && python -m py_compile app/core/observability.py && python -m pytest tests/test_observability.py -q`
- Expect: ruff + py_compile exit 0 locally; pytest exit 0 in CI (`test_init_observability_is_inert_without_dsn` + the pre-existing tests pass)
**Done when:** `init_observability()` imports and is a proven no-op without a DSN; `sentry-sdk` is a pinned dependency.

### Task 2: `check_readiness()` + `/livez` + `/readyz` + `LivenessResponse` + guard allow-list
**Purpose:** a dependency-free liveness probe and a readiness probe, without changing `/health`
**Files:**
- Modify: `services/backend/app/schemas/system.py` — add `class LivenessResponse(BaseModel): status: str` (value is always `"alive"`).
- Modify: `services/backend/app/main.py`:
  - Add a module-level `async def check_readiness() -> tuple[bool, dict[str, str]]:` holding the exact DB (`AsyncSessionLocal` + `SELECT 1`) and cache (`suggestions_cache` set/get) checks currently inside `health()`; returns `(all_ok, {"database": db_status, "cache": cache_status})`.
  - `@app.get("/livez", response_model=LivenessResponse)` → `return {"status": "alive"}`. **No imports of `AsyncSessionLocal` / cache, no awaited dependency calls.**
  - `@app.get("/readyz")` → `ok, detail = await check_readiness()`; `return Response(json.dumps({"status": "ready" if ok else "not ready", **detail}), media_type="application/json", status_code=200 if ok else 503)`.
  - Rewrite `health()` to `ok, detail = await check_readiness()` then its existing `Response(...)` with body `{"status": "healthy" if ok else "unhealthy", "database": detail["database"], "cache": detail["cache"]}` and status `200 if ok else 503` — **byte-identical output to today**.
  - Import `LivenessResponse` from `app.schemas.system` (extend the existing `from app.schemas.system import AppInfoResponse` line).
- Modify: `services/backend/tests/api/test_contract_guard.py` — add `"/readyz"` to `PERMANENT_ALLOWLIST` with comment `# infra: raw Response, dynamic 200/503`.
- Create: `services/backend/tests/api/test_health.py`:
  - `test_livez_is_200_even_when_db_is_down(client, monkeypatch)` — monkeypatch `app.db.database.AsyncSessionLocal` to a callable that raises on use; `GET /livez` → 200 and body parses as `LivenessResponse` with `status == "alive"`; `GET /readyz` → 503.
  - `test_readyz_ok_in_ci(client)` — `GET /readyz` → 200, body has `database` and `cache` keys.
  - `test_health_body_unchanged(client)` — `GET /health` → 200 or 503, body keys are exactly `{status, database, cache}`.
**Dependencies:** 1
**Preconditions:** SP-1's `tests/api/conftest.py` `client` fixture and `_route_walk` helper are on `main` (they are, PR #7).
**Rollback:** `git checkout` `main.py`, `system.py`, `test_contract_guard.py`; delete `test_health.py`.
**Implementation notes:** `check_readiness()` must not raise — it swallows DB/cache errors into the status dict exactly as `health()` does now (keep the `except Exception` + `logger.error` blocks; these are genuine recovery, not the PR #6 anti-pattern). `/livez` returning a dict against `response_model=LivenessResponse` is fine (FastAPI validates the dict).
**Verification:**
- Run: `cd services/backend && python -m ruff check app/main.py app/schemas/system.py tests/api/test_health.py tests/api/test_contract_guard.py && python -m py_compile app/main.py app/schemas/system.py tests/api/test_health.py && python -m pytest tests/api/test_health.py tests/api/test_contract_guard.py tests/api/test_auth_posture.py -q`
- Expect: ruff + py_compile exit 0 locally; pytest exit 0 in CI (health tests pass; contract guard still green with `/readyz` allow-listed; auth-posture unaffected)
**Done when:** `/livez` answers 200 with the DB down, `/readyz` reflects readiness, `/health` output is unchanged, and both guards stay green.

### Task 3: docs — `backend-auth-posture.md` + `.env.example`
**Purpose:** the two new public routes are documented; the DSN env var is discoverable
**Files:**
- Modify: `docs/backend-auth-posture.md` — in the **Current posture** `public` row (or a note under the table), name `/livez` and `/readyz` as unauthenticated infra probes by design (liveness must not depend on auth or the DB; readiness reports dependency health).
- Modify: `.env.example` — add `SENTRY_DSN=` under a `# Observability — leave empty to disable Sentry` comment.
**Dependencies:** 1, 2
**Preconditions:** none.
**Rollback:** `git checkout` both files.
**Implementation notes:** follow `.claude/rules/markdown-style.md`. Keep the `.env.example` addition to two lines (comment + key).
**Verification:**
- Run: `grep -n "livez\|readyz" docs/backend-auth-posture.md && grep -n "SENTRY_DSN" .env.example`
- Expect: each grep prints ≥1 line
**Done when:** the doc lists both probes and `.env.example` carries the empty `SENTRY_DSN`.

## Verification (end to end)

1. `cd services/backend && python -m pytest tests/api/test_health.py tests/test_observability.py tests/api/test_contract_guard.py tests/api/test_auth_posture.py -q` → exit 0 (CI).
2. `python -m pytest tests/ -q` → full backend suite still green (no regression from the `/health` refactor or the new dep).
3. `python -m ruff check app && python -m ruff format --check app` → exit 0.
4. `python -c "from app.main import app; import json; json.dumps(app.openapi())"` → `/livez` present with `LivenessResponse`; `/readyz` present.
5. `grep -rn "SENTRY_DSN" .env.example services/backend/app/core/config.py` → both hits.
6. CI: `gh run list --branch feat/backend-observability` → `SkoLab CI Pipeline` green (backend job runs the new tests).
7. Manual (post-merge, backend env): set `SENTRY_DSN` to a real project DSN, start the app, trigger a 500 → event appears in Sentry; unset it → startup logs "Sentry disabled".

## Known risks / follow-ups

- **`sentry-sdk[fastapi]==2.42.0` may not resolve** against the pinned tree on Python 3.10. T1 falls back to the newest resolvable 2.x and records the version.
- **Sentry captures the exception before the PR #6 catch-all.** `FastApiIntegration` wraps the app at a lower layer than the exception handlers, so it should see the raw exception. If a CI/manual check shows it does not, add an explicit `sentry_sdk.capture_exception(exc)` inside the catch-all in `app/api/errors.py` (a one-line, in-scope follow-up).
- **`traces_sample_rate`** — Gate-1 marker; `0.0` recommended to protect the free-tier span budget.
- SP-3 (web Firestore-realtime) — its own spec, after this.

## Resolved at Gate 1

- **`traces_sample_rate` = `0.0`** (errors only — protects the free-tier span budget; recommended option).
- **Env var = `SENTRY_DSN`** (sentry-sdk's own default; separate project from the web app assumed).

## Approved

Gate 1 passed 2026-09-02. Both markers resolved with the recommended options. Proceed to implementation on `feat/backend-observability`.
