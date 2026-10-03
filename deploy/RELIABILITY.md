# Reliability rollout and recovery

The current `render.yaml` stays on one free instance. The paid candidate is
`deploy/render-ha.yaml`: two API replicas, two private compile-worker replicas,
and a dedicated Redis/Render Key Value service in Singapore. This provides
application-instance redundancy, not multi-region disaster recovery or proof
that the managed database and Redis have failover.

## Before activating the paid profile

1. Review the current Render prices for four `0.5c-512mb` instances and one
   `256mb` Key Value instance: https://render.com/pricing. Include existing
   database, workspace, bandwidth, and telemetry costs. At the 2026-10-03 list
   prices, the four $7 instances plus $10 Key Value total about $38/month,
   excluding those other charges and temporary rollout overlap. Paid activation is a
   separate approval step; this file has not been applied.
2. Verify the project's usable database/pooler connection capacity after other
   consumers. Set `DB_CONNECTION_BUDGET` to that verified ceiling. The profile
   reserves 10 connections and allows 4 overlapping API replicas during rollout:
   `4 * (5 Go + 1 Python worker * (3 pooled + 2 overflow)) + 10 = 50`.
   Startup and pre-deploy checks refuse a lower budget. If capacity is below 50,
   reduce pools or rollout overlap rather than guessing.
3. Retain the API's existing environment values and Firebase secret-file mount.
   Set the compile workers' `INTERNAL_API_TOKEN` to the same value as the API.
   Workers must receive no database, Firebase, or encryption credentials.
4. Provision the private workers and shared Redis first. Check worker health and
   an authenticated compile before activating the API's strict settings.
5. Set GitHub Actions variable `SKOLAB_SANDBOX_SERVICE=skolab-colab-sandbox`.
   The release script then deploys the worker's compatible version before the
   API. Keep automatic Render deployments disabled and ship only tested commits.
6. After approval, switch the Blueprint path to this paid profile and sync it.
   Render uses `/readyz` to admit traffic; it covers the database, Python, shared
   limits, and broadcast Redis. `/gateway-health` remains process-only liveness.
7. Confirm every replica is ready, the authenticated compile journey succeeds,
   and Grafana's availability and collaboration checks receive data.

Reference for scaling, private service addresses, and Key Value configuration:
https://render.com/docs/blueprint-spec. Readiness routing and rollout behavior:
https://render.com/docs/health-checks.

## Failure behavior and drills

- `SHARED_STATE_REQUIRED=true` refuses startup without reachable Redis. During
  Redis outages, HTTP limits and compile slots fail closed rather than creating
  separate local buckets/locks. Database quota accounting also refuses local
  fallback in this mode. HTTP calls report temporary unavailability;
  liveness probes remain reachable. Readiness reports unhealthy shared state.
- Redis broadcasts preserve workspace scoping across replicas. Redis Pub/Sub
  does not persist edits or replay messages missed while disconnected. A client
  must acquire a fresh ticket and reconnect with jittered backoff after close
  code 1012 (restart) or 1013 (temporarily unavailable), then fetch/resynchronize
  its document state before allowing edits. The broadcast channel is not a
  durable document store; reconnect/resynchronization needs the client's actual
  document persistence contract. This repository has no frontend to
  implement that client behavior; backend tests exercise reconnection.
- On SIGTERM, readiness becomes draining, sockets receive a restart close frame,
  and HTTP requests drain within the shutdown deadline. Application migrations
  run once as a pre-deploy command, not concurrently in every API replica.
- Use expand/contract migrations: add compatible columns first, deploy all code,
  backfill, and only remove old columns in a later release. Rolling deployments
  temporarily run both versions. Do not assume a code rollback reverses schema.
- Go integration tests exercise two Redis-backed hubs, stop one, reconnect on
  the survivor, and verify continued delivery. Shared-limit and lock tests
  exercise combined budgets and Redis failure. They do not prove Render's live
  load balancing; an approved staging rollout must also exercise actual traffic
  while one replica is restarted, then simulate worker and Redis outages.
- A separate worker removes compile resource usage from API replicas. Each job
  gets a new temporary directory and bounded process, not a fresh container.
  Cloud Run concurrency 1 also does not guarantee a fresh container per request.
  For hostile multi-tenant workloads, use an actual ephemeral job/container
  runtime with enforced network and filesystem restrictions.

## Database backups and recovery

`services/qa/restore-drill.sh` dumps CI staging to a temporary archive, restores
it into a fresh isolated Postgres container, and compares application table
counts. It accepts only the CI loopback database. Its temporary data is removed
on exit. It validates the restore mechanism; it does not verify production
backups, encryption-key recovery, or the provider's retention policy.

For production, verify the Supabase project's backup/PITR entitlement and keep
the database encryption and email-index keys in a recoverable secret store.
Define business-approved RPO (acceptable lost data) and RTO (acceptable outage)
before choosing retention and PITR. Restore a real backup into a separate
project, give it isolated Firebase and monitoring configuration, verify encrypted
user data and workspace relationships, and measure recovery time. Never test
restore over the production database. Keep encryption keys versioned and
recoverable for the full lifetime of backups encrypted under them.

## Operational acceptance

Before rollout, require green CI, both image scans, a successful restore drill,
and an authenticated compile/collaboration journey. After rollout, inspect
5xx rate, p95 latency, readiness, database pool usage, and journey failures.
Check alert delivery with a controlled staging fault. Existing Grafana synthetic
and SLO definitions live in `services/observability`; deploying their definitions
does not by itself prove someone receives the alerts.
