# SkoLab repository organization

This document is the source of truth for how SkoLab is organized as a
multi-client research product. It applies the principles from Bulletproof
React, the official Turborepo app/package split, and GitHub's collaboration
and security checklist without copying any one repository as a template.

## Current product boundaries

```text
apps/web                 Next.js web client
apps/android-app         Jetpack Compose Android client
services/backend         FastAPI domain and enrichment API
services/backend-go      Go gateway, realtime and low-latency services
shared/                  Cross-client design tokens and generated assets
api-contracts/           Generated HTTP contract snapshots
infrastructure/          Runtime, monitoring and deployment configuration
tests/                   Cross-service and load tests
decisions/               Architecture decision records
```

The Android app is a first-class deployable application. It is not a
JavaScript workspace package: Gradle owns its dependency graph and release
artifacts. It belongs in this repository because it shares the product,
contracts, design tokens, environments and release decisions with the web
client.

## Dependency direction

```text
web ───────────────┐
android ───────────┼──> API contracts / backend APIs
                   └──> shared design tokens

backend-go ────────> backend contracts and infrastructure
backend ───────────> infrastructure / data providers
workers ───────────> contracts / backend domain services
```

Rules:

1. Clients never access another client's implementation.
2. Clients do not access databases directly except for explicitly documented
   Firebase product surfaces that are intentionally shared by web and Android.
3. HTTP and event payloads are defined once and tested from both sides.
4. Shared packages contain stable contracts, tokens or infrastructure helpers;
   they must not become a dumping ground for feature code.
5. A service may depend on an internal module in the same service, but not on a
   private module inside another service.

## Target evolution

Do not perform a bulk rename. Introduce these boundaries incrementally:

```text
packages/contracts       generated/client-safe API and event schemas
packages/telemetry       common correlation and event conventions
workers/                 ingestion, recommendation and notification jobs
docs/architecture/       system and boundary documentation
docs/operations/         deployment, rollback and incident runbooks
```

The existing `api-contracts/` directory remains authoritative until a
contract package is introduced and both the Python and Go consumers are
verified against it.

## Feature organization

Web features should own their screens, queries, mutations, loading states,
error states and tests under `apps/web/src/features/<feature>`. Shared visual
primitives remain in the design-system or shared UI layer. Android follows the
same product boundaries with Compose screens, ViewModels, repositories and
network models grouped by feature rather than by a single global bucket.

Backend domains should own validation, service logic, persistence adapters,
and route handlers. Provider integrations (OpenAlex, AI vendors, Firebase,
email and payments) remain behind infrastructure interfaces.

## Quality gates

Every pull request that changes production code must be able to run the
affected checks:

- web: build, typecheck, lint, unit tests, route/a11y smoke and visual checks;
- Android: Gradle compile, unit tests, lint and release-like packaging;
- Python: Ruff, type checks, unit/API tests and dependency audit;
- Go: `go vet`, race-enabled tests and build;
- contracts: generated snapshot or compatibility check;
- security: secrets, dependency, workflow and container scanning.

CI must use path-scoped jobs so unrelated apps do not rebuild, while a
cross-cutting change (contracts, tokens, config or infrastructure) fans out to
all affected consumers.

## Release boundaries

The repository is monorepo-shaped, but releases are independently observable:

- web deployment has a web health check and browser smoke evidence;
- Python and Go deployments have separate health checks and rollback paths;
- Android has dev/prod flavors and signed release artifacts;
- shared tokens/contracts changes publish or validate all consumers before merge.

Splitting repositories is deferred until measurable evidence shows a need for
access isolation, independent ownership, unacceptable CI latency, or unrelated
release lifecycles.
