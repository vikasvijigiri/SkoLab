# World-Class Backend Checklist — Complete Nested Professional Production Grade

> **Purpose:** 13 complete domains every production-grade backend must satisfy.
> Copilot: on any backend task — scan the relevant domain, surface open blockers,
> and verify before implementation or deployment proceeds.
>
> Tags: [BLOCKER] [AUTO] [CI] [SECURITY] [INFRA]

---

## How to use
```bash
grep -c 'BLOCKER.*\[ \]' BACKEND_CHECKLIST.md    # must be 0 before release
grep -c 'SECURITY.*\[ \]' BACKEND_CHECKLIST.md   # security sweep
grep -c '\[ \]' BACKEND_CHECKLIST.md             # total open items
```

---

## Domain 1 — API Design
> **Copilot:** Check all Retrofit/controller files — every URL must contain `/v1/` or higher.
> Verify all endpoints return `{data, meta}` or `{error:{code,message,details}}` shape.
> Confirm OpenAPI spec exists and is auto-generated. Check pagination on every list endpoint.

- [ ] `[BLOCKER]` REST resource naming: nouns not verbs, plural collections — `/api/v1/papers` not `/api/v1/getPapers`
- [ ] `[BLOCKER]` HTTP status codes used semantically — 201 created, 204 deleted, 409 conflict, 422 validation, 429 rate-limited, never 200 with error body
- [ ] `[BLOCKER]` Consistent response envelope on all endpoints — success: `{data, meta:{page,total}}`; error: `{error:{code,message,details}}`
- [ ] HATEOAS `_links` included on resource responses for discoverability — `{self, next, related}`
- [ ] `[BLOCKER]` API versioned in URL path from day one — `/api/v1/`; breaking changes ship as `/api/v2/`
- [ ] `[BLOCKER]` OpenAPI 3.x spec is source of truth — auto-generated from annotations or contract-first; Swagger UI deployed; SDK auto-generated
- [ ] `[BLOCKER][AUTO][CI]` Request/response bodies validated with schema validator on every endpoint — 400 returned before business logic
- [ ] `[BLOCKER]` Idempotency keys supported on all POST mutation endpoints — `Idempotency-Key` header; result stored 24h; retry-safe
- [ ] `[BLOCKER]` Cursor-based pagination on all list endpoints — offset pagination breaks on concurrent inserts; `next_cursor` in meta
- [ ] Deprecation headers on deprecated endpoints — `Deprecation: true`, `Sunset: <date>` headers; clients can detect

**Sign-off:** `[ ]` _______________ Date: _______________

---

## Domain 2 — Authentication & Authorisation
> **Copilot:** Check JWT configuration for short-lived access tokens (15 min).
> Verify refresh token rotation is implemented. Scan for any `localStorage` token storage.
> Check auth endpoints have rate-limiting middleware. Verify PKCE is used for OAuth flows.

- [ ] `[BLOCKER][SECURITY]` Short-lived JWTs (15 min access) + long-lived refresh tokens (7–30 days) — access token in memory only; refresh in HttpOnly cookie
- [ ] `[BLOCKER][SECURITY]` Refresh token rotation on every use — old token invalidated immediately on new token issued
- [ ] `[BLOCKER][SECURITY]` Token revocation list in Redis — `jti` claim checked on every authenticated request; TTL matches token expiry
- [ ] `[BLOCKER][SECURITY]` Authorisation is ABAC or RBAC enforced server-side — never trust client-sent role claims; policy evaluated from database
- [ ] `[BLOCKER][SECURITY]` Sensitive endpoints require re-authentication — account delete, email change, billing access require fresh auth
- [ ] `[BLOCKER][SECURITY]` OAuth2/OIDC uses PKCE flow — no implicit flow; `state` parameter validated; no tokens in URL
- [ ] `[BLOCKER][SECURITY][AUTO]` Rate limiting on auth endpoints — 5 failed logins → 5min lockout; IP-based + account-based; anomaly alerts
- [ ] `[BLOCKER][SECURITY]` Service-to-service API keys — scoped to minimum permissions; rotatable without downtime; stored hashed in DB

**Sign-off:** `[ ]` _______________ Date: _______________

---

## Domain 3 — Database Design & Management
> **Copilot:** Check for a `/db/migrations/` directory with numbered Flyway/Liquibase files.
> Run EXPLAIN ANALYZE on all queries over 10ms. Verify all FK columns have DB constraints.
> Check HikariCP config. Verify backup automation exists in IaC.

- [ ] `[BLOCKER][AUTO][CI]` Schema migrations version-controlled and auto-run on deploy — Flyway / Liquibase; no manual SQL on production ever
- [ ] `[BLOCKER]` All foreign keys have DB-level constraints — `ON DELETE CASCADE` or `RESTRICT` explicitly set; no orphaned records
- [ ] `[BLOCKER][AUTO]` Indexes on all WHERE, JOIN, and ORDER BY columns — `EXPLAIN ANALYZE` on every query > 10ms; no sequential scans on tables > 10k rows
- [ ] `[BLOCKER][AUTO]` N+1 query problem eliminated — ORM uses eager loading / join fetch; no endpoint makes > N+2 queries per result count
- [ ] `[BLOCKER][INFRA]` Connection pool sized correctly — HikariCP: `minimumIdle=5`, `maximumPoolSize=20` per instance; pool wait time monitored
- [ ] `[INFRA]` Read replicas for read-heavy queries — analytics, feed, search → replica; writes → primary; replica lag monitored
- [ ] `[BLOCKER]` Soft deletes for all user-generated content — `deleted_at` timestamp; queries filter `WHERE deleted_at IS NULL`; nightly purge job
- [ ] `[BLOCKER][INFRA]` Automated backups in separate region — daily full backup; point-in-time recovery enabled; restore tested monthly; RTO < 4h
- [ ] `[BLOCKER][SECURITY]` PII columns encrypted at application layer — email, phone, sensitive data not in plaintext; separate key per user class

**Sign-off:** `[ ]` _______________ Date: _______________

---

## Domain 4 — Caching Strategy
> **Copilot:** Verify all Redis keys follow `cache:v{N}:{resource}:{id}` naming convention.
> Check that every mutation endpoint invalidates or updates its cache entry.
> Verify CDN Cache-Control headers are set on all public GET endpoints.
> Check Redis INFO stats for hit rate — must be ≥ 80%.

- [ ] `[BLOCKER][AUTO]` Cache-aside pattern for all read-heavy endpoints — check cache → miss → query DB → set cache → return; TTL matched to staleness tolerance
- [ ] `[BLOCKER]` Cache keys namespaced and versioned — `cache:v2:user:{id}:profile`; version prefix allows instant full invalidation on schema change
- [ ] `[BLOCKER]` Cache invalidation on every mutation — `DELETE cache:*:user:{id}:*` on profile update; invalidation event published to all instances
- [ ] `[AUTO][INFRA]` Cache hit rate ≥ 80% for cacheable endpoints — monitored in Redis dashboard; below 80% = TTL too short or key too granular
- [ ] `[BLOCKER]` Expensive aggregations cached separately — leaderboards, stats, counts recalculated every 5 min and cached; not per-request
- [ ] `[BLOCKER][INFRA]` CDN caching on public API responses — `Cache-Control: public, max-age=60` on `/feed/trending`; reduces origin load 70%+
- [ ] `[BLOCKER]` Cache stampede protection — single-flight pattern or probabilistic early expiry; only one request rebuilds on cache miss

**Sign-off:** `[ ]` _______________ Date: _______________

---

## Domain 5 — Async Jobs & Queues
> **Copilot:** Check all request handlers — any operation > 200ms must use a job queue.
> Verify a dead-letter queue is configured. Check for exponential backoff with jitter on retries.
> Verify queue depth alert is configured. Check all cron jobs use a distributed lock.

- [ ] `[BLOCKER]` All operations > 200ms off the request thread — email, PDF, notifications, ML inference = async; return `jobId` to client
- [ ] `[BLOCKER][AUTO]` Dead-letter queue configured — after max retries (3), job moves to DLQ; alert fires; jobs never silently dropped
- [ ] `[BLOCKER]` Exponential backoff with jitter on retries — retry 1: 30s, retry 2: 5min, retry 3: 30min; ±20% jitter; prevents thundering herd
- [ ] `[BLOCKER]` Job idempotency — processing same job twice produces same result; DB upsert not insert; safe to replay from DLQ
- [ ] `[BLOCKER]` Job status queryable by `jobId` — `GET /api/v1/jobs/{id}` returns `{status, progress, result}`
- [ ] `[BLOCKER][AUTO][INFRA]` Queue depth and consumer lag monitored — alert: depth > 1000 for > 5 min; auto-scale workers on depth threshold
- [ ] `[BLOCKER]` Scheduled cron jobs use distributed lock — Redis `SETNX` or DB lock before execution; no duplicate runs across instances

**Sign-off:** `[ ]` _______________ Date: _______________

---

## Domain 6 — Security Hardening
> **Copilot:** Run `semgrep` SAST on codebase — zero critical findings.
> Check all SQL queries use parameterised statements — no string concatenation.
> Verify HSTS, CSP, and X-Frame-Options headers on all responses.
> Confirm secrets are loaded from vault, not environment files.

- [ ] `[BLOCKER][SECURITY]` OWASP Top 10 addressed — injection, broken auth, XSS, SSRF, misconfiguration all mitigated; sign-off in `/docs/security-review.md`
- [ ] `[BLOCKER][SECURITY][CI]` All inputs parameterised — zero string-concatenated SQL; ORM prepared statements; verified with SAST (Semgrep / CodeQL)
- [ ] `[BLOCKER][SECURITY]` HTTPS enforced everywhere — `Strict-Transport-Security: max-age=31536000; includeSubDomains; preload`
- [ ] `[BLOCKER][SECURITY]` Security headers on all responses — `Content-Security-Policy`, `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`, `Referrer-Policy`
- [ ] `[BLOCKER][SECURITY]` Secrets in vault — HashiCorp Vault / AWS Secrets Manager; zero secrets in env files or code; rotated on schedule
- [ ] `[BLOCKER][SECURITY][CI]` SAST + DAST in CI — Semgrep / CodeQL for SAST; OWASP ZAP against staging for DAST; block on critical severity
- [ ] `[BLOCKER][SECURITY][CI]` SBOM generated and CVE-scanned on every build — CycloneDX + Grype / Trivy; block on CVSS ≥ 7; alert on CVSS 4–6
- [ ] `[BLOCKER][SECURITY]` External penetration test completed before first public launch — all critical/high findings remediated; report archived
- [ ] `[BLOCKER][SECURITY]` Immutable audit log for all privileged actions — actor, timestamp, resource, action; append-only; tamper-evident

**Sign-off:** `[ ]` _______________ Date: _______________

---

## Domain 7 — Scalability & Reliability
> **Copilot:** Check all services for in-process session state — must use Redis.
> Verify auto-scaling configuration exists in IaC. Check circuit breaker configuration
> on all downstream calls. Verify `/health/live` and `/health/ready` endpoints exist.
> Confirm SLO targets are documented and dashboard exists.

- [ ] `[BLOCKER][INFRA]` All services stateless — session state in Redis; horizontal scaling = add instance, not reconfigure
- [ ] `[BLOCKER][INFRA]` Auto-scaling configured — scale out on CPU > 70% or queue depth > threshold; scale-in cooldown 5 min
- [ ] `[BLOCKER]` Circuit breakers on all downstream calls — open after 5 failures in 10s; half-open probe every 30s (Resilience4j)
- [ ] `[BLOCKER]` Bulkhead pattern — thread pools isolated per downstream dependency; slow DB cannot starve cache-only requests
- [ ] `[BLOCKER][INFRA]` Graceful shutdown — SIGTERM: stop accepting → drain queue → complete in-flight → exit; 30s window
- [ ] `[BLOCKER][INFRA]` `/health/live` and `/health/ready` with distinct semantics — live = process alive; ready = dependencies healthy; used by load balancer
- [ ] `[INFRA]` Chaos engineering in staging — periodic fault injection: kill instances, inject latency, drop DB connections; graceful degradation verified
- [ ] `[BLOCKER][INFRA]` SLA/SLO defined and measured — uptime 99.9%, P99 < 500ms, error budget 43.8min/month; dashboard live before launch

**Sign-off:** `[ ]` _______________ Date: _______________

---

## Domain 8 — Observability (Logs, Metrics, Traces)
> **Copilot:** Verify OpenTelemetry SDK is initialised and `traceId` appears in all log lines.
> Check Prometheus metrics: `requests_total`, `errors_total`, `request_duration_seconds` exist per service.
> Verify every alert has a runbook URL. Check on-call rotation is defined in PagerDuty/OpsGenie.

- [ ] `[BLOCKER][AUTO]` Distributed tracing across all services — OpenTelemetry; `traceId` in every log line and DB query; Jaeger / Tempo for visualisation
- [ ] `[BLOCKER][AUTO][INFRA]` RED metrics per service — `requests_total`, `errors_total`, `request_duration_seconds` histogram; Prometheus + Grafana dashboard per service
- [ ] `[BLOCKER][AUTO][INFRA]` USE metrics per infrastructure — CPU %, memory %, disk I/O, network saturation; Node Exporter + cAdvisor
- [ ] `[BLOCKER][AUTO]` Structured JSON logs with mandatory fields — `timestamp`, `level`, `service`, `traceId`, `userId`; aggregated to Loki / Elasticsearch; queryable by field
- [ ] `[BLOCKER][INFRA]` Every alert has a runbook — alert fires only if it requires human action; runbook URL in alert body; no noisy non-actionable alerts
- [ ] Business metrics alongside technical metrics — DAU, conversion, feature adoption on same Grafana instance as infra metrics
- [ ] `[BLOCKER][INFRA]` On-call rotation defined — primary → secondary → engineering manager escalation; PagerDuty / OpsGenie; every alert has an owner

**Sign-off:** `[ ]` _______________ Date: _______________

---

## Domain 9 — Data Integrity & Consistency
> **Copilot:** Check all multi-step DB operations for transaction boundaries.
> Verify optimistic locking (version column or ETag) on all concurrent update endpoints.
> Check for a transactional outbox pattern on cross-service events.
> Verify `processed_events` table exists for idempotent consumers.

- [ ] `[BLOCKER]` All multi-step operations in DB transactions — create + update count = single transaction; rollback on any step failure
- [ ] `[BLOCKER]` Optimistic locking on all concurrent update paths — `version` column or `ETag`; `UPDATE WHERE version=N`; 409 Conflict on mismatch
- [ ] `[BLOCKER]` Transactional outbox pattern for cross-service events — event written to DB in same transaction as data; relay publishes to queue
- [ ] `[BLOCKER]` At-least-once delivery with idempotent consumers — `processed_events` table checked before acting; no duplicate side effects
- [ ] `[BLOCKER][AUTO][CI]` Data validation at every layer — API schema → domain business rules → DB constraints; defence in depth
- [ ] Eventual consistency windows documented per feature — clients handle stale data gracefully; UI shows optimistic value; reconciles on refresh

**Sign-off:** `[ ]` _______________ Date: _______________

---

## Domain 10 — Backend Testing
> **Copilot:** Check for Testcontainers in the test dependencies — no H2 substituting for Postgres.
> Verify contract test module exists. Check that E2E smoke tests run in the CD pipeline.
> Verify load test scripts exist in `/tests/load/`. Run mutation testing and check score > 70%.

- [ ] `[BLOCKER][AUTO][CI]` Unit test coverage ≥ 80% on all service and domain logic — JUnit5 / pytest / Jest; coverage report in CI; no DB or network in unit tests
- [ ] `[BLOCKER][AUTO][CI]` Integration tests use real DB via Testcontainers — real Postgres/Redis; no H2 substitute; catches DB-specific behaviour and migrations
- [ ] `[BLOCKER][AUTO][CI]` Consumer-driven contract tests (Pact / OpenAPI) — consumer drives contract; provider verifies in CI; shape drift caught before deploy
- [ ] `[BLOCKER][AUTO][CI]` E2E smoke tests on staging after every deploy — sign up → create resource → fetch → delete; deploy halted on failure
- [ ] `[BLOCKER][AUTO]` Load tests on every release candidate — k6 / Locust; baseline, ramp-up, spike, soak; results in `/docs/load-test-results-<ver>.md`
- [ ] `[AUTO]` Mutation testing quarterly — PiTest / Stryker; mutation score > 70%; tests that never catch mutations deleted or improved
- [ ] `[BLOCKER][SECURITY][CI]` OWASP ZAP DAST scan on staging in CI — new endpoints automatically in scope; block on critical findings

**Sign-off:** `[ ]` _______________ Date: _______________

---

## Domain 11 — Infrastructure as Code
> **Copilot:** Check for a `/infra/terraform/` or `/infra/pulumi/` directory.
> Verify no manually created cloud resources exist (run `terraform plan` — zero drift).
> Confirm environments (dev/staging/prod) are in separate accounts or projects.
> Check DR plan documents RPO < 1h and RTO < 4h.

- [ ] `[BLOCKER][INFRA]` All infrastructure in Terraform / Pulumi / CDK — zero click-ops; every resource reproducible from code; state in remote backend
- [ ] `[BLOCKER][INFRA]` IaC changes through PR + plan review — `terraform plan` output in PR; apply after approval; daily drift detection
- [ ] `[BLOCKER][INFRA][SECURITY]` Environments isolated — dev / staging / prod in separate cloud accounts or projects; no shared credentials between environments
- [ ] `[BLOCKER][INFRA]` Immutable infrastructure — containers/images replaced not patched; no SSH into running production containers
- [ ] `[BLOCKER][INFRA]` Disaster recovery plan — RPO < 1h, RTO < 4h; tested with full failover drill annually; results documented
- [ ] `[BLOCKER][INFRA]` Multi-AZ deployment — DB, cache, and app tier span ≥ 2 availability zones; load balancer distributes across zones; no single-AZ SPOFs

**Sign-off:** `[ ]` _______________ Date: _______________

---

## Domain 12 — Cost & Efficiency
> **Copilot:** Check cloud cost dashboard exists and is reviewed weekly. Verify reserved/committed
> instances are purchased for steady-state workloads. Run cloud provider recommendations tool
> (AWS Trusted Advisor / GCP Recommender) and flag unused resources.

- [ ] `[INFRA]` Cloud cost dashboard live with per-service breakdown — reviewed weekly; spike > 20% triggers alert
- [ ] `[INFRA]` Reserved / committed-use instances for steady-state baseline — on-demand for burst only; saves 30–60% vs full on-demand
- [ ] `[INFRA]` Unused resources audited and purged monthly — idle instances, orphaned volumes, stale snapshots; every resource tagged with owner and expiry
- [ ] `[INFRA]` Inter-AZ and egress data transfer costs modelled — same-AZ placement for latency-sensitive services; CDN reduces egress cost
- [ ] `[INFRA]` Scale-down as aggressive as scale-up — scale-in policy tested; minimum instance count = real baseline, not inflated safety buffer

**Sign-off:** `[ ]` _______________ Date: _______________

---

## Domain 13 — Developer Experience
> **Copilot:** Verify `docker-compose up` starts all services from a fresh clone.
> Check that `/docs` or `localhost:8080/docs` serves auto-generated API documentation.
> Verify pre-commit hooks are configured. Check for a seed data script.
> Confirm ADRs exist in `/docs/adr/`.

- [ ] `[BLOCKER]` Local dev starts with one command — `docker-compose up`; new engineer productive in < 30 min; documented in README
- [ ] Hot reload in local dev — code change to result < 5s; no 2-minute rebuilds for a one-line change
- [ ] Seed data script for realistic local dataset — `./scripts/seed.sh` creates users, content, connections; no blank slate on fresh setup
- [ ] `[BLOCKER]` API docs auto-served at `/docs` in local dev — Swagger UI always in sync with code; never manually maintained
- [ ] `[BLOCKER][AUTO][SECURITY]` Pre-commit hooks: formatting + linting + secret scanning — Husky / pre-commit; fail before CI sees it
- [ ] Postman / Bruno collection committed to repo — all endpoints with request/response examples; environment variables for local/staging/prod
- [ ] `[BLOCKER]` ADRs for all architectural decisions — `/docs/adr/001-postgres-over-mysql.md`; explains why; prevents re-litigating settled choices

**Sign-off:** `[ ]` _______________ Date: _______________

---

## Master Backend Release Gate

```bash
# All must output 0 before production release
grep -c 'BLOCKER.*\[ \]' BACKEND_CHECKLIST.md
grep -c 'SECURITY.*\[ \]' BACKEND_CHECKLIST.md
```

### Domain completion matrix

| Domain | Blockers cleared | Overall complete |
|---|---|---|
| 1 — API design | `[ ]` | `[ ]` |
| 2 — Authentication & authorisation | `[ ]` | `[ ]` |
| 3 — Database design & management | `[ ]` | `[ ]` |
| 4 — Caching strategy | `[ ]` | `[ ]` |
| 5 — Async jobs & queues | `[ ]` | `[ ]` |
| 6 — Security hardening | `[ ]` | `[ ]` |
| 7 — Scalability & reliability | `[ ]` | `[ ]` |
| 8 — Observability | `[ ]` | `[ ]` |
| 9 — Data integrity & consistency | `[ ]` | `[ ]` |
| 10 — Backend testing | `[ ]` | `[ ]` |
| 11 — Infrastructure as code | `[ ]` | `[ ]` |
| 12 — Cost & efficiency | `[ ]` | `[ ]` |
| 13 — Developer experience | `[ ]` | `[ ]` |

**Final backend approval:** `[ ]` _______________ Date: _______________

---
*Last updated: 2026-06-04*
