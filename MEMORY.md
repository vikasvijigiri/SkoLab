# Project Memory

> Current web direction (2026-09-09): Professional Network visual language —
> light by default, neutral canvas, white panels, professional blue, Inter
> typography, and compact navigation. Dark remains an explicit option. Active
> frontend skills are vendored from `addyosmani/agent-skills` and
> `vercel-labs/agent-skills` under `.claude/skills/`.

<!-- Engineering-relevant facts and context worth persisting across sessions for
this repo. Distinct from Claude Code's own auto-memory index under
~/.claude/projects/<slug>/memory/ -- this file is checked into the repo. -->

- The daily-feed candidate pool (`pipeline_services.get_daily_feed`) is
  **heterogeneous by design**: arXiv results are hand-built into OpenAlex-*shaped*
  dicts (`_fetch_arxiv_candidates`) and mixed in one list with real OpenAlex
  `search_works` / `fetch_related_works` output. The arXiv shape is only a partial
  imitation — its `authorships[]` entries carry an `author.display_name` and nothing
  else: no `author.id`, no `institutions`. Any helper consuming that pool must
  shape-check rather than assume the OpenAlex schema, and must degrade per-entry
  instead of aborting, since it sits behind endpoints whose outer `except Exception`
  turns any escape into a 500. See ISSUES.md 2026-08-08.

- **`apps/web` visual identity is dark-first "Instrument" (direction B)** as of
  `decisions/0012` (2026-09-08). Dark is the base theme on `:root` in
  `globals.css`; light is a full override behind `@media (prefers-color-scheme:
  light)` + `[data-theme]`. This deliberately supersedes the 2026-09-07
  "Confident Modernist" (direction C) rollout. `DESIGN.md` is the authority; do
  not "restore" the light-first look without a new positioning decision. Build
  and audit web UI through the `frontend-ui` skill (`.claude/skills/frontend-ui/`),
  which checks against `DESIGN.md` + a WCAG floor; React/Next perf rules are in
  `engineering-standards/references/frontend-performance-rules.md`. The token
  architecture carries the theme — semantic utilities (`bg-surface`,
  `text-primary`, `text-text-on-primary`, `shadow-card`) never need a `dark:`
  prefix. `apps/web/scripts/check-contrast.mjs` keeps its own mirror of the
  token hexes — update both it and `globals.css` together.

- `QuestsService.get_leaderboard`'s `ValueError: No leaderboard data available ...` is a
  **terminal fallthrough, not a diagnosis**: both the Firestore and PostgreSQL branches
  swallow their real exception into a `print` and fall through to it, so the message
  names neither cause. Re-run the failing test with `pytest -s` to recover the actual
  error before theorising about missing data. See ISSUES.md 2026-08-08.
