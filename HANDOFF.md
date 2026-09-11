# HANDOFF

> Current-state snapshot, not history. Overwritten in place at the end of
> every session. For history, see `LOG.md`; for why a decision was made, see
> `decisions/`.

**Last updated:** 2026-09-12

## Where the repository is

`main` has absorbed the 2026-09-11 backend-audit/live-feed work (PR #180)
plus a large Dependabot sweep (2026-09-12): 26 dependency PRs merged, two
systemic CI gaps fixed at the root (not just worked around), and
`dependabot.yml` updated so the confirmed-broken bumps below stop
reopening every week. Full detail in `LOG.md`'s 2026-09-12 and 2026-09-11
entries.

- **CI no longer silently drifts.** Two gaps found and fixed for real:
  - `verify.yml`'s Android job failed identically on every Dependabot PR
    (GitHub withholds secrets from Dependabot-triggered runs, and
    `GOOGLE_SERVICES_JSON` is one) — now falls back to a well-formed fake
    placeholder when the secret is empty, so Dependabot PRs get a genuine
    compile signal instead of a guaranteed unrelated failure. PR #181.
  - `ci.yml` hardcoded `go-version: "1.25"` while `checks.yml` used
    `go-version-file`; a legitimate Go dependency bump raised `go.mod`'s
    own `go` directive to `1.26.0` and the hardcoded value went stale,
    breaking every PR touching `services/backend-go` (even ones with zero
    Go changes). Both workflows now read `go-version-file`. PR #182.
- **`dependabot.yml` now blocks the exact bumps already proven broken**,
  each with an inline comment naming the failure and what unblocks it —
  see "What needs a decision" below for the full list. This is a
  stop-gap against repeat investigation, not a fix for the underlying
  incompatibilities.
- Everything from the 2026-09-11 session (feed refresh-on-reload/focus/
  login, `is_fallback` flagging on the three LLM fallback paths, the two
  deleted fake Go endpoints, the `internal/quest`/`internal/user` test
  coverage + nil-pointer fix) is confirmed intact — re-verified after the
  dependency churn: Python `pytest -q` 222 passed/4 skipped, web
  `vitest run` 245 passed, `tsc --noEmit` + `eslint src` clean, Go
  `build && vet && test ./...` all green.

## What needs a decision / attention

- **Coordinated bumps deliberately deferred, each blocked in
  `dependabot.yml` until someone does the paired work:**
  - okhttp 5.x needs `compileSdk >= 37` bumped first (Android)
  - Android Gradle Plugin 9.4.0 needs the Gradle wrapper bumped to
    `>= 9.6.0` first
  - numpy `>= 2.5.0` / networkx `>= 3.5` need CI's Python bumped to
    `>= 3.11`/`3.12` first (currently 3.10 in `ci.yml`, 3.11 in
    `checks.yml` — inconsistent, worth unifying)
  - ESLint 10 needs a compatible `eslint-plugin-react` release
  - TypeScript 7.0 needs `typescript-eslint` to support it
  - vitest 5 / jsdom 30 need `vite` pinned as a direct dependency first
    (currently only resolved transitively)
  - `actions/setup-node@7` resolves a broken native binding
    (`@rolldown/binding-wasm32-wasi`) for one of the web workspace's
    optional deps — root cause not yet investigated past that point
  - `react`/`react-dom` now grouped in `dependabot.yml` so future bumps
    land together instead of splitting across independent PRs that break
    npm's peer-dependency resolution in isolation
- **`firestore.rules` is deployed to production** (confirmed via
  `npx firebase-tools deploy --only firestore:rules`). Worth a manual
  spot-check in the Firebase Console that published rules match the repo
  file.
- Industry-tieups and daily-conjecture endpoints correctly flag
  `is_fallback` but have no frontend consumer today — wire the flag
  through the same way Horizon's card does if either becomes user-facing.
- `internal/quest`/`internal/user` Go tests only cover no-DB and
  validation paths; DB-backed branches only run against CI's `slow` job
  Postgres container, not as Go unit tests.
