# Backend metrics and traces

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
