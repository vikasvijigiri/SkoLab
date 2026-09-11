# AGENTS.md

Repository-level contract for any coding agent working in SkoLab.

## Skill pack

This repo installs `addyosmani/agent-skills` under `.claude/`: 25 skills in
`.claude/skills/`, 4 review personas in `.claude/agents/`, and 7 supporting
checklists in `.claude/references/`. It replaced a larger, repo-specific
capability layer on 2026-09-11 — see `decisions/` for the record — because
that layer's own portability machinery (harness adapters, workflow state,
cost-tracking hooks) had grown larger than the product it was meant to
support.

## Intent → skill mapping

Map the request to the lifecycle phase and let the matching skill run before
writing code:

- Feature / new functionality → `spec-driven-development`, then
  `planning-and-task-breakdown`, `incremental-implementation`,
  `test-driven-development`
- Bug / failure / unexpected behavior → `debugging-and-error-recovery`
- API or interface design → `api-and-interface-design`
- UI work → `frontend-ui-engineering` (grounded in `DESIGN.md` for
  `apps/web`)
- Code review before merge → `code-review-and-quality`
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
