# Repository organization

SkoLab is a deliberately small monorepo: deployable services and the Android
client live together because they share contracts, release decisions, and
operational configuration.

## Layout

```text
apps/android-app/              Jetpack Compose Android client
services/backend/              FastAPI domain, AI, and enrichment API
services/backend-go/           Go gateway, realtime, and low-latency API edge
contracts/openapi/             generated HTTP contract snapshots
contracts/schemas/             client-safe shared schema material
packages/design-system/        Android-consumed design tokens and generators
scripts/                       operational, database, security, and build tools
tests/                         cross-service Firestore and load tests
docs/                          architecture, operations, runbooks, and records
decisions/                     architecture decision records
render.yaml                    production Blueprint for the two backend services
```

## Dependency direction

```text
Android client ───────────────> API contracts / backend APIs
Android client ───────────────> shared design tokens

Go gateway ───────────────────> FastAPI internal APIs / infrastructure
FastAPI service ──────────────> data providers / infrastructure
```

Rules:

1. Deployable units own their implementation and dependency graph.
2. Clients never access service databases directly, except documented Firebase
   surfaces with tested security rules.
3. HTTP and event payloads are captured in contract material and checked in CI.
4. Shared directories hold stable contracts, tokens, or tooling—not feature
   implementations.
5. Dated plans, recon notes, and decision records are historical evidence;
   package READMEs and root configuration describe the current system.

## Quality and release boundaries

- FastAPI: Ruff, unit/API tests, contract compatibility, and Postgres-backed CI.
- Go gateway: `go vet` and race-enabled tests in CI.
- Android: Gradle build and Android-specific verification.
- Firebase: rules tests through `npm run test:rules`.
- Render: `render.yaml` is the sole production Blueprint; it defines only the
  Go gateway and FastAPI service.

Each deployable unit releases independently. A contract, token, or
infrastructure change must validate every affected consumer before merge.
