# Backend API Contracts & Error Handling Implementation Plan

**Goal:** Every FastAPI route carries a `response_model`, no endpoint leaks an internal exception message, list routes share one pagination contract, and a `tests/api/` suite + guard tests lock all three in.

**Source spec:** `docs/specs/2026-09-01-backend-api-contracts-design.md` (approach A)

**Slug:** backend-api-contracts (branch: `feat/backend-api-contracts`)

**Architecture:** Incremental wiring — the spec's approach A. Foundations first (`ErrorResponse` envelope + app-level handlers, `PaginationParams`/`Page[T]`, the `tests/api/` harness + a shrinking allow-list guard). Then one task per endpoint file: wire `response_model` on its routes, add the models it needs to a per-router `app/schemas/<router>.py` (additive — `schemas/core.py` is not moved), remove *route-handler* `except Exception → HTTPException(500, str(e))` blocks (helper-function recovery stays), apply `PaginationParams` to its list routes, drop its routes from the guard allow-list, add `tests/api/test_<router>.py`. Finally: delete the hand-kept `openapi.yaml`, add an OpenAPI build+snapshot test.

**Tech stack and constraints:**

- `services/backend` only. FastAPI, Python 3.10 (CI pin), Pydantic v2, SQLAlchemy async. No Go gateway / Android / web / `.claude` change.
- **No response-shape change.** A wired `response_model` must serialise exactly what the route returns today — verified per route against a real or mocked service response. Extra fields the clients don't read may be dropped; a field the web/Android client reads must survive.
- **Do not change which routes require auth** — that is the Phase-2 auth audit. Wire `response_model` on authed and unauthed routes alike.
- **Do not touch service internals, caching, the recommendation pipeline, or `main.py`'s two custom `@app.middleware("http")` blocks.** Only add exception handlers to `main.py`.
- `main.py` mounts `api_router` twice (`prefix="/api/v1"` and bare) — leave that; handlers registered on `app` cover both.
- New tests run in the existing `ci.yml` backend job (`pytest tests/`, `asyncio_mode=auto`, `ci_bootstrap_db.py` provides the schema). Mock OpenAlex / Groq / OpenRouter — never call them.
- Additive `app/schemas/<router>.py` files (one new file per endpoint module) keep the wiring tasks file-disjoint. This slightly extends the spec's "no per-domain split" — it moves nothing, only adds; noted as a deliberate deviation.

## Route inventory (from `app.routes`, 2026-09-01)

| Router file | Routes | Already typed | To wire |
|---|---|---|---|
| `system.py` | `/`, `/ai_status`, `/status` | 0 | 3 |
| `agent.py` | `/agent/chat`, `/agent/upload_document`, `/chat_with_author` | 0 | 3 |
| `papers.py` | `/summarize_work`, `/analyze_paper`✓, `/presentation_outline`, `/semantic_trending` | 1 | 3 |
| `feed.py` | `/daily_feed`, `/daily_feed/dismiss`, `/daily_conjecture`✓, `/industry_opportunities`, `/assistant_professor_roadmap` | 1 | 4 |
| `authors.py` | `/refresh_author`, `/search_author`~, `/author_metrics`, `/network_collaborators`, `/collaborator_synergy`, `/citation_heatmap`, `/match_grants`, `/journal_advisor` | ~1 (Union w/ dict) | 8 |
| `support.py` | `/support/metrics` | 0 | 1 |
| `industry_academic.py` | `/industry_academic_tieups`✓ | 1 | 0 |
| `integrations.py` | `/integrations/zotero/auth`, `/zotero/callback`, `/zotero/sync` | 0 | 3 |
| `discovery_engine.py` | `/discovery/predict`, `/discovery/nexus-chat` | 0 | 2 |
| `domains/quest/router.py` | `/leaderboard/{field}`✓, `/users/quests`✓, `/users/quests/complete` | 2 | 1 |
| `domains/recommendation/router.py` | `/recommendations/peers`✓, `/peers/invite`, `/peers/check-registered`✓ | 2 | 1 |
| app-level (`main.py`) | `/`, `/health`, `/metrics` | 0 | 3 (or allow-list) |

`user_memory.py` exists on disk but is **not** in `router.py` (migrated to the Go gateway) — Task 1 confirms it is unregistered and, if so, it is out of scope.

## File map

| File | Action | Owns afterward |
|---|---|---|
| `services/backend/app/api/errors.py` | Create | `ErrorResponse` model + `register_exception_handlers(app)` |
| `services/backend/app/api/pagination.py` | Create | `PaginationParams` dependency + `Page[T]` generic model |
| `services/backend/app/main.py` | Modify | calls `register_exception_handlers(app)` after `app = FastAPI(...)`; nothing else changed |
| `services/backend/app/schemas/<router>.py` (system, agent, papers_extra, feed_extra, authors_extra, support, integrations, discovery, quest_extra, recommendation_extra) | Create | the response models each router needs that don't exist in `schemas/core.py` |
| `services/backend/app/api/v1/endpoints/<file>.py` | Modify | `response_model=` on every route; route-handler `except Exception` deleted; `PaginationParams` on list routes |
| `services/backend/app/domains/quest/router.py`, `.../recommendation/router.py` | Modify | same |
| `services/backend/tests/api/__init__.py` | Create | package marker |
| `services/backend/tests/api/conftest.py` | Create | `client` fixture (`httpx.ASGITransport`), external-boundary mock fixtures |
| `services/backend/tests/api/test_contract_guard.py` | Create | asserts every `app.routes` entry has a non-`None` `response_model` OR is in `UNTYPED_ALLOWLIST`; the allow-list shrinks to `[]` as tasks land |
| `services/backend/tests/api/test_<router>.py` (one per router) | Create | happy-path (body parses against `response_model`), 422 path, 401/403 for authed routes |
| `services/backend/tests/test_openapi.py` | Create | `app.openapi()` builds, has `info.version`, every path has ≥1 response; diffs against the snapshot |
| `api-contracts/openapi.snapshot.json` | Create | committed snapshot of `app.openapi()` |
| `api-contracts/openapi.yaml` | Delete | superseded by generated `/openapi.json` + the snapshot |
| `README.md`, `AGENTS.md` | Modify | references to `api-contracts/openapi.yaml` → `/openapi.json` and `/docs` |

## Progress
- [ ] Task 1 — `ErrorResponse` envelope + app-level exception handlers
- [ ] Task 2 — `PaginationParams` + `Page[T]`
- [ ] Task 3 — `tests/api/` harness + shrinking contract-guard test
- [ ] Task 4 — Wire `system.py` + `main.py` app-level routes
- [ ] Task 5 — Wire `agent.py`
- [ ] Task 6 — Wire `papers.py`
- [ ] Task 7 — Wire `feed.py`
- [ ] Task 8 — Wire `authors.py` (the heavy one)
- [ ] Task 9 — Wire `support.py` + `integrations.py`
- [ ] Task 10 — Wire `discovery_engine.py`
- [ ] Task 11 — Wire `domains/quest` + `domains/recommendation`
- [ ] Task 12 — Delete `openapi.yaml`, add OpenAPI build+snapshot test, fix doc refs

## Constitution gate
- [x] I Evidence — every task names its pytest command + expected result
- [x] II Test first — Task 3 lands the guard test failing (31 untyped routes allow-listed); each wiring task removes its routes from the allow-list and adds `test_<router>.py` before/with the wiring
- [x] III Smallest change — only `response_model` + route-handler `except` deletion + pagination on list routes; no service refactor, no `schemas/core.py` move
- [x] IV Reversibility — no migrations, no credentials; `openapi.yaml` deletion is revertible and the snapshot preserves its intent
- [x] V No silent degradation — the guard test enforces the invariant; the allow-list is the only escape and it must reach `[]`
- [x] VI Mechanism — `test_contract_guard.py` + `test_openapi.py` are the enforcement
- [x] VII Secrets — none involved; CI already uses fake keys

## Complexity tracking
- All boxes ticked. Deliberate spec extension (per-router `app/schemas/<router>.py` files) is additive-only and recorded in **Architecture**.

## Tasks

### Task 1: `ErrorResponse` envelope + app-level exception handlers
**Purpose:** no route leaks an internal exception message; validation and unexpected errors return a consistent JSON shape
**Files:**
- Create: `services/backend/app/api/errors.py` — `class ErrorResponse(BaseModel): detail: str; code: str; request_id: str`; `def register_exception_handlers(app: FastAPI) -> None` registering: `RequestValidationError` → 422 `ErrorResponse(code="validation_error", detail=<summary>)` with the field errors under an `errors` key; `StarletteHTTPException`/`HTTPException` → pass status, wrap `detail`; `Exception` catch-all → log the real exception via the existing logger with `request_id`/`trace_id`, return 500 `ErrorResponse(detail="Internal server error", code="internal_error")`
- Modify: `services/backend/app/main.py` — after `app = FastAPI(...)` (line ~368), call `register_exception_handlers(app)`. No other change.
- Test: `services/backend/tests/api/test_errors.py` — a throwaway route raising `ValueError` → 500 body is `ErrorResponse`-shaped and does **not** contain the string `"ValueError"`; a route with a required query param, called without it → 422 `ErrorResponse(code="validation_error")`
**Dependencies:** none
**Implementation notes:** get `request_id` from the existing `request_id_var` contextvar (`main.py`). Do not remove any existing middleware. The catch-all must be registered for `Exception` (broad) — FastAPI/Starlette route it last.
**Verification:**
- Run: `cd services/backend && python -m pytest tests/api/test_errors.py -q`
- Expect: exit 0; both cases pass
**Done when:** an unhandled exception anywhere returns the generic 500 envelope with the real cause only in the server log.

### Task 2: `PaginationParams` + `Page[T]`
**Purpose:** one pagination contract for every list route
**Files:**
- Create: `services/backend/app/api/pagination.py` — `class PaginationParams` (dataclass or `Depends`-able callable: `limit: int = Query(20, ge=1, le=100)`, `offset: int = Query(0, ge=0)`); `class Page(BaseModel, Generic[T]): items: list[T]; total: int; limit: int; offset: int`; a helper `paginate(seq, params) -> Page`
- Test: `services/backend/tests/api/test_pagination.py` — `paginate(list(range(50)), PaginationParams(limit=10, offset=20))` → `items == [20..29]`, `total == 50`; `limit=200` rejected (422 when used in a route) — assert via a throwaway route
**Dependencies:** none
**Implementation notes:** Pydantic v2 generic model syntax. `paginate` slices an already-materialised sequence — services that page in SQL pass `total` explicitly via a `Page(items=..., total=..., ...)` construction instead.
**Verification:**
- Run: `cd services/backend && python -m pytest tests/api/test_pagination.py -q`
- Expect: exit 0
**Done when:** `Page[T]` and `PaginationParams` import cleanly and the slice/bounds tests pass.

### Task 3: `tests/api/` harness + shrinking contract-guard test
**Purpose:** the mechanism that makes "every route typed" a test, not a promise
**Files:**
- Create: `services/backend/tests/api/__init__.py` — empty
- Create: `services/backend/tests/api/conftest.py` — `client` fixture wrapping `app` in `httpx.ASGITransport`; fixtures that monkeypatch `OpenAlexService`, the LLM services, and Firestore access to deterministic fakes
- Create: `services/backend/tests/api/test_contract_guard.py` — iterate `app.routes` (APIRoute only); assert each has `route.response_model is not None` OR `route.path in UNTYPED_ALLOWLIST`; `UNTYPED_ALLOWLIST` is a module-level set seeded with **every currently-untyped path** (from the inventory table). A second test asserts the allow-list has no entry that is *already* typed (catches a stale allow-list).
**Dependencies:** 1, 2
**Implementation notes:** the allow-list starts at ~31 entries and each wiring task deletes its paths from it. The plan is done when `UNTYPED_ALLOWLIST == set()`. Do not allow-list `/openapi.json`, `/docs`, `/redoc`, static, or `HEAD`/`OPTIONS` auto-routes — filter to `APIRoute` with a `GET`/`POST` method.
**Verification:**
- Run: `cd services/backend && python -m pytest tests/api/test_contract_guard.py -q`
- Expect: exit 0 (the allow-list makes it pass now); `len(UNTYPED_ALLOWLIST)` printed in the test's `-s` output for tracking
**Done when:** the guard test passes with the full allow-list and fails if any allow-listed route is quietly removed or any new untyped route is added.

### Task 4: Wire `system.py` + `main.py` app-level routes
**Purpose:** `/`, `/ai_status`, `/status`, `/health`, `/metrics` are typed
**Files:**
- Create: `services/backend/app/schemas/system.py` — `RootResponse`, `AiStatusResponse`, `SystemStatusResponse`, `HealthResponse`, `MetricsResponse` matching each handler's current return dict
- Modify: `services/backend/app/api/v1/endpoints/system.py` — `response_model=` on all 3 routes; drop route-handler `except Exception` (3) that only `raise HTTPException(500, str(e))`
- Modify: `services/backend/app/main.py` — `response_model=` on the `@app.get("/")`, `/health`, `/metrics` handlers (or add their paths to a permanent allow-list with a `# infra route` reason if they must stay dict — decide per route against its body)
- Modify: `services/backend/tests/api/test_contract_guard.py` — remove these paths from `UNTYPED_ALLOWLIST`
- Test: `services/backend/tests/api/test_system.py` — each route: 200 + body parses against its model
**Dependencies:** 3
**Implementation notes:** `/health` and `/metrics` on `app` are infra endpoints; a real model is preferred but a documented allow-list entry is acceptable for `/metrics` if it returns Prometheus text (not JSON) — check the handler.
**Verification:**
- Run: `cd services/backend && python -m pytest tests/api/test_system.py tests/api/test_contract_guard.py -q`
- Expect: exit 0; guard allow-list shrank by 3–6
**Done when:** system + app-level routes are typed or documented-allow-listed, tests green.

### Task 5: Wire `agent.py`
**Purpose:** `/agent/chat`, `/agent/upload_document`, `/chat_with_author` typed
**Files:**
- Create: `services/backend/app/schemas/agent.py` — response models for the 3 routes (reuse `ChatMessage`/`AgentChatRequest` from `core.py` for requests)
- Modify: `services/backend/app/api/v1/endpoints/agent.py` — `response_model=` on all 3; remove the 3 route-handler `except Exception`
- Modify: `tests/api/test_contract_guard.py` — remove these paths
- Test: `services/backend/tests/api/test_agent.py` — happy path (LLM service mocked) + the `message` >2000-char validation → 422 `ErrorResponse`
**Dependencies:** 3
**Implementation notes:** `/agent/upload_document` is multipart — its response is an upload-ack model. Mock `AgentService` in `conftest.py`.
**Verification:**
- Run: `cd services/backend && python -m pytest tests/api/test_agent.py tests/api/test_contract_guard.py -q`
- Expect: exit 0
**Done when:** agent routes typed, tests green, allow-list shrank by 3.

### Task 6: Wire `papers.py`
**Purpose:** `/summarize_work`, `/presentation_outline`, `/semantic_trending` typed (`/analyze_paper` already is)
**Files:**
- Create: `services/backend/app/schemas/papers_extra.py` — `SummarizeWorkResponse`, `PresentationOutlineResponse`, `SemanticTrendingResponse` (a list envelope — use `Page[Work]` or a bespoke list model per the route's shape)
- Modify: `services/backend/app/api/v1/endpoints/papers.py` — `response_model=` on the 3; remove **only** the route-handler `except Exception` blocks (there are 11 in the file — the rest are in `fetch_one_concept`/`score_paper` helpers and stay); apply `PaginationParams` to `/semantic_trending` if it returns a list
- Modify: `tests/api/test_contract_guard.py` — remove these paths
- Test: `services/backend/tests/api/test_papers.py` — each route happy path (summarization service mocked) + `/semantic_trending` pagination bounds
**Dependencies:** 2, 3
**Implementation notes:** grep `papers.py` for `except Exception` and classify each by whether it is directly inside an `@router` handler body vs a helper. Only the former go.
**Verification:**
- Run: `cd services/backend && python -m pytest tests/api/test_papers.py tests/api/test_contract_guard.py -q`
- Expect: exit 0
**Done when:** papers routes typed, helper recovery intact, tests green.

### Task 7: Wire `feed.py`
**Purpose:** `/daily_feed`, `/daily_feed/dismiss`, `/industry_opportunities`, `/assistant_professor_roadmap` typed (`/daily_conjecture` already is)
**Files:**
- Create: `services/backend/app/schemas/feed_extra.py` — `DailyFeedItem`, `DismissResponse`, `IndustryOpportunity`, `RoadmapResponse` (mirror the web app's `lib/types.ts` shapes so no client field is dropped)
- Modify: `services/backend/app/api/v1/endpoints/feed.py` — `response_model=` on the 4; `/daily_feed` + `/industry_opportunities` return lists → `Page[T]` or `list[T]` per their current shape; remove route-handler `except Exception` (of the 10, keep the ones in `generate_fallback_conjecture` / `_abstract_text` helpers)
- Modify: `tests/api/test_contract_guard.py` — remove these paths
- Test: `services/backend/tests/api/test_feed.py` — each route happy path (pipeline service mocked); `/daily_feed` with a bad `limit` → 422
**Dependencies:** 2, 3
**Implementation notes:** cross-check every field against `apps/web/src/lib/types.ts` `DailyFeedItem` / `IndustryOpportunity` — those are the live consumers.
**Verification:**
- Run: `cd services/backend && python -m pytest tests/api/test_feed.py tests/api/test_contract_guard.py -q`
- Expect: exit 0
**Done when:** feed routes typed against the client shapes, tests green.

### Task 8: Wire `authors.py` (the heavy one)
**Purpose:** the 7 untyped author routes typed; `/search_author`'s `Union[AuthorResponse, dict]` narrowed
**Files:**
- Create: `services/backend/app/schemas/authors_extra.py` — `RefreshAuthorResponse`, `AuthorMetricsResponse`, `NetworkCollaborator`, `CollaboratorSynergyResponse`, `CitationHeatmap`, `GrantMatch`, `JournalRecommendation` (mirror `apps/web/src/lib/types.ts`)
- Modify: `services/backend/app/api/v1/endpoints/authors.py` — `response_model=` on all 7 untyped routes; `/network_collaborators` + `/journal_advisor` + `/match_grants` return lists → `Page[T]` or `list[T]`; replace `/search_author`'s `Union[AuthorResponse, dict]` with `AuthorResponse` (if a dict fallback path exists, model it as `AuthorResponse` with optional fields, or return a documented 404/422 instead — decide against the code); remove **route-handler** `except Exception` (15 in the file — most are in `_pg_get_*` / `fetch_similar_authors` / `_blocking_*` helpers and stay)
- Modify: `tests/api/test_contract_guard.py` — remove these paths
- Test: `services/backend/tests/api/test_authors.py` — each route happy path (OpenAlex + PG mocked); `/network_collaborators` pagination; `/search_author` with an unresolvable name → its real behavior asserted (200 empty-ish model vs 404)
**Dependencies:** 2, 3
**Implementation notes:** this is the largest task — `authors.py` is ~760 lines. Classify all 15 `except Exception` first (a comment list in the PR). The `Union[..., dict]` change is the one behavior-adjacent edit — verify the dict branch's real trigger before changing it; if it's a genuine distinct shape, keep a narrow union of two models, not `dict`.
**Verification:**
- Run: `cd services/backend && python -m pytest tests/api/test_authors.py tests/api/test_contract_guard.py -q`
- Expect: exit 0
**Done when:** all author routes typed, `/search_author` no longer returns bare `dict`, helper recovery intact, tests green.

### Task 9: Wire `support.py` + `integrations.py`
**Purpose:** `/support/metrics`, `/integrations/zotero/{auth,callback,sync}` typed
**Files:**
- Create: `services/backend/app/schemas/support.py` — `SupportMetricsResponse`
- Create: `services/backend/app/schemas/integrations.py` — `ZoteroAuthResponse`, `ZoteroCallbackResponse`, `ZoteroSyncResponse` (reuse `ZoteroSyncRequest` for the request)
- Modify: `services/backend/app/api/v1/endpoints/support.py`, `.../integrations.py` — `response_model=` on all 4; remove route-handler `except Exception`
- Modify: `tests/api/test_contract_guard.py` — remove these paths
- Test: `services/backend/tests/api/test_support.py`, `.../test_integrations.py` — happy path each; `/zotero/callback` with missing `oauth_token` → 422
**Dependencies:** 3
**Implementation notes:** the Zotero routes are OAuth stubs (`oauth_token=mock_token_skolab_...` per the secret-scan finding in PR #3) — model what they return today, don't rework the flow.
**Verification:**
- Run: `cd services/backend && python -m pytest tests/api/test_support.py tests/api/test_integrations.py tests/api/test_contract_guard.py -q`
- Expect: exit 0
**Done when:** both routers typed, tests green.

### Task 10: Wire `discovery_engine.py`
**Purpose:** `/discovery/predict`, `/discovery/nexus-chat` typed
**Files:**
- Create: `services/backend/app/schemas/discovery.py` — `BreakthroughPrediction` (+ `PaperSource`) and `NexusChatResponse` (mirror `apps/web/src/lib/types.ts` `BreakthroughPrediction` / `{content}`)
- Modify: `services/backend/app/api/v1/endpoints/discovery_engine.py` — `response_model=` on both; remove the 2 route-handler `except Exception`
- Modify: `tests/api/test_contract_guard.py` — remove these paths
- Test: `services/backend/tests/api/test_discovery_engine.py` — both routes happy path (prediction/LLM service mocked); `nexus-chat` with empty body → 422
**Dependencies:** 3
**Implementation notes:** these are the exact endpoints the web app's Phase 2 `getHorizonPrediction` / `nexusChat` call — match `apps/web/src/lib/types.ts` `BreakthroughPrediction` field-for-field.
**Verification:**
- Run: `cd services/backend && python -m pytest tests/api/test_discovery_engine.py tests/api/test_contract_guard.py -q`
- Expect: exit 0
**Done when:** both routes typed against the web client shapes, tests green.

### Task 11: Wire `domains/quest` + `domains/recommendation`
**Purpose:** `/users/quests/complete` and `/recommendations/peers/invite` typed (the rest already are)
**Files:**
- Create: `services/backend/app/schemas/quest_extra.py` — `CompleteQuestResponse`
- Create: `services/backend/app/schemas/recommendation_extra.py` — `LogPeerInviteResponse`
- Modify: `services/backend/app/domains/quest/router.py`, `.../recommendation/router.py` — `response_model=` on the untyped routes; remove the 2 route-handler `except Exception` in `quest/router.py`
- Modify: `tests/api/test_contract_guard.py` — remove these paths; **assert `UNTYPED_ALLOWLIST == set()`** now (flip the guard from "allow-listed" to "must be empty")
- Test: `services/backend/tests/api/test_quest.py`, `.../test_recommendation.py` — happy path each; an authed route (`/users/quests`) with no token → 401/403 `ErrorResponse`
**Dependencies:** 3, 4, 5, 6, 7, 8, 9, 10
**Implementation notes:** this task closes the allow-list. If any route is still unavoidably untyped, it stays as a **named** permanent allow-list entry with a `# reason:` — but the default is empty.
**Verification:**
- Run: `cd services/backend && python -m pytest tests/api/ -q`
- Expect: exit 0; `test_contract_guard.py` now asserts the allow-list is empty
**Done when:** every `APIRoute` has a `response_model` (or a documented permanent exception), full `tests/api/` green.

### Task 12: Delete `openapi.yaml`, add OpenAPI build+snapshot test, fix doc refs
**Purpose:** one source of truth for the API schema — the generated one
**Files:**
- Delete: `api-contracts/openapi.yaml`
- Create: `api-contracts/openapi.snapshot.json` — `json.dumps(app.openapi(), indent=2, sort_keys=True)` output, committed
- Create: `services/backend/tests/test_openapi.py` — `app.openapi()` builds; has `info.version`; every path item has ≥1 non-`default` response; the serialised schema **equals `api-contracts/openapi.snapshot.json`** (a contract change is now a visible reviewable diff; the test message tells the dev to re-generate the snapshot)
- Modify: `README.md` — the "Project Documentation" table / any `api-contracts/openapi.yaml` mention → `/openapi.json` and `/docs`
- Modify: `AGENTS.md` (root) — same, if it references the file
- Modify: `.claude/hooks/` docs-sync rule referencing `openapi.yaml`, if present — point at the snapshot instead (grep first; skip if absent)
**Dependencies:** 11
**Implementation notes:** this task touches `api-contracts/` (a contract surface) and a doc — its own round per `parallel_groups`. The snapshot test is what keeps the generated schema from drifting silently.
**Verification:**
- Run: `cd services/backend && python -m pytest tests/test_openapi.py -q` and `grep -rn "openapi.yaml" README.md AGENTS.md`
- Expect: pytest exit 0; grep returns nothing
**Done when:** `openapi.yaml` is gone, the snapshot test guards the generated schema, no doc points at the deleted file.

## Verification (end to end)

1. `cd services/backend && python -m pytest tests/api/ tests/test_openapi.py -q` → exit 0.
2. `python -m pytest tests/ -q` → the full backend suite still green (no regression from the `except` deletions or `response_model` wiring).
3. `python -m ruff check app && python -m ruff format --check app` → exit 0.
4. `python -c "from app.main import app; import json; json.dumps(app.openapi())"` → no error; every path documented.
5. `grep -rn "except Exception" app/api/v1/endpoints app/domains` → only helper-function sites remain (a short, reviewed list in the PR).
6. `grep -rn "openapi.yaml" .` → nothing outside git history.
7. CI: `gh run list --branch feat/backend-api-contracts` → `SkoLab CI Pipeline` green (backend job runs `tests/api/` automatically).

## Known risks / follow-ups

- **`response_model` silently dropping a client field.** Mitigated by cross-checking every model against `apps/web/src/lib/types.ts` and by the happy-path tests parsing real/mocked bodies. If a field is missing, the web/Android app breaks — flagged as the top review focus for Tasks 6–10.
- **`authors.py` `except Exception` classification.** 15 sites; deleting a helper's recovery block would turn a handled degradation into a 500. Task 8 requires the classification list in the PR before the deletions.
- **`/search_author` `Union[..., dict]`.** The one behavior-adjacent change; if the dict branch is load-bearing, keep a two-model union, not `dict`.
- **Phase 2/3** (auth audit, Sentry, `/livez`+`/readyz`, deploy) — separate specs.
- **Approach B** (`Result`/`ServiceError` service refactor, per-domain `schemas/` split) — a later plan once this contract surface is locked.

## Resolved at Gate 1

- **openapi.yaml** -> delete it; commit `api-contracts/openapi.snapshot.json` from `app.openapi()` and diff it in a test (plan as written).
- **Pagination envelope** -> `Page[T] = {items, total, limit, offset}` (plan as written).
- **Route-handler `except Exception` blocks** -> delete them; rely on the app-level catch-all; keep `try/except` only where a handler genuinely recovers, with a comment (plan as written).

## Approved

Gate 1 passed 2026-09-01. Three markers resolved with the recommended options (all match the plan's stated choices). Proceed to implementation on `feat/backend-api-contracts`.
