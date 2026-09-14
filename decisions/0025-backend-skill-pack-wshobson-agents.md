# 0025 - Add a Scoped Backend Slice of `wshobson/agents`

**Date:** 2026-09-14
**Status:** Accepted

## Context

With the frontend redesign merged (decisions 0019-0024), the product owner
asked to move on to backend work and asked two things: is there a
world-class harness/repo for backend agent work, and is it safe to remove
the current `addyosmani/agent-skills` pack (installed 3 days earlier, per
`decisions/0017`) to install a backend-specific one instead.

The product owner's own Notion research (`Harness Repository Collection —
Web App (Frontend + Backend)`, dated 2026-09-08) has a dedicated backend
table naming **wshobson/agents** (39.5k★, 202 agents / 183 skills / 94
plugins, multi-harness) as the top pick across backend architecture, API
contracts, database/SQL performance, and async jobs, and tied for top pick
on observability with `addyosmani/agent-skills`.

**Removing the existing pack was rejected.** Two reasons, both load-bearing:

1. `decisions/0017` swapped this repo's *entire* agent-tooling layer three
   days before this request, specifically to escape a bloated, over-
   engineered custom harness. Swapping again now would repeat the exact
   churn that decision was made to stop, for no technical reason — nothing
   about installing a second pack requires removing the first.
2. `addyosmani/agent-skills` is not a frontend-only pack. The same Notion
   research lists it as a top-2 pick for API contracts and tied-primary for
   observability/reliability — both backend concerns it already covers well.
   The research notes also cite 2026 harness research (referred to there as
   "EVOHARNESSBENCH") finding that piling on tools/skills/agents causes
   *forgetting and conflicts* — their own stated fix is "scoped, routed
   capability," not wholesale replacement. Claude Code's skill loader
   already does this: skills load on demand by relevance (progressive
   disclosure per `decisions/0017`'s own rationale), so a second pack scoped
   to backend work adds capability without re-introducing the always-loaded
   cost the 2026-09-11 swap fixed.

## Decision

Added a **scoped** slice of `wshobson/agents` — not the marketplace's full
94 plugins / 183 skills / 202 agents — picked to match this repo's actual
backend stack (Go gateway in `services/backend-go`, FastAPI + SQLAlchemy +
asyncpg + Alembic Python service in `services/backend`, Postgres +
pgvector similarity engine, self-hosted embeddings per `decisions/0003`):

- **Vendored in full for reference** at
  `vendor/harness-skills/wshobson-agents/` (mirrors `decisions/0017`'s
  pattern for `addyosmani-agent-skills/`).
- **21 skills installed** into `.claude/skills/` (same flat `<name>/SKILL.md`
  convention as the existing pack): `api-design-principles`,
  `architecture-patterns` (backend-development plugin); `go-concurrency-
  patterns` (systems-programming); `async-python-patterns`,
  `python-error-handling`, `python-observability`, `python-performance-
  optimization`, `python-resilience`, `python-testing-patterns`,
  `python-type-safety` (python-development); `postgresql-table-design`
  (database-design); `distributed-tracing`, `prometheus-configuration`,
  `slo-implementation` (observability-monitoring); `embedding-strategies`,
  `similarity-search-patterns`, `vector-index-tuning`, `rag-implementation`,
  `hybrid-search-implementation`, `llm-evaluation`, `prompt-engineering-
  patterns` (llm-application-dev).
- **9 specialist review personas installed** into `.claude/agents/`
  (alongside the existing 4 — `code-reviewer`, `test-engineer`,
  `security-auditor`, `web-performance-auditor`): `backend-architect`,
  `golang-pro`, `fastapi-pro`, `database-architect`,
  `backend-security-coder`, `observability-engineer`, `performance-engineer`,
  `ai-engineer`, `vector-database-engineer`.
- **Deliberately excluded**: event-sourcing/CQRS/saga/Temporal-workflow
  skills from the `backend-development` plugin (no event-sourcing or
  Temporal usage anywhere in this codebase), and the ~85 other plugins
  covering domains this repo doesn't touch (blockchain, game dev, SEO,
  mobile-native beyond what `android/skills`-equivalent guidance already
  covers, business/HR, etc.).
- `AGENTS.md`'s intent→skill mapping extended with backend/database/LLM/
  observability routing rows pointing at the new skills and personas.

## Alternatives considered

- **affaan-m/ECC**, named in the same research as a "current high-star
  harness" for backend architecture and for deeper memory/security/
  research-first workflow. Not adopted here — the research describes it in
  vaguer terms (no concrete star count, "current high-star" only) than
  `wshobson/agents`'s explicit, repeated top billing across five backend
  rows with a stated 39.5k★. Worth a second look if a future need
  specifically calls for cross-session memory or a heavier research-first
  workflow layer this pack doesn't provide.
- **Installing the full `wshobson/agents` marketplace.** Rejected per the
  research's own "scoped, routed capability" principle and the
  EVOHARNESSBENCH forgetting/conflict finding it cites — most of the 94
  plugins (blockchain, game dev, SEO, payment processing, HR/legal, etc.)
  have no bearing on this codebase.

## Consequences

`.claude/skills/` and `.claude/agents/` now serve both frontend and backend
work from two coexisting, independently-sourced packs. No collisions were
found between the new 21 skills / 9 agents and the existing 25 skills / 4
agents (checked by name before installing). `vendor/harness-skills/` now
holds two vendored packs (~11MB added for `wshobson-agents/`, comparable to
the existing `addyosmani-agent-skills/` vendor copy). Future skill-pack
additions should follow the same discipline: check the product owner's
Notion research first, prefer addition over replacement unless there's a
concrete technical reason to remove something, and scope the import to
what the actual stack uses rather than importing a marketplace wholesale.
