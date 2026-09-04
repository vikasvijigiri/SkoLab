# Phase 2 — feed persistence + non-LLM CRUD → Go gateway

**Slug:** phase2-feed-to-go
**Branch:** `feat/phase2-feed-to-go` (base `origin/main` = `53da9fa`)
**Status:** implemented 2026-09-04. Go code is **UNVERIFIED** — no Go toolchain on
this box and GitHub Actions is out of minutes. Review the Go by hand.
**Roadmap row:** Phase 2 of `docs/plans/2026-09-03-python-llm-only-phase0.md`.

## Goal

"Python = LLM-only" (`decisions/0002`, roadmap phase 2). Move the routes in
`feed.py` / `industry_academic.py` / `integrations.py` / `support.py` that are
**pure Postgres/OpenAlex CRUD with no LLM, no `embed_*`, no heavy numpy** to the
Go gateway. Feed *generation* (embeddings + MMR + 70B LLM) stays in Python and is
still reached through the gateway's `NoRoute` proxy.

## Scope decision — conservative

**If in doubt, it stayed in Python.** Only routes that are obviously pure
data/stub moved. The one non-trivial move (`/daily_feed/dismiss`) is a small
owner-checked write whose cache format is already cross-checked by the Python
reader (see "dismiss — why it is safe" below).

## Route classification

| Route | Verdict | Why |
| :--- | :--- | :--- |
| `POST /daily_feed/dismiss` | **MOVE** | Owner-checked write of a dismissed-work-id list to `cache_entries`. No LLM, no embed. Owner check = verified Firebase uid + `users.openalex_id == body.author_id`. |
| `GET /support/metrics` | **MOVE** | Returns a hard-coded constant dict. Zero DB, zero LLM. The safest possible move. |
| `GET /integrations/zotero/auth` | **MOVE** | OAuth *stub* — interpolates `user_id` into a mock URL string. No DB, no per-user state. |
| `GET /integrations/zotero/callback` | **MOVE** | Stub — returns a fixed success dict. No DB, no state. |
| `POST /integrations/zotero/sync` | **MOVE** | Stub — echoes the posted paper titles back. No DB, no state. |
| `GET /daily_feed` | **STAY** | Embedding query + candidate embedding + mean-centering + MMR diversification + optional per-paper LLM enrichment. Model-inference heavy. |
| `GET /daily_conjecture` | **STAY** | `llama-3.3-70b-versatile` generation with a local fallback; resolves author via OpenAlex first. |
| `GET /assistant_professor_roadmap` | **STAY** | LLM generation (roadmap JSON) + OpenAlex peer derivation + `ResearcherMetrics` read. LLM is the core. |
| `GET /industry_opportunities` | **STAY** | `fetch_industry_opportunities` = web scrape + LLM + `embed_*`. |
| `GET /industry_academic_tieups` | **STAY** | LLM brainstorm over the caller's **private** semantic-memory profile; already `require_owner("user_id")`. Private state + LLM. |

### Ambiguous / deferred

- `[NEEDS DECISION: /daily_feed/dismiss — Redis L2]` If `REDIS_URL` is set on the
  gateway *and* the Python service (both `sync: false` / unset in `render.yaml`
  today), Python's `PgBackedCache` prefers Redis. The Go port writes the
  dismissed-ids envelope to Postgres `cache_entries` only. This is still correct
  in practice because Python's `get_daily_feed` re-reads the dismissed-ids list
  and skips any cached feed that contains a dismissed id (the list, not the feed
  cache, is the source of truth) — and the dismissed-ids key is only ever written
  by this one path, so Redis never holds it and Python falls through to the
  Postgres row. If a future change makes Python warm that key into Redis, the Go
  writer must publish to Redis too. Flagged, not blocking.
- `[NEEDS DECISION: /daily_feed/dismiss — malformed-body status]` FastAPI returned
  `422 {"code":"validation_error"}` for a missing `work_id`. Gin's
  `ShouldBindJSON` + `binding:"required"` returns `400`. No known client depends
  on the 422; the web client always sends both fields. Accepted divergence.
- `/zotero/callback` required-query-param miss: FastAPI returned `422`. The Go
  port returns `422` with a plain `{"error": ...}` body (not the FastAPI
  validation envelope). Shape of the error body differs; status matches.

## What changed

### Go gateway — new `internal/feed` package (UNVERIFIED)

| File | Change |
| :--- | :--- |
| `services/backend-go/internal/feed/feed.go` | New. `DismissDailyFeedItem`, `GetSupportMetrics`, `ZoteroAuthInit`, `ZoteroAuthCallback`, `ZoteroSyncPapers`. `pgx` via `db.Pool`; `db.Pool == nil` ⇒ `503` on the DB-backed handler, static handlers always work. |
| `services/backend-go/internal/feed/feed_test.go` | New. Table tests over `db.Pool == nil` + shape guards, incl. dismiss `401` (no token, via the real `auth.VerifyUser()` group) and dismiss `403` (wrong owner needs DB — covered by the `db.Pool==nil` ⇒ 503 guard + a documented note; the owner-mismatch branch is exercised by the plan's manual review). |
| `services/backend-go/main.go` | One appended route block (clearly delimited, no reordering): `GET /api/v1/support/metrics`, `GET /api/v1/integrations/zotero/auth`, `GET /api/v1/integrations/zotero/callback`, `POST /api/v1/integrations/zotero/sync` unauthenticated; `POST /api/v1/daily_feed/dismiss` under an `auth.VerifyUser()` group. |

**Dismiss owner check — ported faithfully:**

1. `auth.VerifyUser()` middleware on the route ⇒ `401` with no/invalid token.
2. `SELECT openalex_id FROM users WHERE id = $1` on the verified uid.
3. No row, `NULL` openalex_id, or value `!= body.author_id` ⇒ `403`
   `"You can only dismiss items from your own feed."` (matches Python).
4. On success, port of `PipelineServices.dismiss_recommendation`:
   - `doc_id = author_id` after the last `/`.
   - read `cache_entries` row `pipeline_dismissed_recs::<doc_id>` where
     `expires_at > now()`, JSON envelope `{"v": [<work ids>]}`;
   - append `work_id` if absent;
   - upsert the row (`ON CONFLICT (cache_key) DO UPDATE`), `expires_at = now +
     315360000s`, `last_synced = now`, `data = {"v": [...]}::json`;
   - best-effort `DELETE FROM cache_entries WHERE cache_key =
     'pipeline::daily_feed_<doc_id>'` (feed-cache invalidation).
   - respond `{"success": true}`.

**dismiss — why it is safe to move despite the cross-language cache:**
`cache_entries` is a plain key/JSON/TTL table. The envelope (`{"v": value}`), the
`name::key` prefix convention, and the 10-year TTL are all reproduced verbatim.
The Python feed reader treats the dismissed-ids list as authoritative and
cross-checks it against any cached feed, so even a stale feed cache cannot
resurface a dismissed paper. Worst case is a ≤30 s window where Python's L1
in-memory copy of the dismissed-ids list is behind a just-issued dismiss — the
same race that already exists between two Python workers.

### Python — routes removed

| File | Change |
| :--- | :--- |
| `services/backend/app/api/v1/endpoints/feed.py` | Removed `dismiss_daily_feed_item` + `DismissFeedItemRequest`; dropped now-unused imports (`BaseModel`, `status`, `get_verified_user`, `DismissResponse`, one `AsyncSession` re-import kept for the roadmap/industry handlers). `GET /daily_feed` and the three LLM routes untouched. |
| `services/backend/app/services/platform/pipeline/feed.py` | Removed the dead `dismiss_recommendation` method + the now-unused `PgBackedCache` import. `get_dismissed_recommendation_ids` **kept** — `get_daily_feed` still calls it. |
| `services/backend/app/api/v1/endpoints/support.py` | Deleted (router emptied). |
| `services/backend/app/api/v1/endpoints/integrations.py` | Deleted (router emptied). |
| `services/backend/app/schemas/support.py`, `app/schemas/integrations.py` | Deleted (only the removed endpoints imported them). |
| `services/backend/app/api/v1/router.py` | Dropped `support` + `integrations` from the import tuple and their `include_router` lines; extended the "Migrated to the Go gateway" comment. Minimal, localized (Phase 1 also edits this file). |
| `services/backend/tests/api/test_feed.py` | Removed the five dismiss tests + their fakes; kept `test_daily_feed_is_a_typed_array`. |
| `services/backend/tests/api/test_support.py`, `tests/test_support.py` | Deleted. |
| `services/backend/tests/api/test_integrations.py`, `tests/test_integrations.py` | Deleted. |
| `services/backend/tests/api/test_auth_posture.py` | Removed `/daily_feed/dismiss` from `EXPECTED_AUTHED`. |
| `api-contracts/openapi.snapshot.json` | Regenerated via `scripts/gen_openapi_snapshot.py`, LF-normalized. |

### Docs

- `docs/backend-auth-posture.md` — `/daily_feed/dismiss` moved from the authed
  table to "Moved off the Python backend"; `/support/metrics` and `/zotero/*`
  dropped from the public table.
- `docs/plans/2026-09-03-python-llm-only-phase0.md` — Phase 2 row marked done,
  pointing here.
- `task.md` — appended.

### Web client

No change. `apps/web/src/lib/api/endpoints.ts::dismissDailyFeedItem` already
POSTs `/api/v1/daily_feed/dismiss` to the gateway base URL with the Firebase
`idToken` in `Authorization`. The gateway path is identical; it now serves the
route directly instead of proxying. Verified by reading `client.ts`
(`API_BASE_URL` → `:8080`) and `endpoints.ts` (idToken threaded).

## Verification

| Check | Result |
| :--- | :--- |
| `pytest -q` baseline on `feat/phase2-feed-to-go` before changes | `180 passed` |
| `pytest -q` after changes | see PR body |
| `ruff check app` | see PR body |
| `python scripts/gen_openapi_snapshot.py` + LF-normalize | regenerated |
| `go vet ./... && go test ./...` | **NOT RUN — no Go toolchain, CI out of minutes. UNVERIFIED.** |
