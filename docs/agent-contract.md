# Agent Contract

This is the portable, repo-level contract for any coding agent working in
SkoLab. Tool-specific features like MCP servers, skills, plugins, and global
hooks are not defined here; this file only captures what should be visible and
actionable from the repository itself.

## Read First

Any agent should read these in order:

1. `AGENTS.md`
2. `HANDOFF.md`
3. `README.md`
4. The most relevant package- or app-level guide, such as `apps/web/AGENTS.md`
5. `decisions/README.md` when the work may affect architecture or product scope

## Portable Rules

- Treat the repo docs as the source of truth for behavior, workflow, and
  constraints.
- Use repo scripts and checked-in commands instead of inventing one-off flows.
- Prefer the narrowest file scope that can solve the task.
- Do not hardcode a specific user, author, or researcher into logic.
- Keep backend, web, Android, and gateway responsibilities in their own
  language boundary.
- Protect secrets and never commit `.env`, `.env.local`, or service account
  files.
- If a backend change affects live behavior, verify it with a real request.
- If a web change touches TypeScript, run the repo's TypeScript check before
  considering it done.
- If the task changes architecture, shared workflow, or how the stack runs,
  update the repo docs alongside the code.

## Session-End Expectations

When an agent changes code or docs, it should also keep the current-state docs
fresh:

- Update `HANDOFF.md` with the latest state.
- Append a new entry to `LOG.md`.
- Add or update a decision file if the change is a real architectural or
  product-scope choice.

## Local Skills & Automation

Local repository skills and hooks are located under `.claude/skills/` and `.claude/hooks/`. All agents operating in this repository should invoke matching local skills for verification, testing, API syncing, cache clearing, and task logging (see `AGENTS.md` "Local Skills, Hooks & MCP Servers" for the complete registry).

