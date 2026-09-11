# 0004 — Keep a monorepo with independently deployable clients

**Status:** Accepted

## Decision

SkoLab will remain a monorepo containing the web client, Android client,
backend services, shared design tokens, API contract snapshots, infrastructure,
and cross-service tests. Each application or service keeps its own build,
runtime health check, release artifact and deployment workflow.

## Context

SkoLab has one product and currently changes web, Android, backend, contracts
and design tokens together. The Android app is a Gradle/Kotlin application,
not an npm package, but it still shares product contracts and design language.

The monorepo trade-off is conditional: it improves atomic cross-client changes,
discoverability and shared standards, while increasing the need for dependency
boundaries, path-scoped CI and access controls. We do not currently have the
team topology, repository size, access isolation or release independence that
would justify splitting the product into multiple repositories.

## Consequences

Positive:

- A UI/API/token change can be reviewed and tested atomically.
- Web and Android contract drift is visible in one pull request.
- Governance, security, documentation and incident conventions are shared.
- Services can still deploy independently.

Required controls:

- path-scoped CI and affected-project testing;
- CODEOWNERS and protected workflows;
- versioned API contracts and compatibility checks;
- explicit service and client boundaries;
- independent deployment and rollback evidence.

## Revisit criteria

Reconsider a split only when at least one measurable trigger persists:

- strict access or regulatory isolation is required;
- independent teams need autonomous repository administration;
- clone, indexing or CI latency becomes a material delivery constraint;
- release cadence and ownership are unrelated enough to justify separate
  governance;
- shared-code coupling is lower than the coordination cost of one repository.
