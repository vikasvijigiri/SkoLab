# AGENTS.md

Repository-level contract for any coding agent working in SkoLab.

## Skill pack

This repo installs `addyosmani/agent-skills` under `.claude/`: lifecycle
skills (define → plan → build → verify → review → ship) in
`.claude/skills/`, 4 review personas in `.claude/agents/`, and 7 supporting
checklists in `.claude/references/`. It replaced a larger, repo-specific
capability layer on 2026-09-11 — see `decisions/0017` — because that
layer's own portability machinery (harness adapters, workflow state,
cost-tracking hooks) had grown larger than the product it was meant to
support.

A scoped backend slice of `wshobson/agents` was added alongside it on
2026-09-14 (`decisions/0025`) — 21 backend/Go/Python/database/observability/
LLM skills and 9 specialist review personas, picked to match this repo's
actual stack (Go gateway, FastAPI/SQLAlchemy Python service, Postgres,
pgvector similarity, self-hosted embeddings), not the marketplace's full
183 skills / 202 agents. Both packs coexist deliberately — skills load on
demand by relevance (progressive disclosure), so a second scoped pack adds
capability without the always-loaded cost the 2026-09-11 swap was fixing.

## Intent → skill mapping

Map the request to the lifecycle phase and let the matching skill run before
writing code:

- Feature / new functionality → `spec-driven-development`, then
  `planning-and-task-breakdown`, `incremental-implementation`,
  `test-driven-development`
- Bug / failure / unexpected behavior → `debugging-and-error-recovery`
- API or interface design → `api-and-interface-design`, `api-design-principles`
- UI work → `frontend-ui-engineering` (grounded in `DESIGN.md` for
  `apps/web`)
- Backend architecture (Go gateway or Python service) → `architecture-patterns`,
  plus `go-concurrency-patterns` for `services/backend-go` or the relevant
  `python-*` skill for `services/backend`
- Database work (Postgres schema, migrations, query performance) →
  `postgresql-table-design`
- LLM/embedding/similarity work (`services/backend`'s AI paths only — see
  `decisions/0010`'s Python/Go boundary) → `embedding-strategies`,
  `similarity-search-patterns`, `vector-index-tuning`, `rag-implementation`,
  `hybrid-search-implementation`, `prompt-engineering-patterns`,
  `llm-evaluation`
- Observability / reliability work → `observability-and-instrumentation`,
  plus `distributed-tracing`, `prometheus-configuration`,
  `slo-implementation`, or `python-observability` for the specific surface
- Code review before merge → `code-review-and-quality`; for a backend-heavy
  change, pair with the relevant specialist persona in `.claude/agents/`
  (`backend-architect`, `golang-pro`, `fastapi-pro`, `database-architect`,
  `backend-security-coder`, `observability-engineer`, `performance-engineer`,
  `ai-engineer`, `vector-database-engineer`)
- Refactoring / simplification → `code-simplification`
- Security-sensitive change (auth, input handling, secrets, dependencies)
  → `security-and-hardening`
- Deploy / release → `ci-cd-and-automation`, then `shipping-and-launch`
- Architecture decision or public-API change → `documentation-and-adrs`

Skills also activate automatically from their own descriptions — invoke one
directly by name when the mapping above is ambiguous.

## Read first

1. This file
2. `HANDOFF.md` (current repo state)
3. `README.md`
4. `DESIGN.md` for any `apps/web` UI work
5. The most relevant package-level guide (e.g. `apps/web/AGENTS.md`)

## SDLC contract

Do not skip verification for a completion claim. Do not push, merge,
publish, deploy, or spend money without explicit approval. Keep changes
within scope, preserve user changes, and report failures as failures.

## Boundaries

- Never weaken a check (silence a lint rule, skip a test, lower a
  threshold) to make a diff look green.
- Never commit a secret. `docs/ops/security-resources.md` has the vetted
  external checklists; `.claude/references/security-checklist.md` has the
  in-pack one.
- Personas in `.claude/agents/` do not invoke other personas — the user or
  a skill is the orchestrator. The one endorsed multi-persona pattern is
  parallel fan-out with a merge step (see
  `.claude/references/orchestration-patterns.md`).
