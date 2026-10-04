# Production hardening — 2026-10-04

## Database mitigation applied

The deployed Render API's DATABASE_URL was compared privately with the audited
database and matched. All 29 application tables are owned by `postgres`.
The transaction enabled RLS, revoked every table privilege from PUBLIC, anon
and authenticated, and removed public-role table default grants for the current
creator. Verification found zero remaining public-role privilege pairs,
confirmed backend owner permission checks, and checked a newly created table
had no public grants. The temporary probe was dropped. User data was unchanged.
Both live gateway liveness and readiness returned 200 afterward.

The mitigation deliberately did not advance Alembic before deploying its code.
Migration `c9d0e1f2a3b4` records/reapplies these idempotent protections on release.
Migration `d0e1f2a3b4c5` adds sign-in time to socket tickets. Old tickets have
zero sign-in time and fail closed on the hardened gateway; clients obtain a
fresh ticket. Backend owners bypass RLS; no client Data API policies are intended.
Security migration downgrade deliberately does not reopen public access.

Future migrations creating tables must enable RLS and preserve deny-by-default
grants. Default privileges are creator-specific: provisioning another migration
role requires applying the same restrictions for that role. Owner credentials
remain a residual risk; a dedicated runtime role and separate migration
credentials should follow as a controlled credential rollout.

## Code protections

- Deployed gateways require initialized Firebase and PostgreSQL before serving
  traffic. Database startup retries three times; failure exits rather than
  capturing a permanently unavailable pool. Readiness checks Firebase client
  initialization. This does not prove remote Firebase availability.
- HTTP body reads have a 30-second budget; profile database writes have a
  three-second operation budget. Streaming socket writes retain their own limits.
- Encryption requires a valid Fernet key. Invalid keys, legacy plaintext,
  corrupt ciphertext and wrong-key reads fail rather than silently succeeding.
  The deployed key was checked privately and has a valid format. Existing
  encrypted data still needs recovery/rotation verification; format validation
  alone is not a key recovery drill. Do not rotate a key without migrating data.
- Per replica, WebSockets admit at most 64 connections globally, four per user,
  and 16 per workspace, counting in-flight handshakes. Each socket allows
  30 messages/second with a burst of 60, and 256 KiB/second with one maximum-sized
  frame of burst capacity. Frames remain capped at 512,000 bytes.
- Queued outbound data is capped at 1 MiB/client and 16 MiB/replica, with eight
  frames/client. Slow clients are disconnected. Byte budgets are released on
  delivery and disconnection. These limits are per process, not Redis-wide.
- Socket tickets preserve the original Firebase sign-in time. Account/session
  status is checked before upgrade and alongside membership every 30 seconds.
  The existing status cache allows up to 60 seconds of normal staleness, or
  15 minutes during a Firebase outage. A 15-minute socket lifetime requires
  reconnection and a fresh authenticated ticket. Clients must reconnect after
  policy closure; there is no durable replay guarantee in this transport.
- Expired Go quota rows are removed in bounded batches each minute. Local
  fallback counters are swept and capped at 10,000 entries, failing closed at
  capacity. Outage fallback remains process-local and resets on restart.
- Fresh database bootstrap now executes security migrations instead of stamping
  them away. CI installs TeX before Python compile tests, exercises permission
  denial on disposable PostgreSQL, and verifies release startup fails without
  authentication credentials.

## Remaining infrastructure work

The live service remains on Render's free plan with one API instance and a
co-located compiler. Code improvements do not establish high availability or
strong compiler isolation. The paid candidate is `deploy/render-ha.yaml`;
applying it needs an approved budget and provider capacity checks. No paid
resources are authorized or provisioned by this change.

An isolated worker must have no Firebase/database credentials, must restrict
unnecessary egress and filesystem access, and must demonstrate that worker
failure cannot take down the API. Separate long-lived workers do not by
themselves provide per-job container isolation.

Before a production-grade claim, verify mixed compile/API/collaboration load on
the actual capacity, deployment/failover recovery, durable document convergence,
account revocation, alert firing/recovery, independent backup/key access and
complete service restoration. The audited successful CI load test is not a
Render capacity benchmark. Retain the distinction between targets and measured
availability/RPO/RTO.
