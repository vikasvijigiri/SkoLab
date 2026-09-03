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

## CCU Limits (Based on Rate Limiter Config)

| Tier | Strict Endpoints | Standard Endpoints |
|---|---|---|
| Per-IP limit | 5 req/min | 60 req/min |
| Backend enforcement | `TokenBucket` in `main.py` (`strict_paths`) | `TokenBucket` in `main.py` |
| Edge enforcement | Cloudflare `rate_limit_search_auth` | N/A |

> **Rate-limit note:** `strict_paths` in `main.py` no longer lists the
> non-existent `/api/v1/papers/search` / `/api/v1/authors/search`; the strict
> (5 req/min) tier is now `/agent/chat` and `/export` only. `/api/v1/search_author`
> is deliberately on the **standard** 60 req/min tier because it backs live
> autocomplete — so `spike.js` will not see 429s from author search. Whether the
> expensive LLM GETs (`daily_conjecture`, `discovery/*`) should join the strict
> tier is an open product decision.
