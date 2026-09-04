# Python = LLM-only — Migration Roadmap + Phase 0

**Slug:** python-llm-only-phase0
**Status:** Phase 0 implemented 2026-09-03 (Go verification is CI-only — no Go toolchain on the dev box).
**Approved:** Gate 1 passed 2026-09-03.

## Context

The owner set a hard rule (2026-09-03, "strictly"): `services/backend`
(Python/FastAPI) is for **LLM and model-inference services only**. Every other
concern — HTTP/data plane, auth, cache, rate limiting, metrics, object storage,
background jobs — moves to the Go gateway (`services/backend-go`) or a managed
service. This sharpens `decisions/0002`, which split "fast/simple" from
"AI/heavy" but left a large non-LLM surface in Python.

Grounding: `docs/research/2026-09-03-python-llm-only-boundary.md` (Azure *Gateway
Offloading* — business logic belongs in a service *behind* the gateway, never in
the proxy; *Bulkhead*; "Supabase = hosted Postgres only" already fixed).

**Embeddings stay Python** (owner, 2026-09-03): `app/services/ai/embedding_service.py`
and `app/domains/recommendation/engine.py` (`cosine_similarity`, `mmr_diversify`)
are model-inference infrastructure.

## Migration roadmap

| Phase | Scope | Target | Key risk |
|---|---|---|---|
| **0** ✅ | `/recommendations/peers*` → Go; enumeration hole bounded (rate limit + 200-id cap) | Go gateway + pgx | client auth cutover |
| **0b** ◑ | Blind-index column `users.email_bidx` — **landed**. Android Firebase-token attach — **landed** (#27, `network/AuthInterceptor.kt`). Peers group flipped to hard `auth.VerifyUser()` — **PR #30, merge-held** until an Android release carrying #27 ships. | Alembic + Android + Go | old app builds 401 until release |
| ~~**1**~~ **assessed, no move** | `authors.py` has **no clean non-LLM surface** — every route is LLM (`/author_metrics` calls Groq via `ScrapingService`; `/synergy`, `/match_grants`, `/journal_advisor` LLM+embeds), Firestore-coupled (`/search_author`, `/refresh_author`), or a large graph port (`/network_collaborators`). See `decisions/0009`. | — | premise was wrong |
| **1c** (opt) | `/citation_heatmap` — the only LLM/embed-free author route. Needs a Go `cache_entries` parity client + a Firestore-tier decision + `counts_by_year` + drop the `random` fudge. | Go + pgx | Firestore/cache parity |
| **1d** (opt) | `/network_collaborators` — depth-1/2 OpenAlex fan-out + Jaccard + 3-table write-back. No LLM, but a substantial port (no `gonum` needed — it's not a networkx path). | Go + pgx | port size |
| **2** ◑ | `/daily_feed/dismiss`, `/support/metrics`, `/zotero/*` → Go — **PR #32** (Python verified; Go unverified, CI dark). `industry_academic_tieups` / `daily_feed` / `daily_conjecture` / roadmap / `industry_opportunities` **stay** (LLM/embeds). | Go | Go has no CI right now |
| **3** | `PgBackedCache` L2 → Upstash Redis; in-app `RateLimiter` → gateway/Cloudflare; `MetricsStore` → `prometheus/client_golang` + OTel; delete the substring WAF (Cloudflare) | Upstash, Cloudflare, Prometheus/OTel | cache-key parity; metric continuity |
| **4** | Static `/downloads` → Cloudflare R2 + signed URLs; long jobs (teleport, exports, enrichment) → queue + worker (`202 + jobId`) per `docs/scaling-decision.md` | R2, Redis queue | stateful-worker removal |
| **5** | Python trimmed to `llm_service`, `agent_service`, `summarization`, `prediction`, `embedding_service`, prompts, and the LLM-generation endpoints. Remove the double router mount, Windows shims, `builtins.print` override | — | regression surface |

`[NEEDS CLARIFICATION: does the Android app have ANY working Firebase-authenticated
request path today? No 'Authorization'/'getIdToken'/'Bearer' appears in
apps/android-app. If profile sync to the auth-gated Go /api/v1/users route works,
there is a token mechanism (OkHttp interceptor?) not yet found; if not, Phase 0b
is "build the pattern", not "add a header".]`

## Phase 0 — what landed

| File | Change |
|---|---|
| `services/backend-go/internal/recommendation/recommendation.go` | New: `GetPeerRecommendations`, `LogPeerInvite`, `CheckRegisteredPeers` — SQL + scoring ported from the deleted Python `service.py`, native `pgx` via `db.Pool`. `check-registered` rejects a combined `emails`+`phones` count over 200 with `400`. Email matching omitted (encrypted column — never resolved in Python either). |
| `services/backend-go/internal/recommendation/recommendation_test.go` | New: 8 table tests over the `db.Pool == nil` paths + request-shape guards, incl. `TestCheckRegistered_RejectsOversizeBatch`. |
| `services/backend-go/internal/auth/firebase.go` | New `VerifyUserOptional()` — valid token sets `user_id`, missing/invalid passes through, never aborts. |
| `services/backend-go/main.go` | `/api/v1/recommendations` group under `VerifyUserOptional()` + a dedicated 5 rps/IP limiter, mounted above `NoRoute`. |
| `services/backend/app/api/v1/router.py` | Dropped the `recommendation_router` import + include; extended the "migrated to Go" comment. |
| `services/backend/app/domains/recommendation/{router,schemas,service}.py`, `app/schemas/recommendation_extra.py`, `tests/api/test_recommendation.py` | Deleted. `engine.py`, `__init__.py`, `tests/test_recommendation_system.py` kept. |
| `apps/web/src/lib/api/endpoints.ts`, `apps/web/src/components/workspace/MembersTab.tsx` | `logPeerInvite` takes an `idToken` (optional today) and threads it via `apiRequest`; `MembersTab` resolves it with `getIdToken()`. |
| `decisions/0008-recommendation-peers-to-go-gateway.md`, `docs/backend-auth-posture.md` | Recorded. |

## Verification

| Check | Result |
|---|---|
| `python -c "import app.main"` | OK — no dangling import |
| `pytest tests/test_recommendation_system.py tests/api/test_auth_posture.py tests/api/test_contract_guard.py -q` | `14 passed` |
| `pytest --collect-only -q` | `150 tests` (was 152; −2 from the removed `test_recommendation.py`), no collection errors |
| `ruff check app` | `All checks passed!` |
| `apps/web` `tsc --noEmit` | no new errors (pre-existing `RouteContext` error in `app/api/openalex/works/[id]/route.ts` is on clean `main`) |
| `go vet ./... && go test ./...` | **CI only** — no Go toolchain on this machine; CI (`.github/workflows/ci.yml`, Go 1.24) is the verification of record |

## Phase 0b — blind index (landed 2026-09-03)

| File | Change |
|---|---|
| `services/backend/app/db/blind_index.py` | New: `email_blind_index(email)` = hex `HMAC-SHA256(EMAIL_BLIND_INDEX_KEY, email.strip().lower())`, `None` when key/email empty. |
| `services/backend/app/core/config.py` | New `email_blind_index_key` setting from `EMAIL_BLIND_INDEX_KEY`. |
| `services/backend/app/models/user_models.py` | `User.email_bidx` column (`String(64)`, indexed); `validate_user_email` keeps it in sync on every ORM write. |
| `services/backend/alembic/versions/a1b2c3d4e5f6_add_users_email_blind_index.py` | New migration: add column + `ix_users_email_bidx`. |
| `services/backend/scripts/backfill_email_bidx.py` | New: idempotent backfill for pre-existing rows (`email_bidx IS NULL`). Exits non-zero if the key is unset. |
| `services/backend/tests/test_email_blind_index.py` | New: 7 tests — known vectors, normalisation, `None` paths, ORM-write side effect. |
| `services/backend-go/internal/recommendation/recommendation.go` | `emailBlindIndex()` (same HMAC); `CheckRegisteredPeers` matches `email_bidx = ANY($1)` and returns the matched input emails as `registered_emails`. |
| `services/backend-go/internal/recommendation/recommendation_test.go` | +2 unit tests pinning the HMAC vectors to the Python side. |
| `.env.example` | `EMAIL_BLIND_INDEX_KEY=` documented. |

**Owner release steps** (not run by an agent): set `EMAIL_BLIND_INDEX_KEY` in
both services, `alembic upgrade head`, `python scripts/backfill_email_bidx.py`.

Verification: `pytest tests/test_email_blind_index.py` **7 passed**; targeted
sweep (`data_quality`, `security`, `api/`, `recommendation_system`,
`encrypted_type`) **69 passed**; `--collect-only` 157; `ruff` clean; migration
module imports with the right `revision`/`down_revision`. Go = CI only.

## Known follow-ups

- **Android token attach + hard-auth flip** — Phase 0b's remaining step. No
  `Authorization`/`getIdToken` anywhere in `apps/android-app` today, so this is
  "establish the pattern", gated on a shipped+adopted client build before the
  `VerifyUserOptional` → `VerifyUser` flip.
- **`registered_emails`** stays empty until the owner runs the migration + backfill.
- **`user_circles` invite** only resolves a `peer_uid`; the email path was dead in Python and needs the blind index wired into `LogPeerInvite` too (small follow-up).
- **`peers` autocomplete** email matching stays name/username/phone — a blind index is equality-only, no substring search on an encrypted column.
