# Android App — Pre-Deployment Readiness Checklist

> **Purpose:** This file is the single source of truth for deployment readiness.
> Copilot: before any deployment-related task, scan this file and report which checks are incomplete.
> A release is only approved when every section shows `[x]` on all items.

---

## How to use this file

- Mark each item `[x]` as you complete it.
- Run `grep -c '\[ \]' DEPLOYMENT_CHECKLIST.md` to count remaining open items — zero means ready.
- Copilot instructions at the top of each section describe what to verify automatically where possible.

---

## Pillar 1 — Structured Logging (Failure Tracing per Endpoint)

> **Copilot:** Check that every API endpoint file contains a logger call with `request_id`, `status_code`,
> and `latency_ms` fields. Flag any endpoint handler that uses `println`, `System.out`, or bare `Log.d`
> without structured fields. Also verify a log-masking middleware is registered in the app's
> `NetworkModule` or equivalent DI config.

- [ ] `X-Request-ID` header is injected at the API gateway / OkHttp interceptor and forwarded to every downstream service call
- [ ] All log lines are structured JSON: `timestamp`, `level`, `service`, `endpoint`, `method`, `status_code`, `latency_ms`, `request_id`, `user_id`
- [ ] Root-cause exceptions are logged **at the throw site**, not only at the outer catch handler
- [ ] Stack traces are included in `debug` builds; sanitized (no stack trace, no internal paths) in `release` builds
- [ ] Slow-response alert defined: P95 latency threshold per endpoint; alert fires after 3 consecutive breaches
- [ ] Log aggregation pipeline wired up (Firebase Crashlytics + remote sink: CloudWatch / Datadog / Loki)
- [ ] Dashboards and alert channels exist and are tested **before** launch day
- [ ] PII fields (phone, email, auth tokens) are masked by log middleware — verified by unit test asserting no raw PII in log output

**Sign-off:** `[ ]` Logging verified by _______________  Date: _______________

---

## Pillar 2 — Stress Test & Ramp-Up Strategy

> **Copilot:** Look for a `/tests/load/` or `/k6/` directory. If absent, flag it.
> Check that the load test scripts define at least four scenarios: baseline, ramp-up, spike, soak.
> Verify that documented CCU limits match any rate-limiting config in the backend (e.g. `RateLimiterConfig`).

- [ ] **Baseline load test** completed at 1× expected DAU — p50, p95, p99 latency and error rate recorded per endpoint
- [ ] **Ramp-up test** completed: linear increase 0 → 2× peak over 10 minutes — ceiling (error inflection point) documented
- [ ] **Spike test** completed: instant burst to 5× (simulates push notification blast / viral event)
- [ ] **Soak test** completed: 2× load sustained for ≥ 2 hours — no memory leak, connection pool exhaustion, or DB starvation observed
- [ ] Concurrent user (CCU) limits documented per tier (e.g. Free ≤ 500 CCU, Pro ≤ 5 000 CCU)
- [ ] Rate-limiting middleware (token bucket) enforces the CCU limits above
- [ ] Circuit breakers configured on **all** external API/service calls — open after 5 failures, half-open probe every 30 s
- [ ] Load test scripts committed to repo under `/tests/load/` and runnable via CI (`k6 run` / `locust`)
- [ ] Test results report saved to `/docs/load-test-results-<date>.md`

**Sign-off:** `[ ]` Stress tests verified by _______________  Date: _______________

---

## Pillar 3 — CPU-Bound Task Efficiency & Scaling Decision

> **Copilot:** Search for `Dispatchers.Main` usage in files under `*/viewmodel/` or `*/repository/` —
> any heavy computation (file parsing, encryption, ML inference) on the Main dispatcher is a bug.
> Check that WorkManager is used for tasks tagged deferrable. On the server side, flag any request
> handler whose average response time exceeds 200 ms in the load test results.

### Android client
- [ ] CPU-bound tasks identified with Android Profiler (ML inference, image processing, report generation, CSV parsing)
- [ ] All heavy work runs on `Dispatchers.IO` or `Dispatchers.Default` — **never** `Dispatchers.Main`
- [ ] Deferrable background work uses `WorkManager` with proper constraints (network, battery)
- [ ] No ANR-risk operations (> 5 s on main thread) — verified by Strict Mode in debug builds

### Server side
- [ ] All tasks > 200 ms moved off the request thread into an async job queue (SQS / BullMQ / Cloud Tasks)
- [ ] Queue workers return a `jobId`; client polls or receives result via WebSocket / FCM
- [ ] Vertical scaling evaluated first: compute-optimised instance tested — decision documented in `/docs/scaling-decision.md`
- [ ] Horizontal auto-scale trigger defined: CPU > 70% sustained for 5 min **or** queue depth > threshold
- [ ] Stateless workers confirmed — any node can handle any job (no local state / local file dependencies)
- [ ] Cold-start latency measured for serverless functions; provisioned concurrency added if P99 > 1 s

**Sign-off:** `[ ]` Scaling decision verified by _______________  Date: _______________

---

## Pillar 4 — CDN Framework Configuration

> **Copilot:** Check the app's `build.gradle` or `network_security_config.xml` for hardcoded asset URLs
> that bypass the CDN. Verify that image loading (Glide / Coil) uses the CDN base URL defined in
> `BuildConfig` or a config file — not a raw origin URL. Check `Cache-Control` headers are set on
> static asset responses.

- [ ] All static assets (JS, CSS, images, fonts) served via CDN — **not** directly from origin
- [ ] `Cache-Control: max-age=31536000, immutable` set on fingerprinted/versioned assets
- [ ] CDN edge PoPs verified for primary user geographies (India: Mumbai, Chennai, Delhi nodes confirmed)
- [ ] Cache invalidation strategy defined:
  - Content hashing for static assets (no manual purge needed)
  - TTL per API response type documented
  - Purge API tested at least once manually
- [ ] Profile images and paper thumbnails served via image CDN (Cloudinary / imgix / CloudFront + resize)
  - WebP format served where supported
  - Images sized by device DPR — no 4K images for 360dp avatar slots
- [ ] Origin shield enabled — cache misses do not hit origin directly
- [ ] CDN hit rate ≥ 85% validated from CDN analytics after smoke test
- [ ] Fallback TTL configured: CDN serves stale content if origin is unreachable

**Sign-off:** `[ ]` CDN configuration verified by _______________  Date: _______________

---

## Pillar 5 — Cloudflare Hardening

> **Copilot:** Verify the Cloudflare config file (terraform / `wrangler.toml` / exported JSON) includes:
> SSL mode `full_strict`, WAF ruleset enabled, rate limiting on `/api/auth/*` paths,
> and Bot Fight Mode on. Flag any DNS record with `proxied: false` on application subdomains.

- [ ] All application DNS records proxied through Cloudflare (orange cloud enabled)
- [ ] **SSL/TLS mode set to `Full (Strict)`** — not Flexible (Flexible sends unencrypted traffic to origin)
- [ ] WAF OWASP Core Rules enabled — initially in `Challenge` mode, tuned to `Block` before launch
- [ ] Rate limiting rule on auth endpoints: `/api/login`, `/api/register`, `/api/password-reset` → max 5 req / IP / min → HTTP 429
- [ ] Bot Fight Mode (or Super Bot Fight Mode) enabled — Googlebot bypass rule verified
- [ ] Cache rules configured:
  - Static assets: `Cache Everything`, long TTL
  - Authenticated API routes: `Bypass Cache`
  - Public read-only API (e.g. `/api/feed`): `Cache` with short TTL (60–300 s)
- [ ] DDoS protection alert thresholds set in Cloudflare dashboard — on-call team is paged before origin feels the attack
- [ ] Cloudflare Worker for edge auth / geo logic deployed and tested (if applicable)
- [ ] Firewall rules block traffic from high-risk ASNs to admin routes
- [ ] Origin IP is **not** publicly exposed anywhere (DNS history, old MX records, error pages)

**Sign-off:** `[ ]` Cloudflare hardening verified by _______________  Date: _______________

---

## Final Go / No-Go Gate

Run this command to count open items before any release:

```bash
grep -c '\[ \]' DEPLOYMENT_CHECKLIST.md
```

**Release is approved only when the output is `0`.**

| Check | Status |
|---|---|
| All Pillar 1 items complete | `[ ]` |
| All Pillar 2 items complete | `[ ]` |
| All Pillar 3 items complete | `[ ]` |
| All Pillar 4 items complete | `[ ]` |
| All Pillar 5 items complete | `[ ]` |
| Load test results report committed | `[ ]` |
| Scaling decision doc committed | `[ ]` |
| **Final approval** | `[ ]` ______________ Date: ______________ |

---

*Last updated: 2026-06-03 — maintain this file as part of every release cycle.*
