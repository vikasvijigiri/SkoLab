# 0017. Replace the repo-specific capability layer with addyosmani/agent-skills

**Date:** 2026-09-11
**Status:** Accepted

## Context

The repo carried a large, repo-specific agent capability layer under
`.claude/` plus `harnesses.json`, `.claude-plugin/`, `guide/`, `templates/`,
and `tools/` (~1.7 MB, 16 custom skills, a harness-portability contract in
`AGENTS.md` meant to project the layer onto Codex and generic agents, cost-
tracking hooks, and its own validation test suite). The user asked to remove
it entirely and install a vetted, external, production-grade skill pack
instead — smaller, lower per-session token cost, and not something this repo
has to author and maintain itself.

## Decision

Removed the entire custom layer and installed `addyosmani/agent-skills`
(already vendored for reference at
`vendor/harness-skills/addyosmani-agent-skills/`) in its place:

- `.claude/skills/` — 25 lifecycle skills (define → plan → build → verify →
  review → ship)
- `.claude/agents/` — 4 review personas (code-reviewer, test-engineer,
  security-auditor, web-performance-auditor)
- `.claude/references/` — 7 supporting checklists

`.claude/settings.json` was cleared to an empty hook set (the old layer's
hooks no longer exist). `AGENTS.md` and `CLAUDE.md` were rewritten to point
at the new pack instead of the deleted `.claude/workflow.md` contract.

**Scope: tooling only.** Product knowledge embedded in the old layer was
relocated, not deleted:

- 5 design-contract references + `design-mcp.md` → `docs/design/`
- 11 ops/resource docs (Supabase, Cloudflare, Sentry, Expo free-tier limits;
  curated backend/frontend/testing/security/observability reference lists;
  docs-structure and markdown-style conventions) → `docs/ops/`

`DESIGN.md` and `DEPLOY.md` were updated to point at the new `docs/design/`
and `docs/ops/` locations. `TASK.md`, `HANDOFF.md`, `LOG.md`, and
`decisions/` were left untouched as project history — they are not part of
the tooling being replaced.

`.github/workflows/checks.yml` was rewritten to call this repo's own lint,
typecheck, test and build commands directly (web via `npm`, backend via
`ruff`/`pytest`, Go via `go vet`/`go test`), since the old workflow called
into `.claude/hooks/_projectchecks.py`, which no longer exists.

## Consequences

- Session cost per turn should drop: the new pack's skills are read on
  demand (progressive disclosure) rather than the old layer's always-loaded
  policies, workflows, and cost-tracking hooks.
- Lost: repo-specific automation (halt guard, branch guard, attribution
  guard, spend guard, auto-commit on Stop, task-log Stop-hook reminder,
  parallel-task-group scheduling for subagents). None of it is replaced
  here — if any of it is needed again, it is a separate, deliberate
  decision, not a silent gap.
- `tools/` (the old layer's own test suites and validators) is gone; there
  is no more repo-root Python test suite validating agent-layer config.
  `scripts/` (real product scripts: build, database, ops, security) is
  unaffected.
- CI (`checks.yml`) now names its checks explicitly instead of resolving
  them from `.claude/project-checks.json`; keeping CI and local checks in
  sync is back to being a discipline, not a structural guarantee.
