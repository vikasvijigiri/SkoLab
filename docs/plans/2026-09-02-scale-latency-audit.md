# Scale & latency audit — 100 concurrent users on free tier

**Date:** 2026-09-02
**Scope:** static (code-read) audit of the Python backend, Go gateway, and web
frontend for latency and concurrency, plus a per-surface latency map and a
phased plan. No app was run; findings are from source.

## The governing constraint

100 simultaneous users + low latency + free tier + self-hosted embeddings
(`decisions/0003`) cannot all hold. An embedding forward pass is CPU-bound and
single-machine; on a free shared-CPU host, concurrent embed calls serialise.
The plan below removes every *artificial* ceiling so the real one (raw CPU for
embeddings + third-party LLM/OpenAlex latency) is all that's left — then names
the decision that lifts that one too.

## Supabase provisioning — done

| Item | Value |
|---|---|
| Project | `rqjjklyuuolbeuwvgzsx` — `ACTIVE_HEALTHY`, Postgres 17, region `ap-southeast-1` |
| API URL | `https://rqjjklyuuolbeuwvgzsx.supabase.co` |
| Tables | none yet — apply schema with `alembic upgrade head` against the pooler URL (do **not** hand-roll SQL; `services/backend/alembic/versions/` is the source of truth) |
| Security advisors | clean (nothing to flag on an empty DB) |

**This app uses Supabase as hosted Postgres only** — no Supabase Auth (Firebase
does auth), Storage, or PostgREST. The anon/publishable keys are not needed;
only `DATABASE_URL`. Neon would be an equivalent swap.

> `ap-southeast-1` (Singapore) is far from Render's US regions. Put the Render
> gateway + HF Space in an Asia/Pacific region too, or accept ~200 ms
> cross-Pacific DB latency on every query. Recreating the DB in `us-west-1` is
> cheaper now than later.

## Backend findings

### P0 — concurrency ceilings (these break at 100 users)

| # | Issue | Location | Effect at load | Fix |
|---|---|---|---|---|
| B1 | Single uvicorn worker — `CMD` has no `--workers` | `services/backend/Dockerfile:48` | One process, one event loop for all Python-side work; CPU-bound `encode()` and JSON parsing of 70B responses starve every other request | `--workers` = vCPU count. Model is loaded per worker (≈130 MB + torch each) — fine on a 16 GB HF Space, impossible on a 512 MB box |
| B2 | Embedding model in-process, CPU-bound, no cross-request cache or coalescing | `app/services/ai/embedding_service.py` | `bge-small` ≈ 20–80 ms/text/core; 100 concurrent callers form a serial queue → multi-second waits; cache **stampede** when a hot key (`daily_feed`) expires and every user misses at once | (a) cache vectors by `sha256(text)` in L2; (b) single-flight concurrent identical embeds; (c) see "Scaling decision" |
| B3 | New `httpx.AsyncClient()` per LLM call | `app/services/ai/llm_service.py:250` | TCP + TLS handshake to Groq on every call (~100–300 ms); ephemeral-port pressure under load | One module-level `AsyncClient` with `limits=Limits(max_connections=100, max_keepalive_connections=20)` |
| B4 | Go→Python proxy uses `http.DefaultTransport` — `MaxIdleConnsPerHost=2`, no `ResponseHeaderTimeout`, no request deadline | `services/backend-go/main.go:133` | Constant connection churn to the one Python host; a hung Python request pins a gateway goroutine and client socket **forever** → goroutine pileup, memory growth, collapse | Custom `http.Transport{MaxIdleConnsPerHost:100, IdleConnTimeout:90s, ResponseHeaderTimeout:60s}` + `context.WithTimeout` per proxied request |

### P1 — database & pool

| # | Issue | Location | Effect | Fix |
|---|---|---|---|---|
| B5 | Direct-connection pools sized Go `MaxConns=50` + Py `pool_size=10 + max_overflow=20`; 80 direct connections | `internal/db/postgres.go:28`, `app/db/database.py:44` | Supabase free **direct** connection limit (~60) is blown → `too many connections`, refused requests | Both services connect to the Supabase **transaction pooler** (`:6543`). pgx: set `default_query_exec_mode=exec` (no prepared-statement cache under pgBouncer txn mode). asyncpg: `statement_cache_size=0`. Right-size: Go 15–20, Py `pool_size=5, max_overflow=10` |
| B6 | `init_db()` runs `Base.metadata.create_all` + `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` on every startup | `app/db/database.py:137`, called from `app/main.py:239` | Slow cold start (stacks on model load); DDL against prod on every deploy; schema drift vs. Alembic | Migrations become a release step (`alembic upgrade head`); delete the runtime `create_all` + `ALTER` block |
| B7 | Slow-query listener runs `EXPLAIN` synchronously inside `after_cursor_execute`, logs full SQL text | `app/db/database.py:85-104` | Every query >100 ms triggers a second query on the same connection **in the request path**; full statements in logs (log spend + PII) | Gate behind `DEBUG_SQL`; sample 1%; move EXPLAIN off the hot path |
| B8 | No Redis → every cache get-miss and every set opens a fresh `AsyncSessionLocal()`; `set()` is SELECT-then-INSERT/UPDATE (read-modify-write race) | `app/db/pg_cache.py:145,188` | Cache traffic becomes DB traffic; concurrent sets on the same key race; cold-cache thundering herd | Set `REDIS_URL` to Upstash (code path already exists, `init_redis()`); convert `set()` to `INSERT ... ON CONFLICT (cache_key) DO UPDATE` |

### P2 — resilience / cost

| # | Issue | Location | Effect | Fix |
|---|---|---|---|---|
| B9 | LLM fallback loop tries up to 16 models serially, each with a full `llm_timeout_seconds` | `app/services/ai/llm_service.py:214` | One bad Groq window → a single user request runs for minutes before failing | Cap at 3–4 models; enforce a total wall-clock budget; per-provider circuit breaker (`app/core/circuit_breaker.py` already exists — wire it in) |
| B10 | Rate limiter keys on `c.RemoteIP()` without configured trusted proxies | `main.go:49`, `internal/middleware/ratelimit.go` | Behind Render's LB, requests may all present one IP → 100 users share the 120 req/s bucket; or, if a forwarded header is trusted wrongly, it is spoofable | `router.SetTrustedProxies([...])`; key on the real client IP; exempt or raise the limit for authed users |
| B11 | Background workers (`researcher_worker`, `monitor_disk_space`, a `ThreadPoolExecutor(max_workers=1)`) run in-process with the API | `app/main.py:245-269,358` | Enrichment CPU competes with request serving on the same (single) worker | Move enrichment to a separate HF Space / process, or a `--workers`-excluded dyno; at minimum guard with a concurrency semaphore |

## Frontend findings

| # | Issue | Location | Effect | Fix |
|---|---|---|---|---|
| F1 | Global `retry: 2` in the QueryClient | `apps/web/src/components/providers.tsx:17` | A degraded backend gets 3× the load (original + 2 retries) per query | `retry: 1` with backoff; `retry: 0` for mutations (Horizon predict, Nexus chat) |
| F2 | Heavy queries lean on the 60 s global `staleTime` — `dailyFeedQuery`, `collaboratorsQuery`, `heatmapQuery`, `journalAdvisorQuery`, `matchGrantsQuery`, `industryOpportunitiesQuery`, `authorQuery` set none of their own | `apps/web/src/lib/api/queries.ts` | Navigating back to a page after 60 s idle re-hits an endpoint whose server cache is valid for 1–6 h | Per-query `staleTime` aligned to the server TTL in `app/core/cache.py` (feed 1 h, analysis 6 h, journals 2 h, …); raise `gcTime` |
| F3 | No prefetch on intent | `queries.ts`, list/card components | Every author/paper open pays a full cold round-trip | `queryClient.prefetchQuery(authorQuery(...))` on card hover / focus |
| F4 | Bundle & code-split not verified statically | `apps/web` | Unknown until measured | Lighthouse + `@next/bundle-analyzer` after deploy; `next/dynamic` for Nexus/Horizon views if they pull heavy deps |

## Per-surface latency map

"Warm" = server cache hit. "Cold" = cache miss on free-tier hardware **after
P0 fixes** (before them, every Cold row is far worse and collapses under load).

| Surface / action | Query | Route | Tier | Heavy work | Server cache | Cold | Warm |
|---|---|---|---|---|---|---|---|
| `/home` daily feed | `dailyFeedQuery` | `GET /api/v1/daily_feed` | Python | OpenAlex + embed N papers + MMR | 1 h | 5–20 s | <200 ms |
| `/home` daily conjecture | `dailyConjectureQuery` | `GET /api/v1/daily_conjecture` | Python | OpenAlex ×2 + 70B LLM | 24 h | 3–10 s | <200 ms |
| Author type-ahead | `authorSuggestionsQuery` | `GET /api/v1/author_suggestions` | **Go** | PG + OpenAlex fallback | 30 m | 200–800 ms | <100 ms |
| Author profile | `authorQuery` | `GET /search_author` | Python | PG→Firestore→OpenAlex live + 10 metrics | 1 h | 1–8 s | <300 ms |
| Collaborators graph | `collaboratorsQuery` | `GET /network_collaborators` | Python | networkx co-author graph | 1 h | 2–10 s | <300 ms |
| Citation heatmap | `heatmapQuery` | `GET /citation_heatmap` | Python | OpenAlex works aggregation | 1 h | 1–5 s | <200 ms |
| Journal advisor | `journalAdvisorQuery` | `GET /journal_advisor` | Python | LLM + embedding calibration | 2 h | 3–12 s | <200 ms |
| Grant match | `matchGrantsQuery` | `GET /match_grants` | Python | LLM + embed | 1 h | 3–12 s | <200 ms |
| Paper analysis | `paperAnalysisQuery` | `GET /api/v1/analyze_paper` | Python | 70B LLM → JSON | 6 h | 4–15 s | <200 ms |
| Lit search | `openAlexWorksQuery` | Next route `/api/openalex/works` | Next server | OpenAlex | RQ 5 m | 400 ms–2 s | <100 ms |
| Horizon predict (btn) | `useMutation` | `POST /api/v1/discovery/predict` | Python | 70B LLM, long prompt | none | 5–20 s each press | — |
| Nexus chat (send) | `useMutation` | `POST /api/v1/discovery/nexus-chat` | Python | 70B LLM multi-turn | none | 3–15 s | — |
| Industry opportunities | `industryOpportunitiesQuery` | `GET /api/v1/industry_opportunities` | Python | scrape + LLM + embed | 1 h | 5–20 s | <200 ms |
| Leaderboard | `leaderboardQuery` | `GET /api/v1/leaderboard/:field` | **Go** | one PG query | Go cache | 100–400 ms | <50 ms |
| Profile sync (login) | `syncUserProfile` | `POST /api/v1/users/profile/sync` | **Go** | Firebase verify + PG upsert | — | 200–600 ms | 200 ms |

Reading: the Go-tier surfaces (type-ahead, leaderboard, profile sync) are
already fine at 100 users. Everything Python-tier is gated by embeddings, 70B
LLM calls, or OpenAlex/Firestore round-trips — the caches hide this well until
a key expires and N users miss together.

## Plan

### Phase 0 — deploy (unblocks everything; needs user auth — see `DEPLOY.md`)

1. Recreate the Supabase project in a US or single Asia region matching the
   compute hosts.
2. `alembic upgrade head` against the pooler URL.
3. Deploy per `DEPLOY.md`; smoke `/livez`, `/gateway-health`.

### Phase 1 — remove artificial ceilings (P0 + top P1)

| Step | Change | Files | Check |
|---|---|---|---|
| 1 | `--workers` in the uvicorn CMD, driven by an env var | `services/backend/Dockerfile` | `ps` shows N workers; memory within Space limit |
| 2 | Shared module-level `httpx.AsyncClient` | `app/services/ai/llm_service.py` | k6 `test:load:baseline` p95 for an LLM route drops |
| 3 | Custom proxy `Transport` + per-request timeout | `services/backend-go/main.go` | `go test ./...`; k6 `spike` shows no goroutine growth |
| 4 | Point both pools at the Supabase transaction pooler; right-size; asyncpg `statement_cache_size=0`, pgx `exec` mode | `app/db/database.py`, `internal/db/postgres.go` | `SELECT count(*) FROM pg_stat_activity` stays < 60 under `ramp_up` |
| 5 | Move `create_all` + `ALTER` out of `init_db`; migrations are a release step | `app/db/database.py`, `app/main.py` | cold-start time measured before/after |
| 6 | Set `REDIS_URL` to Upstash; `set()` → `INSERT ... ON CONFLICT` | env, `app/db/pg_cache.py` | `[Redis] Connected` in logs; `cache_entries` write rate falls |

### Phase 2 — kill the stampede + embedding cost

| Step | Change | Files |
|---|---|---|
| 7 | Cache embedding vectors by content hash (L2) | `app/services/ai/embedding_service.py` |
| 8 | Single-flight concurrent identical embeds and identical cache-miss computations (per-key `asyncio.Lock` / `aiojobs`-style) | `app/db/pg_cache.py`, callers in `app/services/platform/pipeline_services.py` |
| 9 | Serve stale-while-revalidate from cache: return the expired value immediately, refresh in the background | `app/db/pg_cache.py` |
| 10 | Cap the LLM fallback list to 3–4, add a total deadline, wire the existing circuit breaker | `app/services/ai/llm_service.py` |

### Phase 3 — frontend latency polish (needs the app running; Playwright)

| Step | Change | Files |
|---|---|---|
| 11 | `retry: 1`, no retry on mutations/4xx | `apps/web/src/components/providers.tsx` |
| 12 | Per-query `staleTime`/`gcTime` matched to server TTLs | `apps/web/src/lib/api/queries.ts` |
| 13 | Prefetch author/paper queries on card hover/focus | list & card components |
| 14 | Lighthouse + bundle-analyzer pass; `next/dynamic` for heavy views; verify `next/image` everywhere | `apps/web` |
| 15 | Playwright per-interaction timing run against the deployed URL; record real p50/p95 per row of the latency map | `tests/` (new) |

### Phase 4 — the scaling decision (blocks "100 users, low latency")

Choose one; each supersedes or amends `decisions/0003`:

| Option | Cost | Latency at 100 concurrent | Work |
|---|---|---|---|
| **A. Pay for warm compute** | ~$25–50/mo (2 vCPU / 4 GB backend + small paid Postgres) | Cold paths 1–5 s, no queue collapse | least code; just hosting change |
| **B. Embeddings → API + hard cache** (Voyage / OpenAI / Cohere) | ~$0–5/mo | Embedding step 50–150 ms network, parallelisable; frees CPU for request serving | swap `embedding_service.py` internals, keep the interface; new decision record |
| **C. Cap concurrency + queue** | $0 | Honest backpressure: fast users fast, overflow users see a "working…" state for seconds | add a semaphore + queue UI; accept the UX |

Recommended: **B** now (keeps it near-free and is the real fix), **A** if/when
paid infra is on the table anyway.

## What this audit did not cover

- Running the stack or Playwright (Phase 3 depends on a deployment).
- `app/services/*` business-logic correctness — this was a latency/concurrency
  pass only.
- The `infrastructure/` observability stack.
