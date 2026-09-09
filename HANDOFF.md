# HANDOFF

> Current status (2026-09-09): professional-network UI audit is green on
> `feat/instrument-frontend`; 19 Playwright checks, 110 unit tests, TypeScript,
> lint, build, contrast, and production dependency audit pass. The branch is
> ready to push and open as a PR. The previous Claude layer is preserved locally
> at `.claude.previous-20260909`; the active layer is sourced from addyosmani
> and Vercel frontend skills under `.claude/skills/`.

> Current-state snapshot, not history. Overwritten in place at the end of
> every session. For history, see `LOG.md`; for why a decision was made, see
> `decisions/`.

**Last updated:** 2026-09-08

## Where the repository is

`main` is at `1ab8c31`. Active work is on branch **`feat/instrument-frontend`**
(3 commits ahead, unmerged, no remote):

- `7844a28` — Phase A: `.claude/skills/frontend-ui/` + `engineering-standards/
  references/frontend-performance-rules.md`, adapted from `addyosmani/agent-skills`
  and `vercel-labs`. `vendor/harness-skills/addyosmani-agent-skills/` vendored
  (skill content only).
- `7616035` — Phase B: `DESIGN.md` rewritten C → B (dark-first "Instrument");
  `decisions/0012-instrument-visual-identity.md`.
- `8f6151e` — Phase C: `apps/web` migrated to the dark-first contract —
  `globals.css` dark-as-base, primitives, shell, avatars.

Plan: `docs/plans/2026-09-08-instrument-frontend.md` (all phases ticked;
`## Approved`).

## Verification behind the branch

| Check | Result |
|---|---|
| `python tools/new_skill_check.py --all` | 16 skills, all required pass |
| `python tools/test_process_router.py` | rc 0 |
| `python tools/run_checks.py --tier all --require-test` | `PASS: 5 check(s) green` |
| `apps/web`: `npm run test` | 110 passed (30 files) |
| `apps/web`: `npx tsc --noEmit` · `npm run lint` · `npm run build` | green (lint: 1 pre-existing unrelated warning) |
| `apps/web`: `npm run check:contrast` | all token pairs pass, both themes |
| `apps/web`: `npm run test:e2e` | 18/19 — axe WCAG AA specs pass |

## What needs a decision / attention

- **Branch is unmerged and has no remote.** Nothing pushed. `/publish` +
  `release-git` when the owner wants it on `main`. Formal `testing` and
  `code-review` skill passes were not run separately — every gate was run and
  quoted during implementation.
- **Pre-existing e2e failure `smoke.spec.ts:13`** ("Firebase isn't configured
  yet" notice on `/login`). Verified to fail identically on the pre-change
  tree — an env/config issue, not from this work. Untouched.
- **`.env` still lacks** the keys listed in `.env.example` (`OPENALEX_API_KEY`,
  `EMAIL_BLIND_INDEX_KEY`, etc.). Owner's own credentials; no agent fills them.
- **Newly added skills do not hot-attach to an already-running Claude Code
  session** — `/frontend-ui` becomes invocable after a restart. Its guidance
  was applied directly this session regardless.
- **`decisions/0012` supersedes** `docs/plans/2026-09-07-web-visual-identity-round1.md`
  and the direction-C contract. Rollback path is in the decision record.
