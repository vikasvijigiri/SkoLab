# SLO alert runbooks

Every `SLOBurnRate*` alert links here through its `runbook_url`. Alerts come
from `skolab-slo.rules.yml`, which is generated from `generate.py`. External
probe and journey failures have their own runbook: [../RUNBOOK.md](../RUNBOOK.md).

## Reading a burn-rate alert

An alert means users are failing faster than the SLO can absorb, not that a
single threshold blipped. The labels tell you how urgent it is:

| `severity` | `window` | Meaning | Respond |
| --- | --- | --- | --- |
| `page` | `1h` | 2% of the 30-day budget gone in an hour (14.4x burn) | Now |
| `page` | `6h` | 5% gone in six hours (6x burn) | Within the hour |
| `ticket` | `3d` | 10% gone in three days (1x burn) | Next working day |

At this burn rate, a `1h` page means the whole month's budget is spent in about
two days. Alerts resolve on their own within minutes of a fix, because each one
also requires the short window (5m, 30m or 6h) to still be burning.

## Triage, for any SLO

1. **Was there a deploy?** Check Render's deploy history for the service
   named in `job`. `service.version` (the git commit) is on every span and
   in `target_info`. If the alert started within about 15 minutes of a deploy,
   **roll back first** (Render > service > Deploys > Rollback) and investigate
   afterwards.
2. **Where is it failing?** Open the *SkoLab backend metrics* dashboard with
   `service` = the alert's `job`. *Errors by route and status* and
   *p95 by route* show which route is burning.
3. **Why?** Click an exemplar dot on the latency panel, or search Tempo for
   `service.name=<job>` with `status=error`, to open a failing trace. The
   matching JSON log lines share its `trace_id`. Sentry holds the
   exception and stack trace.
4. **Is it saturation?** Check the dashboard's *Saturation* row: DB pool
   connections near `max_connections`, rising `acquire_waits`, event-loop lag
   above ~100ms (Python), or goroutine/heap growth (gateway).
5. **Is it a dependency?** `/readyz` on each service probes the database (and
   Redis on Python). Supabase and Redis status pages cover provider outages.

## Mitigation levers

- **Rollback**: Render keeps previous images, and rolling back is the
  fastest fix for a bad deploy.
- **Kill switch**: `KILL_SWITCHES=<path fragment>` on `skolab-backend-py`
  returns 503 for matching routes without a redeploy. Use it to shed a
  failing feature so it stops taking the rest of the API down. Those 503s
  still count against availability, so the budget keeps burning; the
  trade-off is deliberate.
- **Scale/pool**: `DB_POOL_SIZE`/`DB_MAX_OVERFLOW` (Python) and
  `DB_MAX_CONNS` (gateway) share the Supabase pooler's ~60 connections, so
  raise one only if the other has headroom.

## api-availability

Python API requests (all routes except compile) are returning 5xx. Compile has
its own SLO, so its failures never land here.

Common causes: a database outage or exhausted pool (`/readyz` fails,
`acquire_waits` climbs), a bad deploy, an unhandled exception in one route
(Sentry shows one dominant issue), or a kill switch someone left on.

## api-latency

More than 5% of Python API requests take longer than 1s.

Check event-loop lag first. A sync call made inside an `async def` handler
blocks every request on that worker, and it shows up as lag on all routes at
once. If only one route is slow, open its slow exemplar traces and look for DB
spans. If every route is slow but loop lag is flat, check the DB pool, then
Supabase.

## gateway-availability

Gateway requests (excluding compile and WebSocket upgrades) are returning 5xx.
The gateway owns user sync and WebSocket tickets and talks to Postgres
directly. Check its `/readyz`, then the gateway pool panels. A recovered panic
also counts as a 500 here and goes to Sentry.

## gateway-latency

More than 5% of gateway requests take longer than 1s. The usual cause is
Postgres latency or pool waits, because the gateway does little else. The
per-IP rate limiter answers 429, which counts as fast and successful here.

## compile-availability

More than 1% of LaTeX compiles are failing with 5xx. The gateway answers 503
when the compile backend is unreachable or exceeds 25s
(`colab.sandboxCallTimeout`).

- `COLAB_SANDBOX_URL` set: check the `colab-sandbox` container is up.
- Unset: compiles fall back to `skolab-backend-py`, which admits only
  `COLAB_MAX_CONCURRENT_COMPILES` (default 2) at once. A burst of large
  documents shows up as queueing, then 503s. Check *Compile requests by
  status*.

Quota refusals (429) are expected behavior and are not counted.

## compile-latency

More than 5% of compiles take longer than 10s. Look for one user compiling
very large documents (traces show the duration; the quota should cap their
volume) or for CPU starvation on a shared free-tier instance. Moving compile
to its own container (`docs/deploy/colab-sandbox.md`) is the structural fix.
