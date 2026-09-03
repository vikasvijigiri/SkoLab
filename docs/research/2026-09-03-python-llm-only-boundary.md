# What is the industry-standard structure for a Python-LLM-only backend with everything else on standard tooling?

**Asked because:** The user set a hard rule (2026-09-03) — `services/backend`
(Python) is for LLM/model inference only; every other concern must use
industry-standard tooling. This research grounds the migration plan
(`docs/plans/`) and the P0 slice.

**Verdict:** The Go-edge + Python-inference split is a canonical pattern
(Azure *Gateway Offloading*, *Bulkhead*, *Backends for Frontends*; already
this repo's `decisions/0002`). The refinement the rule needs: the **gateway
carries cross-cutting concerns only** (auth, TLS, rate limit, WAF, logging,
routing) — *never business logic*; a **Go service behind the gateway** owns
the non-LLM data/enrichment plane (author metrics, co-author graphs,
recommendation scoring, OpenAlex orchestration); **Python shrinks to LLM +
embedding inference**; long jobs move to an **async queue + worker**. Managed
pieces: Supabase (Postgres only), Upstash (Redis), Cloudflare (R2 + WAF/CDN),
Firebase (auth), Prometheus/OpenTelemetry (metrics).

## Findings

### 1. Is "Go edge + Python inference" an industry-standard split? — HIGH

- **Source:** Azure Architecture Center, *Design Patterns for Microservices*
  (opened). *Bulkhead* — "isolates critical resources (connection pools,
  memory, CPU) for each workload… prevents one service from causing cascading
  failures." *Backends for Frontends* / *Gateway Routing* — single client
  endpoint, services split behind it.
- **Source:** `decisions/0002-go-gateway-in-front-of-python.md` (opened) —
  already splits "fast, boring, high-volume (auth, CRUD)" from "slow, heavy,
  CPU/IO-bound (LLM, embeddings, scoring)"; rationale is event-loop
  starvation, verbatim the Bulkhead problem.
- **Source:** `docs/plans/2026-09-02-scale-latency-audit.md` (opened) —
  measured: single uvicorn worker + CPU-bound `encode()` starves every other
  request; Go-tier surfaces (type-ahead, leaderboard, profile sync) "already
  fine at 100 users", Python-tier is not.
- **Means here:** the split is sound and evidence-backed. The rule is a
  *sharpening* of 0002, not a new architecture — 0002 left a large non-LLM
  surface (authors, feed CRUD, recommendations, papers, OpenAlex client,
  cache, metrics) in Python that the rule now evicts.

### 2. What goes in the gateway vs a service behind it? — HIGH

- **Source:** Azure *Gateway Offloading pattern* (opened). Offload to the
  gateway: "certificate management, authentication, SSL termination,
  monitoring, protocol translation, or throttling." Constraints, quoted:
  - **"Business logic should never be offloaded to the gateway."**
  - "Only offload features that are used by the entire application."
  - "This pattern might not be suitable if it introduces coupling across
    services."
  - "Make sure the gateway doesn't become a bottleneck… sufficiently
    scalable."
- **Means here:** moving auth enforcement, rate limiting, the substring
  "WAF", and request metrics **out of `app/main.py` and into the Go
  gateway / Cloudflare is correct** and matches the pattern. But
  `authors.py` metrics, the `networkx` co-author graph, recommendation
  TF-IDF/scoring, and OpenAlex enrichment orchestration are **business
  logic** — they must land in a *service behind* the gateway, not in the
  gateway handler code itself. The existing gateway already does this right
  for `author.go` / `quest.go` (thin PG reads) but those are near-CRUD; the
  heavier logic needs its own module/service.

### 3. BaaS (Supabase Auth/Storage/PostgREST) vs custom services? — HIGH (internal)

- **Source:** `docs/plans/2026-09-02-scale-latency-audit.md` (opened),
  verbatim: "This app uses Supabase as hosted Postgres **only** — no Supabase
  Auth (Firebase does auth), Storage, or PostgREST. The anon/publishable keys
  are not needed; only `DATABASE_URL`. Neon would be an equivalent swap."
- **Source:** `decisions/0004-firebase-for-colab-profile.md` exists;
  `decisions/0001` — Firestore owns large docs + realtime.
- **Means here:** "industry-standard" for *this* repo does **not** mean
  adopting a BaaS data API. Auth is Firebase; hosted Postgres is Supabase;
  large-doc/realtime is Firestore. A PostgREST layer would fork the authz
  model (Firebase tokens vs Postgres RLS) — rejected. Object storage is the
  one storage gap still open (see finding 5).

### 4. Cross-cutting infra: cache, rate-limit, metrics — MEDIUM–HIGH

- **Source:** *Gateway Offloading* (opened) — rate limiting, logging,
  monitoring are named gateway concerns.
- **Source:** `decisions/0001-two-tier-caching.md` (opened) — Redis is
  already the designed upgrade: `PgBackedCache` "checks for `REDIS_URL` and
  prefers it when set"; Postgres L2 is the stopgap.
- **Source:** scale-latency-audit B8/B10 (opened) — hand-rolled
  SELECT-then-UPSERT cache races; in-process rate limiter multiplies under
  workers; both already flagged with fixes (Upstash, `SetTrustedProxies`).
- **Means here:** replace `PgBackedCache` L2 with Upstash Redis (no interface
  change needed — the abstraction was built for this); rate limiting →
  gateway/Cloudflare keyed on real client IP; metrics → Prometheus client
  library + OpenTelemetry (the repo already emits W3C `traceparent` and has
  an `infrastructure/` Prometheus+Grafana+Loki stack) instead of the
  hand-rolled `MetricsStore` with an `asyncio.Lock` on every request.

### 5. Long-running jobs and file exports — HIGH (internal, canonical elsewhere)

- **Source:** `docs/scaling-decision.md` (opened) — teleportation, PDF
  parse, CCPA/BibTeX exports run in FastAPI `BackgroundTasks` on the request
  event loop; local-disk `downloads/` breaks multi-node (404 on the wrong
  pod). Prescribes: object store + 15-min signed URLs; Redis/RabbitMQ broker
  + Celery/BullMQ workers; `202 Accepted` + `jobId` poll/WebSocket.
- **Source:** Azure patterns page (opened) — *Asynchronous Request-Reply*,
  *Competing Consumers*, *Queue-Based Load Leveling* are the named patterns
  for this.
- **Means here:** enrichment/export is neither "LLM" nor "gateway" nor
  "CRUD" — it is queue + worker. Under the new rule the worker that does LLM
  enrichment stays Python; the job orchestration/state is the Go service's;
  file output goes to R2 with a signed URL.

### 6. Polyglot cost for a small team — MEDIUM

- **Source:** web search synthesis (Chronosphere, Medium/CodeX polyglot
  articles — *summaries only, primaries not opened*, marked accordingly).
  Consensus snippet: "optimize for domain alignment, not language
  proliferation"; multiple runtimes → "divergent build pipelines, Docker
  images, monitoring setups"; "choose languages your team can realistically
  maintain."
- **Means here [PARTIALLY UNVERIFIED — based on search summaries]:** two
  runtimes (Go + Python) is justified by the genuine CPU-vs-IO split. Adding
  a **third** (a separate "thin non-LLM Python service") would be
  proliferation — so non-LLM logic should be ported into **Go**, not a
  second Python service. Keep it at exactly two deploy languages.

## Disagreements

- **Right-tool-per-service vs runtime sprawl.** Polyglot advocates say pick
  the best language per workload; small-team cautions say every extra runtime
  multiplies ops surface; Azure *Gateway Offloading* says the split "might
  not be suitable if it introduces coupling across services." Lean: keep the
  two-language split (Go edge+data, Python inference) because the resource
  profiles measurably diverge, but refuse a third runtime — non-LLM logic
  goes to Go.
- **Where OpenAlex/arXiv fetching lives.** It feeds LLM/embedding context, so
  an argument exists for Python. But it is I/O-bound HTTP orchestration with
  a Go client already present (`internal/services/openalex/client.go`), and
  the rule is "Python = inference only." Lean: fetching + caching → Go; the
  Python inference service receives already-assembled context. Flag for the
  plan.

## Not adopted

- **Supabase PostgREST / auto-CRUD API** — repo already fixed Supabase as
  "hosted Postgres only"; a data API would split authz across Firebase tokens
  and Postgres RLS. Rejected internally before this research.
- **Supabase Auth / Storage** — Firebase owns auth (`0004`); object storage
  should be Cloudflare R2 per `.claude/rules/edge-hosting.md` and
  `scaling-decision.md`.
- **Business logic in the Go gateway handlers** — violates *Gateway
  Offloading* ("business logic should never be offloaded to the gateway").
  Non-LLM logic goes in a service/module behind the gateway, not the proxy
  layer.
- **A dedicated model-serving framework (Triton / Seldon / KServe)** — the
  inference load is `bge-small` embeddings + third-party chat LLM calls on a
  free tier. A serving framework is GPU-scale infrastructure; revisit only if
  embeddings move to dedicated GPU. `sentence-transformers` in-process behind
  the FastAPI service is adequate (`decisions/0003`).
- **A separate thin non-LLM Python service** — third runtime for a
  two-person team; fold non-LLM work into Go instead (finding 6).
- **gRPC between gateway and Python** — the current HTTP reverse proxy
  (`main.go:reverseProxy`) is sufficient for JSON payloads at this scale;
  gRPC adds a schema-compilation step for no measured benefit yet.

## Sources

- Azure Architecture Center — *Gateway Offloading pattern*
  <https://learn.microsoft.com/en-us/azure/architecture/patterns/gateway-offloading>
  (opened, full)
- Azure Architecture Center — *Design Patterns for Microservices*
  <https://learn.microsoft.com/en-us/azure/architecture/microservices/design/patterns>
  (opened, full)
- `decisions/0001-two-tier-caching.md` (opened)
- `decisions/0002-go-gateway-in-front-of-python.md` (opened)
- `decisions/0003-self-hosted-embeddings.md` (opened, partial)
- `docs/scaling-decision.md` (opened, ~120 lines)
- `docs/plans/2026-09-02-scale-latency-audit.md` (opened, full)
- `services/backend-go/main.go` (opened, full) — current gateway routes + proxy
- `services/backend/app/api/v1/router.py`, `app/main.py`, `app/api/dependencies.py`,
  `app/db/pg_cache.py`, `app/core/cache.py`, `app/api/v1/endpoints/feed.py` (opened
  earlier this session for the backend critique)
- WebSearch result summaries on polyglot-microservices tradeoffs (Chronosphere;
  Medium/CodeX) — **summaries only, primaries not opened**; finding 6 marked
  partially unverified accordingly.
