# Backend availability and journey failures

## First response

1. Open the **SkoLab availability and complete journey** dashboard. Compare
   Mumbai and Oregon. A failure at one location may be regional; failures at
   both indicate a wider reachability or application problem.
2. Check Render's current deployment and recent startup logs for both services.
   A green CI run does not mean the new image deployed successfully.
3. Go availability checks process reachability. Python readiness checks database
   and cache connectivity. Compare `/livez` with `/readyz`: alive but not ready
   points to dependency failure rather than a stopped Python process.
4. For a journey failure, open the Synthetic Monitoring check's latest run and
   find the failing assertion. Never paste credentials or ticket URLs into an
   issue. Use fixed operation names and trace/request IDs to investigate.

## Journey assertions

| Failure | Investigation |
|---|---|
| Fresh Firebase login | Dedicated account enabled, password/current API key, Firebase availability |
| Identity sync | Go database connectivity, users table/schema, Firebase UID ownership |
| PDF compilation | Go/Python reachability, quota, pdflatex installation, compile logs; valid test source must return `compiled` and a complete PDF |
| Ticket issuance | Monitoring workspace ownership/membership, ticket table/schema |
| Peer delivery | WebSocket authorization/upgrade, gateway instances, Redis pub/sub connectivity |

The synthetic account and workspace are dedicated fixtures. Never delete real
accounts or modify user documents as a health test. This journey verifies backend
operations; a browser UI journey requires the frontend repository and its URL.

## Alert behavior and testing

Availability alerts fire after neither probe has succeeded for three minutes,
followed by a three-minute pending period. Missing probe data for five minutes
also triggers the condition. One failed probe alone is visible on the dashboard
without a critical outage notification. Journey alerts inspect the latest run
within 35 minutes and remain pending for two minutes. Missing journey telemetry
is an alerting condition, not a successful run.

`python services/observability/provision.py --test-alert` creates a temporary
**TEST: SkoLab monitoring notification** rule, observes firing, switches it to
healthy, observes recovery, and deletes it. This does not interrupt production.
The recipient must confirm notification receipt: seeing `firing` proves rule
evaluation, not delivery into an inbox. If cleanup fails, delete only the rule
with UID `skolab-notification-test` in Grafana and rerun the test.
The **Alert delivery drill** workflow runs this on the 1st of every month;
if its two notifications do not arrive, treat alerting as down.

## Reliability targets and infrastructure

The initial availability target is 99.9% of scheduled external checks successful
over 30 days. The displayed value is based on recorded samples; missing samples
are covered by a separate alert and must not be silently counted as successful.
This synthetic target is distinct from the percentage of actual user requests
that succeed. Set a latency target after collecting representative real traffic;
do not use the tiny monitoring document as normal compile-performance evidence.

Keep application request metrics in the existing OTLP pipeline. Synthetic probe
metrics describe external checks, so they must not be added to user request
counts. Do not add another scrape/exporter for the same application instruments.

Render CPU/memory and Supabase/Redis capacity still need provider integrations.
Render Metrics Streams requires a Pro workspace or higher; do not purchase a
plan automatically. Use provider dashboards until eligible integrations and
credentials are available. Logs remain in Render and errors in Sentry; adding
central log shipping needs its own single pipeline. A dashboard alone does not
configure those integrations.
