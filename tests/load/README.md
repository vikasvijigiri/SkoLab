# Load Testing — Skolab Backend

All load test scripts use [k6](https://k6.io/). Tests are designed to match the 5 pillars in `checklists/DEPLOYMENT_CHECKLIST.md`.

## Endpoints exercised (verified against the live OpenAPI, 2026-09-03)

| Script role | Route | Required param |
|---|---|---|
| Author search | `GET /api/v1/search_author` | `name` |
| Paper analysis (LLM) | `GET /api/v1/analyze_paper` | `title` |
| Personalised feed | `GET /api/v1/daily_feed` | — (`author_id` optional) |
| Health | `GET /health` | — |

> Earlier revisions of these scripts hit `/api/v1/authors/search` and
> `/api/v1/papers/search` with a `query=` param. **Neither route exists** — the
> app never had them — so those runs were load-testing 404s. If you add a route,
> re-check the paths here against `GET /openapi.json`.

## Prerequisites

```bash
# Install k6
winget install k6          # Windows
brew install k6            # macOS
# Or: https://k6.io/docs/getting-started/installation/

# Set the target URL
export BASE_URL=https://api.your-domain.com
# For staging:
export BASE_URL=http://localhost:8000
```

## Running Tests

| Test | Purpose | Command |
|---|---|---|
| `baseline.js` | Establish p50/p95/p99 at 1× expected DAU | `k6 run baseline.js` |
| `ramp_up.js` | Find ceiling — linear 0→2× peak in 10 min | `k6 run ramp_up.js` |
| `spike.js` | Instant 5× burst to simulate viral event | `k6 run spike.js` |
| `soak.js` | 2× load for 2 hours — detect memory leaks | `k6 run soak.js` |

## Saving Results

```bash
# Save results for archiving
k6 run --out json=results/baseline-$(date +%Y%m%d).json baseline.js
# Then copy the summary to /docs/load-test-results-YYYY-MM-DD.md
```

## Interpreting Results

- **p95 > 2s** → P95EndpointLatencyHigh alert will fire (see `infrastructure/alertmanager-alerts.yml`)
- **Error rate > 1%** → Investigate rate limiter, connection pool, or upstream circuit breaker
- **Soak memory growth > 20%** → Investigate background task leaks or SQLAlchemy session leaks

## CCU Limits (rate limiting is the Go gateway's job)

| Layer | Limit | Where |
|---|---|---|
| Gateway, global per-IP | 120 req/s, burst 30 | `middleware.NewRateLimiter` in `services/backend-go/main.go` |
| Gateway, `/api/v1/recommendations/*` | 5 req/s, burst 5 | `recRL` in `services/backend-go/main.go` |
| Edge | Cloudflare `rate_limit_search_auth` | Cloudflare WAF |

> **Rate-limit note:** the per-process `TokenBucket` / `RateLimiter` in the
> Python `main.py` (and its `strict_paths` list) was retired on 2026-09-04
> (`docs/plans/2026-09-04-retire-python-infra.md`) — it was redundant with the
> gateway limiter and wrong under more than one uvicorn worker. Load tests
> should expect 429s from the gateway's global per-IP limit, not from Python.
> A per-route strict limit for `/agent/chat` on the gateway is a follow-up.
