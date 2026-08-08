---
name: k6-load-test
description: Run and interpret the k6 load tests in tests/load/ (baseline, ramp_up, spike, soak) against the SkoLab backend. Use when asked to load-test, stress-test, or check capacity/latency-under-load for services/backend, or before a release per tests/load/README.md. Do NOT use for functional correctness or unit/integration testing (`backend-test-suite`), or to write new load-test scripts — this only runs and interprets the existing four.
---

# k6 load testing

`tests/load/` already has four scripts and a README with thresholds — this
skill is about running them correctly and reading the result, not writing
new ones. Read `tests/load/README.md` first if it's been a while; it has the
authoritative CCU/rate-limiter numbers this reasoning depends on.

## Before running anything

1. **Confirm k6 is installed:** `k6 version`. If missing, install it
   (`winget install k6` on Windows per the README) — this installs new
   software on the machine, so if you're not sure the user wants that,
   confirm first rather than silently installing.
2. **Confirm the target.** `BASE_URL` defaults to `http://localhost:8000` in
   every script. Running against local `uvicorn --reload` is always safe.
   **Do not point any of these scripts at a staging or production URL
   without explicit user confirmation** — `ramp_up`/`spike`/`soak` are
   designed to push 2–5× peak load and can trip the real
   `P95EndpointLatencyHigh` Alertmanager rule (see AGENTS.md /
   `infrastructure/`) or page someone through PagerDuty on a shared
   environment. `baseline.js` alone (1× DAU, 50 VUs) is the only one mild
   enough to be low-risk against a shared target, and even that should be
   confirmed if the target isn't local.
3. Make sure the target backend is actually up (`curl -s -o /dev/null -w
   "%{http_code}" $BASE_URL/health`) before running — a load test against a
   dead server just produces 100% connection-refused errors, not a real
   signal.

## Which script to run

| Script | Duration | Purpose | Default safety |
|---|---|---|---|
| `baseline.js` | ~6.5 min | p50/p95/p99 at 1× DAU | Safe locally; ask before staging |
| `ramp_up.js` | ~10 min | find the ceiling, 0→2× peak | Ask first — deliberately overloads |
| `spike.js` | short burst | 5× instant burst | Ask first — deliberately overloads |
| `soak.js` | **2 hours** | memory-leak detection at 2× load | Ask first; also run via `run_in_background` — don't block the session for 2 hours |

## Running

```bash
cd tests/load
BASE_URL=http://localhost:8000 k6 run baseline.js
```

To archive results (useful before a release, per the README):

```bash
k6 run --out json=results/baseline-$(date +%Y%m%d).json baseline.js
```

## Interpreting results (from tests/load/README.md — don't re-derive these)

- `p(95) http_req_duration > 2000ms` → the same threshold the
  `P95EndpointLatencyHigh` alert uses. A local breach here means it would
  fire in production.
- `http_req_failed` rate `> 1%` → investigate the rate limiter
  (`TokenBucket` in `main.py`), connection pool, or an upstream circuit
  breaker before assuming it's the backend logic itself.
- `soak.js` memory growth `> 20%` over the 2-hour run → look for background
  task leaks or SQLAlchemy session leaks, not a generic "restart it" fix.

## Reporting

Report the actual per-endpoint p50/p95/p99 and error rate k6 prints in its
summary — not just "thresholds passed/failed". If a threshold failed, name
which one and the actual number against the limit.

## Routing

- Mandatory validator: none beyond k6's own summary output — that is the
  evidence, not a separate check.
- Preceded by: confirm the target backend is up (see "Before running
  anything") — a load test against a dead server is not a signal.
- Terminal handoff: `releasing`, if this ran as a pre-release capacity check
  per `tests/load/README.md`.

## Success

The intended script ran to completion against a confirmed-safe target, and
the real p50/p95/p99 and error-rate numbers were reported against the
`tests/load/README.md` thresholds — not just pass/fail.
