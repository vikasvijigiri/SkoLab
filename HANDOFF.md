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

- **CoLab Workspace redesign approved, not yet implemented** (2026-09-12,
  `decisions/0019-colab-workspace-consolidated-writing.md`). Reference
  mockup (8 screens: Home; Documents default/Templates-open/Share-open/
  dock-collapsed; Focus mode; Members; Tasks & Meetings) is a Claude Design
  canvas at https://claude.ai/code/artifact/b0629b28-fc26-45d1-a3fe-204eb7270e67 —
  read it before touching `apps/web/src/app/(app)/workspace/` or
  `components/workspace/`.
- **Discovery + Horizon redesign approved, not yet implemented**
  (2026-09-12, `decisions/0021-discovery-horizon-fused-highlights.md`).
  Reference mockup (8 screens: Discovery Researchers/Papers/Topics, Compare,
  researcher Highlights, expanded dashboard, Horizon input/result) is a
  Claude Design canvas at
  https://claude.ai/code/artifact/3638613e-3a06-4f18-a8bc-4012d8e72d04 —
  read it before touching `apps/web/src/app/(app)/discovery/`,
  `.../horizon/`, or `.../author/[id]/`.
- **Global top bar redesign approved, not yet implemented** (2026-09-12,
  `decisions/0020-global-topbar-solid-brand-blue.md`): 48px solid
  brand-blue bar, applies to every screen in both canvases above. Read
  0020 before touching `TopBar.tsx`.
- **Signals (unified alerts) redesign implemented** (2026-09-13, on
  `feature/frontend-redesign-2026-09`) — `decisions/0022-signals-unified-alerts.md`.
  `ActivityType` gained `citation_received`, `tracked_researcher_paper`,
  `tracked_topic_activity`, `mention`, `invite`; `useNotifications.ts`/
  `NotificationsBell.tsx` render all of them with real, specific copy; new
  `/notifications` (grouped by day, 5 filter chips, mark-all-read, empty/
  loading/error states) and `/notifications/manage` (per-kind cadence,
  `users/{uid}/settings/notifications`, direct client write per decision
  0004) routes. End-to-end: citations (Go gateway watermark at
  `users/{uid}/notification_state`, reusing the same `cited_by_count`
  `/author/[id]` already computes) and tracked-researcher papers (Go
  gateway reads `users/{uid}/tracked_researchers`, written by Discovery's
  now-also-implemented Track feature — the full bookmark-then-notify loop
  is real end to end). `tracked_topic_activity`/`mention`/`invite` are
  frontend-ready only — no topic-follow feature or CoLab @mention parser
  exists yet to produce them; see the code comments in `useNotifications.ts`
  and `internal/activity/activity.go`. The Go gateway changes pass
  `go build`, `go vet`, and `go test ./...` cleanly (13 new/existing tests
  in `internal/activity` and `internal/firestore`). Reference mockup
  (6 screens) is a Claude Design canvas at
  https://claude.ai/code/artifact/918c193e-b166-4269-b629-f67a1f476929.
- **Profile redesign + CV export approved, not yet implemented**
  (2026-09-12, `decisions/0023-profile-redesign-and-cv-export.md`) — this
  closes the original session scope ("CoLab + Profile"), which had gone
  undesigned through eight other screens until a self-audit caught it.
  Reference mockup (3 screens: view, edit, CV export/share) is a Claude
  Design canvas at
  https://claude.ai/code/artifact/04619c80-3a0b-45ef-aba4-cf2be07b49ca —
  read 0023 before touching `apps/web/src/app/(app)/profile/page.tsx`.
- **A full self-audit was run across all four canvases above** (2026-09-12,
  prompted by asking "see if anything is missing" before assuming the
  redesign work was done) — every finding was fixed the same session and
  is recorded as an addendum in the decision file it affects (0019, 0021,
  0022). Worth repeating this kind of pass before treating any future
  slice of this redesign as complete.
- **A second, independent audit pass** (2026-09-12, same day, prompted by
  "reverify again") ran a dedicated read-only audit agent per canvas —
  checking every button/link resolves somewhere, chrome consistency, and
  fidelity against both the decision text and the real app code — then a
  separate fix pass applied every must-fix finding and republished all
  four canvases at the same URLs. Recorded as a second addendum in each
  decision file (0019, 0021, 0022, 0023).
- **A third pass (2026-09-13) closed every item that second audit had left
  as open backlog**, recorded as a third addendum in each decision file:
  CoLab gained 3 mobile artboards (Home, Documents, Documents-with-dock-
  open-as-bottom-sheet) plus Home-loading and Documents-error states (18
  screens total); Discovery+Horizon gained a Discovery loading state and a
  genuine Horizon failure state distinct from `is_fallback` (9 screens);
  Signals gained an exact unread-count badge (replacing a plain dot),
  plus loading and failed-to-load states (6 screens); Profile gained a
  loading state and a save-error banner (4 screens). This pass also found
  and fixed a real bug in all four canvases: every accent color
  (`--accent-amber/indigo/emerald/rose/violet/orange`) had been pulled
  from a superseded design direction instead of the values actually live
  in `globals.css` today (decision 0013's theme) — corrected everywhere,
  and the real WCAG contrast for the correct values was hand-verified
  against `apps/web/scripts/check-contrast.mjs`'s exact formula (all six
  clear both the 3.0:1 and 4.5:1 thresholds, 5.5:1–6.7:1 in practice) —
  closing that contrast-recheck item with a verified answer instead of
  leaving it outstanding. Remaining known gaps, all explicitly deferred as
  lower-priority: no empty/zero-result states or pagination on Discovery,
  no empty-state for the CV share panel's connections list, mentions/
  invites sharing one Signals settings row. None of these block reading
  the canvases as implementation references.
- **A route-level verification (2026-09-13, `decisions/0024`) found three
  real routes with zero design coverage** and closed them: Home feed (2
  screens) — https://claude.ai/code/artifact/25456c65-ecb2-4c3d-b033-76277b72c96b;
  Paper detail (3 screens) — https://claude.ai/code/artifact/07cbf4cc-3ef0-4dab-af58-c8c7ad1b62bf;
  Settings (1 screen) — https://claude.ai/code/artifact/43da870e-a872-46d4-a35b-94ad671bcffb.
  Read 0024 before touching `apps/web/src/app/(app)/home/`, `.../paper/`,
  or `.../settings/`.
- **Full-stack implementation of all eight redesigned areas is now
  underway on `feature/frontend-redesign-2026-09`**, off `main`, per
  explicit product-owner direction (2026-09-13): CoLab, Discovery+Horizon,
  Signals (including its backend — new `ActivityType` values and the
  write path that populates them), Profile+CV export (including a share
  path), Home feed, Paper detail, and Settings. This branch should land as
  a reviewed PR against `main`, not a direct merge — see `LOG.md`'s
  2026-09-13 entry once implementation lands for the verification results
  (tests, lint, typecheck) before merging.
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
