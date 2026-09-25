# SkoLab

SkoLab is a backend-and-Android research platform. The repository contains two
independently deployable backend services, a Jetpack Compose Android client,
shared contracts, Firebase rules, and operational tooling.

## Repository map

| Path | Purpose |
| --- | --- |
| `services/backend` | FastAPI domain, AI, enrichment, and data API |
| `services/backend-go` | Go gateway, realtime, and API-edge functionality |
| `apps/android-app` | Android client (Gradle-owned) |
| `contracts` | OpenAPI snapshots and language-neutral API schemas |
| `packages/design-system` | Android design tokens and generator |
| `scripts`, `tests` | Operations, security, contracts, Firebase, and load checks |
| `docs`, `decisions` | Current runbooks plus architecture/history records |

The retired web client and its production service are intentionally absent. A
future web client should be introduced as a new, separately designed package.

## Prerequisites

- Python 3.12
- Go version declared in `services/backend-go/go.mod`
- Node.js 22.22+ for Firebase rules and shared-token tooling
- Android Studio / JDK 21 only when working on `apps/android-app`

## Local development

```powershell
# Terminal 1 — FastAPI
make dev-backend

# Terminal 2 — Go gateway
make dev-go
```

Copy `.env.example` to a local `.env` only when needed. Keep real credentials
out of Git, Render Blueprint files, and chat.

## Verification

```powershell
make lint-python
make test-python
make verify-contracts
make test-go
```

Use `npm run test:rules` for Firestore-rule changes and `make build-android`
for Android changes. Production configuration and release verification are in
[`DEPLOY.md`](DEPLOY.md).

## Documentation

- [`docs/architecture/repository-organization.md`](docs/architecture/repository-organization.md) — boundaries and dependency direction
- [`docs/ops`](docs/ops) — operations and security guidance
- [`docs/runbooks`](docs/runbooks) — incident and recovery procedures
- [`decisions`](decisions) — architecture decision records
