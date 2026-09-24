# 0026. Remove the .claude skill-pack harness

**Date:** 2026-09-24
**Status:** Accepted

## Context

`decisions/0017` and `decisions/0025` installed two skill packs under
`.claude/` (`addyosmani/agent-skills` lifecycle skills + review personas,
then a scoped backend slice of `wshobson/agents`). `.claude/` was always
gitignored — it never entered version control, so no other clone or CI run
depended on it. The user asked to remove the harness layer entirely and
strip the repo's own stale references to it.

## Decision

Deleted `.claude/` (skills, agents, references, settings) along with the
untracked, gitignored temp artifacts sitting next to it at the repo root
(`.playwright-mcp/`, `penpot-mcp.stderr.log`, `penpot-mcp.stdout.log`) —
none of it was tracked, so removal has no git-history impact.

`AGENTS.md`'s "Skill pack" and "Intent → skill mapping" sections, and the
`.claude/`-referencing bullets under "Boundaries," were removed since they
described a layer that no longer exists. `decisions/0017` and
`decisions/0025` were left untouched as project history, per this repo's
own ADR convention (numbered entries are never rewritten or renumbered).

A mechanical sweep of the rest of the repo (tracked build artifacts,
backup/OS junk files, orphaned vendor directories) found nothing else to
remove — `git ls-files` turned up no committed `.bak`/`.orig`/`.log`/
`__pycache__`/`dist`/`build`/`.next`/`coverage` paths. A deeper dead-code
audit of application source (unreferenced components/modules across
`apps/`, `services/`, `packages/`, `shared/`) was explicitly scoped out of
this pass.

## Alternatives considered

- **Keep the sections as a historical note** instead of deleting them —
  rejected; the mapping's skill names would stop resolving to anything
  real, which is exactly the kind of stale reference this cleanup was
  meant to remove.
- **Also run a deep dead-code audit** across app/service source in the same
  pass — deferred; it's a materially larger, judgment-heavy effort with
  real false-positive risk, better scoped as its own deliberate task.

## Consequences

- No skill packs are currently installed under `.claude/`. Any agent
  working in this repo now relies on `AGENTS.md`'s remaining sections
  (read-first order, SDLC contract, boundaries) plus whatever skills ship
  with the harness it's running in.
- If a skill pack is wanted again, that's a new, deliberate decision — not
  a silent reinstatement.
- Unreferenced/dead application source code (if any) was not audited here
  and may still exist in `apps/`, `services/`, `packages/`, `shared/`.
