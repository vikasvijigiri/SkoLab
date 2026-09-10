# HANDOFF

> Current status (2026-09-10): `main` is at `cff6997` (PR #119 merged) with the
> Discovery **fit-first collaborator finder** shipped — see `LOG.md`'s
> 2026-09-10 entry and `decisions/0014`. Working tree clean apart from
> `.claude/hooks/state/*` bookkeeping. No open feature branch.

> Current-state snapshot, not history. Overwritten in place at the end of
> every session. For history, see `LOG.md`; for why a decision was made, see
> `decisions/`.

**Last updated:** 2026-09-10

> **Note:** the sections below "Where the repository is" predate several merges
> (daily brief, research surfaces, discovery fit-first) and are stale — trust
> `LOG.md` and `git log` over them until a full `documentation` pass refreshes
> this file.

## Where the repository is

`main` is at `cff6997` — merge of PR #119
(`feat/discovery-fit-first`, base `206e845`), branch deleted local + remote.

- Discovery's researcher surface is now fit-first: defaults to the viewer's
  resolved subfield, ranks by a no-embeddings fit score, shows activity /
  momentum / career-stage / decades / topical-focus / standing / ORCID /
  deceased signals with a click-only filter rail.
- New: `apps/web/src/lib/discovery/*`, `apps/web/src/components/discovery/*`,
  `apps/web/src/app/api/enrich/{deaths,collab-flags}`. No Go/Python change.
- Plan `docs/plans/2026-09-10-discovery-fit-first.md` (all 11 tasks ticked,
  `## Approved`); decision `decisions/0014-discovery-fit-first-collaborator.md`.
- Verified before merge: tsc 0, lint 0, vitest 39 files / 160 tests,
  check:contrast pass, Playwright `discovery-researchers` + `rollout-visual`
  10/10 (axe AA), `next build` 0.

### Earlier (stale) — instrument-frontend, 2026-09-08

`main` was at `1ab8c31`; branch `feat/instrument-frontend`
(`7844a28` `7616035` `8f6151e`) — dark-first "Instrument" identity,
`decisions/0012`, plan `docs/plans/2026-09-08-instrument-frontend.md`. Status
of that branch relative to current `main` is unverified here.

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
