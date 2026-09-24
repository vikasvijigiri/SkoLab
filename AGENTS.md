# AGENTS.md

Repository-level contract for any coding agent working in SkoLab.

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
  external checklists.
