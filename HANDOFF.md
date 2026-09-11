# HANDOFF

> Current-state snapshot, not history. Overwritten in place at the end of
> every session. For history, see `LOG.md`; for why a decision was made, see
> `decisions/`.

**Last updated:** 2026-09-11

## Where the repository is

`main` is at `3d32f90` plus one uncommitted pass (about to land as its own
commit/PR): a full backend response audit — every Go gateway and Python
endpoint reviewed for whether its response is genuinely correct, not a
silent stub — plus a LinkedIn-style "refresh on reload/tab-focus/login" pass
on the home feed. Full detail in `LOG.md`'s 2026-09-11 entries.

- **Home feed now refreshes like a social feed.** `dailyFeedQuery`,
  `activityFeedQuery`, `scienceNewsQuery`, `industryOpportunitiesQuery`
  (the four TanStack Query factories behind `UnifiedFeed`) opt into
  `refetchOnWindowFocus: true` + `refetchOnMount: "always"` — scoped to
  just those four, not the app-wide default (`providers.tsx` keeps
  `refetchOnWindowFocus: false` globally; most queries, e.g. author-profile
  pages, shouldn't re-hit the network on every tab focus). A new
  `InvalidateOnAuthChange` wrapper in `providers.tsx` calls
  `queryClient.invalidateQueries()` whenever the signed-in uid changes
  (login, logout, account switch), so a returning/switching user never sees
  a stale previous session's cache. 4 new tests
  (`components/providers.test.tsx`).
- **Three silent LLM-fallback responses now say so.** Horizon's
  `predict_next_big_thing`, the industry-tieups service, and
  `daily_conjecture`'s canned-puzzle fallback all used to return
  placeholder content shape-identical to a real result, with a plain `200`
  — no signal to the caller. All three response schemas gained
  `is_fallback: bool = false`; the fallback paths set it `true` (and it
  survives into whatever gets cached, so a cache hit still reports it
  honestly). Horizon's result card (`HorizonPredictionResult.tsx`) now
  shows an "Estimated — AI unavailable" badge when it's set — the one
  place among the three actually rendered in `apps/web` today (industry
  tie-ups and daily-conjecture are backend-correct now but have no live
  frontend consumer). OpenAPI snapshot regenerated to match. New/updated
  tests across `test_prediction_service.py`, `test_industry_academic.py`,
  `test_feed.py` (the conjecture fallback path had *zero* prior coverage),
  and `horizon/page.test.tsx`.
- **Two permanently-fake Go endpoints deleted.** `GET /support/metrics`
  (hardcoded "live" stats) and the whole `integrations/zotero/*` OAuth stub
  trio — both reachable in production, neither ever called by `apps/web`.
  Removed the handlers, routes, and their shape-only mock-asserting tests;
  left a pointer in `main.go`/`internal/feed/feed.go`'s doc comments and
  `docs/backend-auth-posture.md` for if a real integration is ever built.
- **Two real nil-pointer bugs fixed in `internal/user`.**
  `SyncUserProfile` and `DeleteUser` called `db.Pool.Exec`/`.Begin()` with
  no nil check — unlike every sibling handler in the codebase — so a DB
  outage panicked into `gin.Recovery`'s generic 500 instead of the same
  clean, documented 503 everything else gives. Found this by writing the
  first tests either package (`internal/quest`, `internal/user`) has ever
  had — 19 new Go tests total, including direct coverage of
  `aggregateMemory` (previously-untested pure business logic deriving
  reading pace / research style / top topics from activity logs).

## What needs a decision / attention

- **Nothing agent-blocking remains open.** `firestore.rules` is deployed to
  production (confirmed via `npx firebase-tools deploy --only
  firestore:rules`, clean compile + release). Worth a manual spot-check in
  the Firebase Console that published rules match the repo file, and a live
  smoke test confirming an unrelated account is denied.
- **Two audit findings were deliberately left alone, by explicit choice**
  (not an oversight): the industry-tie-ups and daily-conjecture endpoints
  now correctly flag `is_fallback`, but neither has a frontend consumer
  today — no badge UI was built for them since there's nowhere to show it
  yet. If either becomes user-facing, wire the flag through the same way
  Horizon's card does.
- **`internal/quest` and `internal/user` tests only cover the no-DB and
  validation paths** (input validation, `db.Pool == nil` guards,
  `aggregateMemory`'s pure logic) — matching the existing pattern in
  `internal/feed/feed_test.go`. The DB-backed branches (real leaderboard
  query, real quest completion, real activity-log aggregation end-to-end)
  still only run against the CI `slow` job's Postgres container, not as Go
  unit tests.
