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

Append these credentials to the ignored root `.env` or inject them as environment
variables. Never commit secrets or reusable Firebase credentials:

```dotenv
GRAFANA_URL=https://your-stack.grafana.net
GRAFANA_TOKEN=service-account-token
GRAFANA_SM_TOKEN=synthetic-monitoring-access-token
GRAFANA_CONTACT_POINT=existing-contact-point-name
SKOLAB_FIREBASE_API_KEY=firebase-web-api-key
SKOLAB_SYNTHETIC_EMAIL=dedicated-monitoring-account-email
SKOLAB_SYNTHETIC_PASSWORD=dedicated-monitoring-account-password
SKOLAB_SYNTHETIC_WORKSPACE_ID=dedicated-workspace-id
```

Create the Grafana service account under Administration > Users and access with
Editor permissions for dashboards and alert provisioning. Generate the Synthetic
Monitoring access token in Testing & synthetics > Synthetics > Config. The
provisioner discovers the regional API server and metrics datasource from that
plugin's settings; an OTLP ingestion token is not a management token.

Create a dedicated Firebase email/password account, enable that sign-in provider,
and sign into SkoLab to create its isolated monitoring workspace. Existing
application authorization remains enforced. The journey account consumes normal
quota: 48 daily runs at eight units across Go/Python = 384 units, below the default
600-unit daily limit. If you use lower limits, lengthen its interval; don't bypass
quotas for monitoring. Repeated manual tests also consume quota.

For `--journey`, the Grafana service account additionally needs secure-value
read/create/write permissions. Secrets are sent to Grafana's secret manager with
only `synthetic-monitoring` as decrypter. API responses and secrets are never
printed or stored in the repository. Alternatively create the four secure values
in Grafana's Synthetic Monitoring Secrets UI with the names used in `journey.js`.
Grant secret management permissions through Grafana's documented fixed roles.
When using values already configured in the UI, add `--existing-secrets` to the
apply command. Confirm a successful manual journey before enabling its schedule.

```sh
# Preview public check definitions (no network or changes).
python services/observability/provision.py
# Verify the real journey once; k6 must be installed.
python services/observability/run_journey.py
# Apply availability checks, alerts, and dashboard.
python services/observability/provision.py --apply
# Add the journey and its alert once its isolated fixtures work.
python services/observability/provision.py --apply --journey
# Exercise alert evaluation, notification, recovery and cleanup.
python services/observability/provision.py --test-alert
```

If the preferred probes are unavailable, supply two online public probe names
using `--probes Mumbai Oregon`. Multiple Prometheus datasources are handled by
discovering the one configured for Synthetic Monitoring; override with
`GRAFANA_PROMETHEUS_UID` only if your setup requires it.

See [RUNBOOK.md](RUNBOOK.md) for incident response, target definitions, notification
delivery verification, and provider integration limits. The observability CI
workflow runs the actual k6 script against local HTTP/WebSocket fixtures,
including authentication failure, invalid PDF and dropped broadcast cases. CI
never requires or contacts a production account.

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
   OTEL_TRACES_SAMPLER_ARG=0.1
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
   intentionally excluded. At 10% sampling, several requests may be needed;
   temporarily use `OTEL_TRACES_SAMPLER_ARG=1` on both services for verification.
5. Create Grafana alerts using the examples below and configure a contact point.
   Importing a dashboard does not automatically create alerts or notifications.

Without an endpoint, exporters and export threads are not created. Set
`OTEL_SDK_DISABLED=true` to disable SDK recording/export. Under pytest, external
export is blocked unless a test explicitly opts into a local receiver.

## Metrics and aggregation

| OTLP instrument | Grafana/Prometheus name | Meaning |
| --- | --- | --- |
| `skolab.http.requests` | `skolab_http_requests_total` | Completed requests, including 4xx/5xx |
| `skolab.http.request.duration` | `skolab_http_request_duration_seconds_bucket` / `_sum` / `_count` | Full response duration, including streaming |
| `skolab.http.active_requests` | `skolab_http_active_requests` | Active requests per process |

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

Metrics are unsampled; root traces default to 10%, with children respecting the
parent decision. There is one server span per request and no second HTTP metrics
library. Python structured logging adds no extra server span. When OTel export is
configured, Python Sentry performance tracing is disabled; Sentry exception
reporting remains available. Go Sentry does not enable performance tracing.

## Starter alerts

Treat these as initial thresholds; tune to observed traffic and your latency SLO.

- **Gateway errors:** 5xx rate / total request rate > 1% for 5 minutes, gated on
  at least 1 request/second to avoid noisy ratios at very low traffic.
- **Gateway latency:** p95 > 2 seconds for 10 minutes, excluding
  `/api/v1/colab/compile`, which intentionally runs a longer task.
- **Compile failures:** show 5xx rates separately for `/api/v1/colab/compile` and
  set an alert once you establish expected volume. Use its duration panels for
  timeouts/slow compilations; those are included in request error/latency metrics.
- **Availability:** use external uptime checks. Missing application telemetry is
  not itself proof of an outage, particularly on Render free services that sleep.

For example, use `sum(rate(skolab_http_requests_total{job="skolab-gateway",
http_response_status_code=~"5.."}[5m])) / sum(rate(skolab_http_requests_total{
job="skolab-gateway"}[5m]))` for error ratio.

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
