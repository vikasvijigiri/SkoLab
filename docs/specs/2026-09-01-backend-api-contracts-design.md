# services/backend — API Contracts & Error Handling (design)

**Date:** 2026-09-01
**Status:** proposed — for `writing-plans` to scope, Gate 1 to approve
**Scope:** `services/backend` (FastAPI). Go gateway, Android, web, and the
`.claude/` layer are out of scope. Auth audit, Sentry, health-split, deploy
are **later phases** of the backend world-class pass.

---

## 1. The problem

The backend works, but its HTTP surface is not contract-grade:

| Dimension | Current | Evidence |
|---|---|---|
| Typed responses | **7 of 72 files** use `response_model=`; most of the ~37 routes return raw `dict` | `api/v1/endpoints/papers.py` (`summarize_work` raw, `analyze_paper` typed) |
| Response models | ~15 good Pydantic models already exist, just not all wired | `app/schemas/core.py` |
| Error handling | Repeated `except HTTPException: raise; except Exception as e: raise HTTPException(500, str(e))` in **8 endpoint files** — and `str(e)` **leaks the internal exception message to the client** | `papers.py:26`, `feed.py`, `authors.py`, … |
| Global handlers | **None.** No `@app.exception_handler` in `main.py` | `app/main.py` |
| Pagination | 2–3 endpoints, inconsistent (`Query(10)` vs `Query(10, ge=1, le=30)`); most list routes return unbounded arrays | `authors.py:697`, `papers.py:88` |
| API doc | `api-contracts/openapi.yaml` is a hand-kept stub covering ~6 of 37 routes (~16%); drifts every change | `LOG.md` 07-19 |

## 2. Constraints that shape the approach

- **The services are fragile** — heterogeneous data pools, `except Exception` in
  endpoints turning any escape into a 500 (`MEMORY.md`, `ISSUES.md` 08-08). A
  cross-cutting service refactor here is high-risk.
- **FastAPI already generates `/openapi.json`** from the code — a second
  hand-authored copy is the exact drift the current stub demonstrates.
- **Python 3.10**, pinned in CI. Pydantic v2. SQLAlchemy async.
- The backend suite now runs in CI (PR #3: `ci_bootstrap_db.py` + alembic).
  New tests plug into that (`services/backend/tests/`, pytest, `asyncio_mode=auto`).
- **No response-shape change may break the web or Android clients** — a wired
  `response_model` must match what the endpoint returns today (extra fields are
  dropped by `response_model` unless `response_model_exclude_unset`, so each
  wiring is verified against a real/mocked response).
- Most research endpoints are deliberately unauthenticated (public OpenAlex
  data); this spec does **not** change which routes require auth — that is the
  Phase-2 auth audit.

## 3. Approaches considered

### A — Incremental wiring (chosen)

Keep the structure. Per route: add `response_model=` (create the ~10 missing
models in `app/schemas/`), delete the per-endpoint `except Exception`, add two
app-level exception handlers, add a shared `PaginationParams` dependency + a
`Page[T]` envelope for list routes. Add a `tests/api/` suite that iterates the
route table. Delete the hand-kept `openapi.yaml`; add a CI check that
`app.openapi()` builds and snapshot it.

- **Wins:** ~90% of the contract/error value; diff is reviewable route-by-route;
  the fragile service internals are untouched; the OpenAPI schema becomes real
  and generated.
- **Loses:** error mapping still lives partly at the edge (the global handler is
  a net, not a `Result` type); `schemas/core.py` stays one file.

### B — Schemas + service-layer refactor

Split `schemas/core.py` into `schemas/<domain>.py`; introduce a
`Result[T]` / `ServiceError` type that services return and the API layer maps to
HTTP, removing error handling from endpoints entirely; consolidate `deps.py`.

- **Wins:** the correct long-term layering — errors classified at the source,
  endpoints become pure mapping.
- **Loses:** touches **every** service and endpoint at once, against code the
  recon already calls fragile. A regression here is a production 500, not a lint
  error. Large, hard to review incrementally.

### C — OpenAPI-first + codegen

Hand-author the complete OpenAPI spec as the source of truth; generate
request/response models and a typed client; CI-diff `app.openapi()` against it.

- **Wins:** strongest contract discipline; a spec independent of the impl.
- **Loses:** fights FastAPI's own generation; two copies of the schema is the
  drift problem restated; heavy tooling for a single-team internal API.

## 4. Chosen direction — A, and why it beat the others

- **Over B:** B's layering is right *eventually*, but doing it now means a
  cross-cutting rewrite of fragile services with production-500 blast radius,
  and it can't be reviewed route-by-route. A locks the *contract surface* first
  (typed in, typed out, no leaked internals); B becomes a cleaner, safer plan
  once every route has a `response_model` a refactor must preserve.
- **Over C:** FastAPI already produces `/openapi.json` from the code. The stale
  `openapi.yaml` isn't a missing-spec problem, it's a *two-copies* problem — so
  the fix is to delete the copy and CI-snapshot the generated one, which A does.

**What would change the choice:** if a `debugging` pass shows the cascading 500s
need `Result`/`ServiceError` classification to be fixed at all (not just
contained by a global handler), B jumps the queue for the error-handling half.

**What A gives up:** endpoints still each `return` a shaped value rather than a
typed `Result`; one `schemas/core.py`; no generated client.

## 5. Design (what `writing-plans` scopes)

### 5.1 Typed responses

- Inventory every route (`app.routes`) → the ~30 without `response_model`.
- For each: if a matching model exists in `schemas/core.py`, wire it; else add a
  model (named `<Thing>Response`, `BaseModel`, explicit fields, sensible
  defaults matching current behavior).
- Verify each wiring against a real or MSW-style mocked handler response so no
  field the clients read is silently dropped. Endpoints returning a bare list →
  `response_model=list[<Model>]` or the `Page[T]` envelope (5.3).
- `summarize_work` and any other raw-`dict` route get a model or an explicit
  `# raw by design:` comment with the reason.

### 5.2 Global exception envelope

- `app/api/errors.py` (new): an `ErrorResponse` model
  `{ detail: str, code: str, request_id: str }`.
- In `main.py`, register:
  - `RequestValidationError` → 422 `ErrorResponse(code="validation_error")`,
    body includes the field errors.
  - `HTTPException` → pass the status through, wrap the detail in `ErrorResponse`.
  - `Exception` (catch-all) → **log the real exception server-side** (existing
    JSON logger + `request_id`/`trace_id`), return **500
    `ErrorResponse(detail="Internal server error", code="internal_error")`** —
    the internal message never reaches the client.
- Delete the per-endpoint `try/except HTTPException/except Exception` blocks; a
  route raises `HTTPException` for real 4xx it owns and lets everything else hit
  the catch-all. (Keep a `try/except` only where the endpoint genuinely recovers
  — e.g. a degraded fallback — with a comment.)

### 5.3 Pagination

- `app/api/pagination.py` (new): `PaginationParams` dependency
  (`limit: int = Query(20, ge=1, le=100)`, `offset: int = Query(0, ge=0)`) and a
  generic `Page[T]` model `{ items: list[T], total: int, limit: int, offset: int }`.
- Apply to the list endpoints that currently return unbounded arrays or have
  ad-hoc `limit`/`offset` (`authors.py`'s similar-researchers list, papers
  search, feed, leaderboard-ish routes). Endpoints whose result is inherently
  small and fixed (a single profile, a daily conjecture) are left as-is with a
  one-line note.

### 5.4 Tests

- `services/backend/tests/api/` — one module per router tag. For each route:
  - a happy-path call via `httpx.ASGITransport` / `TestClient` asserting the
    response **validates against its `response_model`** (Pydantic parse the body);
  - the validation-error path (bad/missing required query param → 422
    `ErrorResponse`);
  - for the 4 authed routes: no token → 401/403 `ErrorResponse`.
- One test asserting **every route in `app.routes` has a non-`None`
  `response_model`** (or is on an allow-list with a reason) — the mechanism that
  keeps 5.1 from regressing.
- Mock the external boundary (OpenAlex, Groq/OpenRouter) — never call them in a
  test.

### 5.5 OpenAPI

- Delete `api-contracts/openapi.yaml`.
- Add `services/backend/tests/test_openapi.py`: `app.openapi()` builds without
  error, has an `info.version`, and every path has at least one documented
  response. Optionally snapshot `app.openapi()` to
  `api-contracts/openapi.snapshot.json` and diff in CI so a contract change is a
  visible reviewable diff.
- Update `README.md` §"Project Documentation" and any `AGENTS.md` reference that
  points at the deleted file → point at `/openapi.json` / `/docs`.

## 6. Error handling, data flow, testing

- **Data flow:** request → route (typed params, `PaginationParams` where a list)
  → service (unchanged) → route `return`s a value FastAPI validates against
  `response_model` → client. Errors: `HTTPException` for owned 4xx; anything else
  → catch-all handler → logged server-side, generic 500 to client.
- **Testing:** the `tests/api/` suite + the two guard tests (every-route-typed,
  openapi-builds) run in the existing `ci.yml` backend job.

## 7. Out of scope (this spec)

- Which routes require auth (Phase 2 — auth audit).
- Sentry, `/livez` vs `/readyz` split, readiness gating (Phase 2 — observability).
- Deploy manifest / target (Phase 3).
- The `Result`/`ServiceError` service refactor (approach B — a later plan once
  the contract surface is locked).
- Splitting `schemas/core.py` into per-domain files (cosmetic; fold into B).
- Any change to service internals, caching, the recommendation pipeline, or the
  Go gateway.
- Response-shape *changes* — a `response_model` must match today's output.

## 8. Open markers (batched at Gate 1)

1. §5.5 — delete `api-contracts/openapi.yaml` and rely on generated
   `/openapi.json` + a CI snapshot (this spec's choice), or keep it and backfill
   it by hand to 100%?
2. §5.3 — pagination envelope: `{items, total, limit, offset}` (chosen, matches
   existing params) vs a richer `Page[T]` with `has_next`/`next_offset` vs
   cursor-based?
3. §5.2 — delete the per-endpoint `except Exception` blocks entirely (chosen —
   the global catch-all covers them), or keep them as re-raising safety nets for
   defence in depth?
