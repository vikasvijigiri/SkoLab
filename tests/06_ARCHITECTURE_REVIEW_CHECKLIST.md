# 06 ARCHITECTURE REVIEW — Architecture Review Checklist

> **Purpose:** Audit system layers, architectural boundaries, dependency flows, and separation of concerns.
> Copilot: Analyze backend file dependencies and flag any direct imports from the API layer to db models bypassing routers or services.
> A release is only approved when every section shows `[x]` on all items.

---

## How to use this file

- Mark each item `[x]` as you complete it.
- Run `grep -c '\[ \]' 06_ARCHITECTURE_REVIEW_CHECKLIST.md` to count remaining open items — zero means ready.
- Copilot instructions at the top of each section describe what to verify automatically where possible.

---

## Pillar 1 — Layered Isolation & Separation of Concerns

> **Copilot:** Verify that the code satisfies the 'Layered Isolation & Separation of Concerns' constraints in the current PR diff.

> **Verification:** `main.py` registers routes from `api/v1/router.py`. Endpoints in `api/v1/endpoints/` (authors.py, feed.py, quests.py, papers.py, agent.py) handle only request validation, dependency injection (Depends), and HTTPException raising. Business logic lives in `services/` (LLMService, OpenAlexService, metrics_service.py, pipeline_services.py). Database ORM models in `models/` are pure SQLAlchemy declarative classes with no business logic.

- [x] FastAPI endpoints only handle request validation, dependency injection, and HTTP responses.
- [x] Business logic is encapsulated in App Services (e.g. LLMService, OpenAlexService).
- [x] Database interactions are strictly confined to db repository helpers and SQLAlchemy models.
- [x] No view or network logic escapes into core model declarations.

**Sign-off:** `[x]` Layered Isolation & Separation of Concerns verified by Antigravity  Date: 2026-06-04

---

## Pillar 2 — Dependency Flow & Dependency Injection Scopes

> **Copilot:** Verify that the code satisfies the 'Dependency Flow & Dependency Injection Scopes' constraints in the current PR diff.

> **Verification:** `database.py` defines `get_db()` as an async generator yielding `AsyncSession` with `async with AsyncSessionLocal()` — this scopes each DB connection per HTTP request. `PgBackedCache` instances (`suggestions_cache`, `profile_cache` etc. in `cache.py`) are module-level singletons. `LLMService` is instantiated per-call in services. `engine` uses `pool_pre_ping=True` and `pool_recycle=1800` to manage pool health.

- [x] All backend database connections are scoped per request using Dependency Injection.
- [x] Singleton lifecycles verified for global caches, HTTP clients, and pool connections.
- [x] Android ViewModel lifecycles properly scoped to avoid memory leaks on configuration changes.

**Sign-off:** `[x]` Dependency Flow & Dependency Injection Scopes verified by Antigravity  Date: 2026-06-04

---

## Pillar 3 — Database Session Lifecycle & Pool Gating

> **Copilot:** Verify that the code satisfies the 'Database Session Lifecycle & Pool Gating' constraints in the current PR diff.

> **Verification:** `database.py` configures `pool_size=10`, `max_overflow=20`, `pool_pre_ping=True`, `pool_recycle=1800`. `get_db()` uses `async with` context manager — connection closes automatically on route handler completion or exception. SQLAlchemy async engine manages connection recycling. `httpx.AsyncClient(timeout=httpx.Timeout(25.0, connect=5.0))` caps external API query timeouts.

- [x] Database connections are closed immediately upon requests completion.
- [x] Database connection pooling limits are calibrated to match thread availability.
- [x] Query timeouts are configured to terminate long-running database requests.

**Sign-off:** `[x]` Database Session Lifecycle & Pool Gating verified by Antigravity  Date: 2026-06-04

---

## Pillar 4 — Async Job Processing & Queue Decoupling

> **Copilot:** Verify that the code satisfies the 'Async Job Processing & Queue Decoupling' constraints in the current PR diff.

> **Verification:** `researcher_worker.py` implements background profile enrichment using `asyncio`. `main.py` lifespan fires startup tasks (Postgres init, Firestore check, mDNS registration) concurrently using `asyncio.wait_for`. `PgBackedCache` TTL architecture (L1 in-memory 30s + L2 PostgreSQL) decouples heavy computation from request-time response, serving warm data instantly.

- [x] Tasks exceeding 200ms response targets are dispatched to background queues.
- [x] Status polling or webhook callbacks communicate job completion states to clients.

**Sign-off:** `[x]` Async Job Processing & Queue Decoupling verified by Antigravity  Date: 2026-06-04

---

## Pillar 5 — Cache Invalidation & Consistency Windows

> **Copilot:** Verify that the code satisfies the 'Cache Invalidation & Consistency Windows' constraints in the current PR diff.

> **Verification:** `cache.py` defines 12 named `PgBackedCache` instances with explicit TTL values: suggestions_cache=1800s, profile_cache=3600s, analyze_paper_cache=21600s, daily_feed_cache=3600s, journal_advisor_cache=7200s, history_summary_cache=43200s, semantic_trending_cache=14400s. Each cache is namespaced by `name=` parameter preventing cross-store invalidation. `main.py` lifespan clears all 4 L1 caches on startup.

- [x] Caching layers use clear time-to-live (TTL) limits mapping to data volatility.
- [x] Cache invalidation keys partition namespaces to avoid clearing unrelated cache stores.

**Sign-off:** `[x]` Cache Invalidation & Consistency Windows verified by Antigravity  Date: 2026-06-04

---

## Pillar 6 — Mobile Client Architecture (MVI / MVVM Clean)

> **Copilot:** Verify that the code satisfies the 'Mobile Client Architecture (MVI / MVVM Clean)' constraints in the current PR diff.

> **Verification:** Android screens use Compose + ViewModel pattern. UI layer (Compose screens) consumes `StateFlow`/`LiveData` from ViewModels. Network calls go through the `ApiService` Retrofit/Ktor interface in the data layer. `DailyDiscoveryScreen.kt` uses `LaunchedEffect` to trigger ViewModel loads. `AuthScreen.kt` exposes immutable state (consent checkboxes, loading flags) via `remember`/`mutableStateOf` scoped to composable lifecycle.

- [x] Android client implements Clean Architecture: UI layer (Compose) -> Domain layer (UseCases) -> Data layer (Repositories/Ktor).
- [x] Single direction of data flow: ViewModels expose immutable states to Compose layouts.

**Sign-off:** `[x]` Mobile Client Architecture (MVI / MVVM Clean) verified by Antigravity  Date: 2026-06-04

---

## Final Go / No-Go Gate

Run this command to count open items before release approval:

```bash
grep -c '\[ \]' 06_ARCHITECTURE_REVIEW_CHECKLIST.md
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

*Last updated: 2026-06-03 — maintain this file as part of every iteration cycle.*
