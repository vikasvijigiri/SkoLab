# Backend metrics and traces

## External availability and complete journey

`checks.json`, `journey.js`, `availability-dashboard.json` and `provision.py`
manage the external checks and their alerts separately from application metrics.
The provisioner uses stable jobs/UIDs and updates existing resources; duplicate
jobs are rejected. It never replaces the stack's global notification policy or
other teams' checks. Grafana's built-in per-check alerts are disabled to avoid
duplicate availability notifications.

Availability checks run every minute from Mumbai and Oregon. Go checks HTTP 200
and `status=online`. Python checks HTTP 200, `status=ready`, and healthy database
and cache. Both require HTTPS and reject redirects. The complete backend journey
runs every 30 minutes from Mumbai only: fresh Firebase login, identity sync,
compile a valid tiny document, validate `%PDF-` and `%%EOF`, issue two single-use
tickets, and confirm a message reaches the second workspace connection. This is
not a browser UI test or a load test. Its workspace must contain no real users.

### Deployment: GitHub Actions, nothing local

`.github/workflows/observability-deploy.yml` applies all monitoring to Grafana
Cloud on every merge to `master` that touches `services/observability/`. It can
also be run by hand: **Actions > Deploy monitoring to Grafana Cloud > Run
workflow**, with an optional end-to-end notification test. Each run applies the
SLO rules (`slo/provision.py`), then these checks, their alerts and dashboards.
Nothing is installed or run on a developer machine. Probes and the k6 journey
execute on Grafana's own infrastructure, and CI tests the k6 script against
local fixtures on GitHub's runners.

Configure these **repository secrets** (Settings > Secrets and variables >
Actions). Never commit them:

| Secret | Value |
| --- | --- |
| `GRAFANA_URL` | `https://<stack>.grafana.net` |
| `GRAFANA_TOKEN` | Grafana service-account token (Admin, so it can also write the journey's secure values) |
| `GRAFANA_SM_TOKEN` | Synthetic Monitoring access token (Testing & synthetics > Synthetics > Config) |
| `GRAFANA_CONTACT_POINT` | Contact point that receives all SkoLab alerts (`skolab-oncall`) |
| `SKOLAB_FIREBASE_API_KEY`, `SKOLAB_SYNTHETIC_EMAIL`, `SKOLAB_SYNTHETIC_PASSWORD` | Journey login fixtures (optional; they turn the journey on) |
| `FIREBASE_SERVICE_ACCOUNT` | Firebase Admin SDK key (JSON). The deploy keeps the monitoring account existing, enabled and email-verified, because the backend refuses unverified accounts. Admin-level: never commit it, rotate if exposed |

The provisioner discovers the regional API server and the metrics and logs data
sources from the Synthetic Monitoring plugin's settings. An OTLP ingestion
token is not a management token.

**Turning on the journey.** Until the three login secrets are set, deploys
apply the availability checks only. To enable it:

1. Choose the monitoring account's email and password (enable the
   email/password sign-in provider). With `FIREBASE_SERVICE_ACCOUNT` set, the
   deploy creates the account already email-verified (or repairs an existing
   one); without it, create and verify the account yourself. Then: on each deploy the provisioner signs in
   as it, syncs its profile, and creates its monitoring workspace through
   `POST /api/v1/workspaces` with a fixed `Idempotency-Key`. The first deploy
   creates the workspace and every later one gets the same one back. That
   workspace must never be shared with real users. Application authorization
   still applies. The account consumes normal quota: 48 daily runs at four
   units (one compile, charged once by the gateway) is 192 units, below the
   default 600-unit daily limit. With lower limits, lengthen the journey interval rather than bypass
   quotas.
2. Make sure the service account behind `GRAFANA_TOKEN` can write secure
   values (the Admin role does). The deploy uploads the journey values to
   Grafana's secret manager, which only the `synthetic-monitoring` decrypter
   can read.
3. Add the three login repository secrets and re-run the workflow.

When running `provision.py` by hand, `--existing-secrets` reuses secure values
already created in the Synthetic Monitoring Secrets UI, and
`SKOLAB_SYNTHETIC_WORKSPACE_ID` pins a specific workspace. CI uses neither.

Before scheduling the journey, the deploy runs it once as an **ad-hoc check on
a Grafana probe** and reads the result back from the stack's Loki logs. A
failing journey is never scheduled (it would alert every 30 minutes), and the
run turns red with the failing step's message, with credentials, tickets and
tokens redacted. To repeat that check without deploying, run
`provision.py --verify-journey` from the same workflow environment.

The same commands work from any machine with the environment variables set, or
with the ignored root `.env`:

```sh
python services/observability/provision.py                    # preview check definitions (no network)
python services/observability/provision.py --apply            # availability checks, alerts, dashboard
python services/observability/provision.py --apply --journey  # ...plus the verified journey
python services/observability/provision.py --verify-journey   # one cloud-side journey run
python services/observability/provision.py --test-alert       # fire, recover and clean up a test alert
```

If the preferred probes are unavailable, supply two online public probe names
using `--probes Mumbai Oregon`. Multiple Prometheus datasources are handled by
discovering the one configured for Synthetic Monitoring; override with
`GRAFANA_PROMETHEUS_UID` only if your setup requires it.

See [RUNBOOK.md](RUNBOOK.md) for incident response, target definitions, notification
delivery verification, and provider integration limits. The observability CI
workflow (`observability.yml`) runs the actual k6 script against local
HTTP/WebSocket fixtures on GitHub's runners, including authentication failure,
invalid PDF and dropped broadcast cases. It never requires or contacts a
production account.

References: [Synthetic Monitoring API](https://grafana.com/docs/grafana-cloud/observe-and-act/testing/synthetic-monitoring/api-reference/),
[Secure values](https://grafana.com/docs/grafana-cloud/observe-and-act/testing/synthetic-monitoring/create-checks/manage-secrets/),
[Grafana alert provisioning](https://grafana.com/docs/grafana/latest/alerting/set-up/provision-alerting-resources/http-api-provisioning/).

SkoLab has one application metrics pipeline per service: Go and Python push
OTLP HTTP/protobuf directly to Grafana Cloud (or an OTel Collector). Each process
has a unique `service.instance.id`; `service.name` distinguishes gateway traffic
from Python execution. There is no scrape endpoint. The old hand-written gateway
metrics and `/observability` endpoint are retired; remove any existing scrape job.

## Enable on Render

1. In Grafana Cloud, open your stack's **OpenTelemetry > Configure** page. Generate
   an ingestion token with metrics/traces write permissions. Use the endpoint and
   `OTEL_EXPORTER_OTLP_HEADERS` value provided there. Keep credentials in Render's
   environment settings, never in Git or the dashboard JSON.
2. Add these variables to **both** Render services:

   ```dotenv
   OTEL_EXPORTER_OTLP_ENDPOINT=https://<your-otlp-host>/otlp
   OTEL_EXPORTER_OTLP_HEADERS=Authorization=Basic%20<base64-instance-id-and-token>
   OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf
   OTEL_TRACES_SAMPLER_ARG=1
   ```

   The generic endpoint is a base URL: the exporters append `/v1/metrics` and
   `/v1/traces`. If using the standard signal-specific endpoint variables instead,
   provide the complete URL for each signal. Headers follow the SDK's URL-encoded
   comma-separated `key=value` format. Copy Grafana's generated configuration.
   Render Blueprint already sets distinct service names and production environment.
   Endpoint and credentials are deliberately optional so an unconfigured deployment
   remains functional without sending telemetry elsewhere.
3. Deploy the code, make several normal API requests, and wait about 30 seconds.
   In Grafana **Explore**, select the Prometheus data source and query
   `skolab_http_requests_total`. Import `grafana-dashboard.json` using
   **Dashboards > New > Import**, selecting that Prometheus data source.
4. In Explore's Tempo data source, search `service.name=skolab-gateway`. An
   authenticated compile should show the gateway server span, its HTTP client
   span, and Python server/DB spans under the same trace ID. Health probes are
   intentionally excluded.
5. Load the SLO alert rules and route them to a contact point, as described in
   [SLOs and alerting](#slos-and-alerting). Importing the dashboard does not
   create alerts or notifications.

Without an endpoint, exporters and export threads are not created. Set
`OTEL_SDK_DISABLED=true` to disable SDK recording/export. Under pytest, external
export is blocked unless a test explicitly opts into a local receiver.

## Metrics and aggregation

| OTLP instrument | Grafana/Prometheus name | Meaning |
| --- | --- | --- |
| `skolab.http.requests` | `skolab_http_requests_total` | Completed requests, including 4xx/5xx |
| `skolab.http.request.duration` | `skolab_http_request_duration_seconds_bucket` / `_sum` / `_count` | Full response duration, including streaming |
| `skolab.http.active_requests` | `skolab_http_active_requests` | Active requests per process |
| `skolab.db.pool.connections` | `skolab_db_pool_connections{state}` | Pool connections: `acquired`, `idle` (+ `constructing` in Go) |
| `skolab.db.pool.max_connections` | `skolab_db_pool_max_connections` | Configured pool ceiling (Python: size + overflow, per worker) |
| `skolab.db.pool.acquire_waits` | `skolab_db_pool_acquire_waits_total` | Gateway only: acquires that waited for a free connection |
| `skolab.runtime.event_loop_lag` | `skolab_runtime_event_loop_lag_seconds` | Python only: worst asyncio scheduling delay per export |
| `skolab.runtime.goroutines` / `skolab.runtime.heap` | `skolab_runtime_goroutines` / `skolab_runtime_heap_bytes` | Gateway only: goroutines and live heap |

The pool and runtime instruments are the "saturation" golden signal. They are
read once per 30s export, never per request.

Request counts and duration use only `http.request.method`, `http.route` and
`http.response.status_code`. Routes are templates, unknown routes collapse to
`unmatched`, and unknown methods collapse to `_OTHER`. No user IDs, payloads,
SQL text, raw URLs, query strings or credentials are exported by these spans.
Custom DB spans describe the operation; exception detail remains in Sentry/logs.
W3C trace context is propagated without baggage.

Grafana Cloud normally maps `service.name` to `job` and `service.instance.id` to
`instance`. Confirm these labels in Explore if your ingestion path has custom
translation rules. The supplied dashboard filters `job` to **one service at a
time**, defaults to `skolab-gateway` in `production`, and sums rates across
instances. The environment selector keeps staging and production separate. Gateway and
Python count different service hops: never sum their request counts to estimate
end-user traffic. Use gateway counts for incoming API traffic, Python counts for
work performed there. Histogram quantiles aggregate buckets before calculating
the percentile. Do not average per-worker p95 values.

Metrics are unsampled; root traces are sampled at the configured ratio (100% in production), with children respecting the
parent decision. There is one server span per request and no second HTTP metrics
library. Python structured logging adds no extra server span. When OTel export is
configured, Python Sentry performance tracing is disabled; Sentry exception
reporting remains available. Go Sentry does not enable performance tracing.

## SLOs and alerting

Alerting is SLO-based: people are paged when users are failing faster than an
agreed error budget allows, never for causes such as CPU at 80%. Causes belong
on the dashboard. The SLOs, all over a 30-day window and measured in production
only:

| SLO | Service | Good event | Target |
| --- | --- | --- | --- |
| `api-availability` | skolab-backend-py | non-5xx response (all routes but compile) | 99.5% |
| `api-latency` | skolab-backend-py | response within 1s (all routes but compile) | 95% |
| `gateway-availability` | skolab-gateway | non-5xx (excluding compile and `/ws/*`) | 99.5% |
| `gateway-latency` | skolab-gateway | within 1s (excluding compile and `/ws/*`) | 95% |
| `compile-availability` | skolab-gateway | non-5xx compile | 99% |
| `compile-latency` | skolab-gateway | compile within 10s | 95% |

WebSocket routes are excluded because their recorded duration is the socket's
lifetime. Compile has its own budget so that one heavy feature cannot hide, or
be hidden by, the rest of the API. 4xx (including 429 quota and rate-limit
refusals) counts as good: the server behaved correctly.

Each SLO has three multi-window burn-rate alerts (Google SRE Workbook,
"Alerting on SLOs"): **page** at 14.4x over 1h/5m and 6x over 6h/30m, and a
**ticket** at 1x over 3d/6h. Every alert requires at least 50 requests in its
long window, so a couple of failures during a quiet hour cannot page anyone.
Each alert's `runbook_url` points into [slo/RUNBOOK.md](slo/RUNBOOK.md).

**Changing an SLO.** Edit the table in [`slo/generate.py`](slo/generate.py),
then run `make slo-rules`. That regenerates `slo/skolab-slo.rules.yml` and runs
`promtool check` plus the unit tests in `slo/skolab-slo.test.yml`, which prove
each alert fires on a real burn and stays quiet on healthy, low-traffic and
staging traffic. CI runs the same checks and fails if the generated file is
stale. Never edit the rules file by hand.

**Loading into Grafana Cloud.** The deploy workflow (see *Deployment* above)
runs `slo/provision.py --apply` and then `--verify` on every merge. That
reconciles everything from the same `generate.groups()` data the tests cover,
using the same `GRAFANA_URL`/`GRAFANA_TOKEN` as the external checks:

- **Recording rules** run in Mimir as data-source-managed rules (namespace
  `skolab-slo`), written through Grafana's ruler proxy. Retired groups are
  deleted.
- **Burn-rate alerts** form one Grafana-managed rule group, *SkoLab SLOs*, in
  the *SkoLab monitoring* folder. Each rule routes to `GRAFANA_CONTACT_POINT`
  (default `skolab-oncall`), as the availability alerts do, and the stack's
  root notification policy is left alone. Notifications are grouped by `slo`,
  so the 1h and 6h pages for one incident arrive together. Pages repeat hourly
  while firing; tickets repeat daily.
- **The dashboard** is imported into the same folder.

Without flags the script prints its plan. `--contact-email` creates the
contact point if it is missing (a one-time step), and `--verify` checks that
the recorded series and all 18 alert rules exist.

**Relationship to the external checks.** The probes and journey at the top of
this file answer "can a user reach SkoLab at all?", including when no request
reaches the app (DNS, TLS, Render edge, a crashed or sleeping instance). These
SLOs answer "are the requests that do arrive succeeding, and fast enough?".
Both are needed, and neither one's numbers feed into the other's. Probe failures use
[RUNBOOK.md](RUNBOOK.md); SLO burn alerts use [slo/RUNBOOK.md](slo/RUNBOOK.md).

## Logs, traces and exemplars

Both services write JSON logs carrying the active `trace_id` and `span_id`
(gateway: the `request` access log; Python: every log line), so a log line
leads to its trace and a trace to its logs. Latency histograms carry
exemplars by default in both SDKs, and the dashboard's response-time panel
shows them: click a dot to open the slow request's trace in Tempo.

Traces are head-sampled at 100% (`OTEL_TRACES_SAMPLER_ARG=1` in
`render.yaml`), so every failing request has a trace while traffic is small.
When trace volume nears the Grafana Cloud quota, lower the ratio and add tail
sampling in an OTel Collector, which keeps every error and slow trace (see
below). Sentry captures every exception either way.

## Infrastructure and growth

Use **Render > service > Metrics** for CPU/memory and platform diagnostics.
On a Pro workspace or higher, optionally add **Observability > Metrics Stream >
Grafana**. This supplies `render.*` infrastructure metrics. Leave Render HTTP
request metrics out of application request panels to avoid two traffic sources.
Database/Redis capacity is available from their hosting provider; this change
does not claim to instrument Supabase infrastructure or Redis pool metrics.

Exports run in background batches with bounded timeouts and finite trace queues.
Metrics flush every 30 seconds; shutdown flushes after request draining. Exporter
failure does not fail a request. Direct SDK export is best-effort, not durable:
a prolonged destination outage can lose telemetry. At larger scale, route the
same SDK configuration to an OTel Collector with batching, memory limits,
retry/queue monitoring and persistent queues where needed. Keep only one receiver
path for each service; do not add a scrape of the same instruments alongside OTLP.

References: [Grafana OTLP setup](https://grafana.com/docs/grafana-cloud/send-data/otlp/send-data-otlp/),
[Render metrics streams](https://render.com/docs/metrics-streams),
[OpenTelemetry Collector](https://opentelemetry.io/docs/collector/).
