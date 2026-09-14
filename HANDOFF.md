# HANDOFF

> Current-state snapshot, not history. Overwritten in place at the end of
> every session. For history, see `LOG.md`; for why a decision was made, see
> `decisions/`.

**Last updated:** 2026-09-14

## Where the repository is

`main` has absorbed the full frontend redesign (PR #219, merged
2026-09-13): CoLab Workspace, Discovery+Horizon+Track, Signals, Profile+CV
export, Home feed, Paper detail, and Settings — decisions 0019-0024, all
now marked implemented. Full detail on what shipped, what was merged from
where, and what was simplified/deferred is in the "What needs a decision"
section below. Both post-merge action items are done: the Firestore
security rules test suite was run for real (28/28 passed, via a portable
JDK 21 already on this machine at `tools/jdk-21.0.5+11` — `firebase-tools`
needs Java 21+, not just any JDK), and the updated `firestore.rules` (new
`users/{uid}` subtree for Signals/Track, `researchers/{uid}/cvShares` for
CV sharing) is deployed to production (`skolab-vvi`), confirmed via
`firebase deploy --only firestore:rules`'s own "released rules" output.

- **A full production audit (2026-09-14)** — every Go gateway route and
  every Python service route curl-tested against live `*.onrender.com`,
  every frontend button/link code-reviewed app-wide, Sentry checked for
  real production errors — found and (mostly) fixed real bugs. PR #221
  fixed 6 confirmed issues (an unauthenticated internal LLM endpoint, a
  dishonest missing `is_fallback` field, a silently-broken cache
  write-back, an unbounded slow endpoint, dead download links, and a
  content-quality OpenAlex query bug), plus 2 small frontend findings.
  **The real headline finding, discovered via direct Render API access
  (build logs + deploy history — a Render API key is needed for this,
  not just the app's own `*.onrender.com` endpoints):**
  `skolab-backend-py` had **failed to build on every single deploy since
  2026-09-11** — `26497f8` bumped `numpy`/`networkx` in
  `requirements.txt` to versions requiring Python ≥3.11/3.12 "now that CI
  runs Python 3.12," but never touched `services/backend/Dockerfile`,
  which was still pinned to `python:3.10-slim`. Every build since silently
  failed at `pip install`, so Render kept serving a pre-2026-09-11 image
  the entire time this session's frontend and backend work was happening
  — explaining every "production doesn't reflect current `main`" symptom
  the audit found. Fixed directly on `main` (`52fc589`, bumped both
  Dockerfile stages to `python:3.12-slim`) — **first successful Python
  deploy since 2026-09-11**, confirmed live via the Render API.
- **A second real bug found only after that first deploy actually landed**:
  PR #221's own `network_collaborators` cache-write-back fix gave the
  write a fresh, non-expired context but still called it *synchronously*
  — production logs showed every request now blocking on the write
  instead of returning fast, confirmed via live Render logs. Fixed
  (`284d44d`, wrapped in a goroutine, matching the existing `fireTeleport`
  fire-and-forget convention) and reverified live.
- **Full final verification, all curl-tested against production after
  both fixes landed**: `/downloads/*` 200 (was 404), `/match_grants` 200
  in 2.2s (was 34.9s/timeout), `/discovery/predict` now carries
  `is_fallback` correctly, `/api/v1/network_collaborators` 200 in ~31s
  (matches the original, pre-regression baseline — see "What needs a
  decision" for why the cache itself still doesn't populate, a separate,
  deeper perf issue).
- **`INTERNAL_API_TOKEN` is resolved.** It was never set in production
  (absent from `render-env-vars.env` too). Generated a real secret, set it
  via the Render API's single-key env-var endpoint on both
  `skolab-gateway` and `skolab-backend-py`, and — critically — learned
  that Render's `/restart` endpoint does **not** pick up a new env var; only
  a fresh `POST /deploys` (with `clearCache: "clear"`) does. Verified live,
  end to end, with real payloads and the correct token: `embed_work`
  (real OpenAlex embed), `author_metrics_enrich` (real LLM analysis), and
  `internal/compute_metrics` (200; response values look plausible for a
  minimal test payload, worth a follow-up check against a realistic
  digest) all genuinely work now, not just auth-reject correctly.
- **`/observability` and `/api/v1/author_stats` — actually fixed
  (2026-09-14), root cause found in `internal/middleware/gzip.go`.** The
  earlier working theory (a Render edge routing rule tied to the literal
  route path) was wrong — proven wrong by renaming the routes and seeing
  identical failures. The real signal: the app's own structured log
  recorded a clean `200` for `/observability` in the *exact same second*
  Render's edge returned a synthetic `502 no-deploy` to the client, and a
  *small* response on the same handler family (an invalid-`author_id`
  422) succeeded every time while a *large* one (a real author bundle, or
  `/observability`'s full metrics dump) failed every time. That's
  response-size-dependent corruption, and this codebase already has one
  exact precedent for it: `main.go`'s `reverseProxy`/`ModifyResponse`
  buffers proxied responses for the identical reason (a multi-chunk write
  through gzip got mangled at Render's Cloudflare-fronted edge). The
  native-route `Gzip()` middleware never got the same treatment — it
  flushed compressed bytes to the connection on every `Flush()` call,
  producing chunked, multi-write output for anything past one
  `gzip.Writer` buffer. Fixed by buffering the whole compressed body and
  writing it once with a correct `Content-Length` instead of chunked
  transfer (`f3e5dc1`) — verified live, 5/5 clean `200`s on
  `/observability`, plus `/api/v1/author_stats` and the old
  `/api/v1/author_metrics` alias both `200` with a real author bundle.

- **Product-scope changes (2026-09-14, explicit product-owner direction, all
  on `main`):**
  - **Discovery and Horizon removed** — the standalone `/discovery` and
    `/horizon` tabs, their nav entries, and every file that existed only to
    serve them (~50 files: routes, components, lib, three Next.js API route
    handlers backing Discovery's own grid). The global `ResearchCategoryRail`
    ribbon (For you/Papers/People/.../Jobs) went too — every link pointed at
    `/discovery`. Kept, per explicit direction: `/author/[id]`'s
    Highlights/Track/Compare layer from the same redesign
    (`decisions/0021`) — RelationshipGraph, MomentumSparkline, TrackButton,
    AuthorInline, the on-demand Horizon-predict button — plus everything
    that turned out to be genuinely shared (`openAlexWorks` for CoLab's
    SlashMenu, `openAlexAuthorsByTaxon`/`openAlexWorksByTaxon` for the
    landing demo and Nexus, `openAlexAuthorMatchQuery` for Profile/
    onboarding). No backend code was removed — every route the deleted
    pages called is also load-bearing for something kept. See
    [[discovery-horizon-redesign-2026-09]] for the full before/after.
  - **CoLab Workspace: the project ribbon folded into the Documents editor's
    own toolbar row.** The page used to stack a project-identity header
    (back/name/role/share/delete) directly above the document editor's own
    meta strip (save status/words/Call/Share paper/Templates/Originality/
    Preview/Focus) — two ~50px rows for chrome the writing surface could use
    instead. Merged into one row for the Documents tab specifically; Tasks &
    Meetings and Members (no equivalent row of their own) keep the page-level
    header.
  - **Tasks & Meetings is no longer a separate destination.** It's a fourth
    tab in the Documents editor's existing dock (`DocumentDock`), alongside
    Quick reference/Chat/Equations — the same "no separate screen" treatment
    those two already got under `decisions/0019`. The workspace rail drops
    to 2 destinations (Documents, Members).
  - **Science news removed entirely, frontend and backend** — the Go
    gateway's `internal/pulse` package (RSS/Atom aggregation from Quanta/
    Phys.org/ScienceDaily/Nature) and both its routes are gone, along with
    the "News" lens on the Home unified feed and every piece of
    `ScienceNewsItem`/`getScienceNews`/`scienceNewsQuery` plumbing.
    `FeedKind` narrows to `paper | activity | job`.
  - All four verified: Go build/vet/test clean, `next build` clean, tsc/
    eslint clean, vitest 216/216 passing, deployed and spot-checked live.

`main` also carries the 2026-09-11 backend-audit/live-feed work (PR #180)
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

- **All eight redesigned areas are now implemented, merged, and verified**
  on `feature/frontend-redesign-2026-09` (2026-09-13), off `main`, per
  explicit product-owner direction — **not yet merged to `main`, needs PR
  review first.** Six work packages were built in parallel (isolated git
  worktrees, one per area) and integrated back with `git merge` (four
  clean/fast-forward, two needed manual conflict resolution — see below):

  - **Global top bar + category rail** (`decisions/0020`, `0019`'s
    addendum): `TopBar.tsx` is now a 48px solid-blue bar; `AppShell.tsx`
    conditionally hides `ResearchCategoryRail` on Workspace/Profile/Settings.
  - **CoLab Workspace** (`decisions/0019`): Chat/Equations consolidated into
    a dockable panel (`DocumentDock.tsx`) with collapse-to-rail; journal-
    specific templates (PRL/PRB/NJP/EPL) in `ResearchTools.tsx`; a live
    `QuickReferencePanel.tsx`; a `/`-triggered `SlashMenu.tsx`.
  - **Discovery + Horizon + Track** (`decisions/0021`): a Highlights layer
    on `/author/[id]` above the existing dashboard; `RelationshipGraph.tsx`
    (generic, reused by Profile too); Track (`lib/firebase/tracking.ts`,
    real Firestore writes to `users/{uid}/tracked_researchers/{authorId}`);
    `CompareModal.tsx`; TL;DR-first `PaperResultCard.tsx`, now linking to
    `/paper/[id]`.
  - **Signals** (`decisions/0022`): `ActivityType` extended with 5 new
    kinds; `/notifications` and `/notifications/manage` routes; citation-
    delta detection and tracked-researcher-paper surfacing implemented in
    the Go gateway (`internal/activity/activity.go`) — both genuinely
    end-to-end, not stubs. `tracked_topic_activity`/`mention`/`invite` are
    frontend-ready only (no topic-follow feature or CoLab @mention parser
    exists yet to produce them).
  - **Profile + CV export** (`decisions/0023`): the old capability-graph
    card replaced with a real `CoAuthorGraph.tsx`; a new `/profile/cv/[uid]`
    route; click-first sharing (`CvSharePanel.tsx`, `lib/firebase/cvShare.ts`)
    to real CoLab connections, with an `mailto:`/`sms:` fallback. The share
    copy was corrected from the mockup's "no account needed" claim to
    "signed in to SkoLab" — the route sits under the auth-gated `(app)`
    group, so the original claim would have been false.
  - **Home feed + Settings** (`decisions/0024`): verified `home-client.tsx`
    and `IdentityStrengthCard.tsx` already met the honesty bar (a real,
    explainable profile-strength percentage, not a fabricated score) — no
    rebuild needed. Settings' Notifications section now links to
    `/notifications/manage` instead of a vague placeholder.
  - **Paper detail** (`decisions/0024`): verified already faithful to the
    design (honest confidence badge, "Honest Limitations" section, correct
    loading/partial-failure handling) — no changes needed.

  **Merge conflicts resolved by hand** (both from two workstreams
  independently touching the same shared surface): `apps/web/src/lib/
  types.ts`'s `TrackedResearcher.trackedAt` type (kept `unknown` — the
  field is a Firestore `serverTimestamp()`, never a plain number, which
  the Go consumer confirmed it never reads directly); `firestore.rules`'
  `users/{uid}` block (kept the more complete version with `settings`,
  `tracked_researchers` with an authorId-honesty check, and a server-only
  `notification_state`); `apps/web/src/app/(app)/settings/page.tsx`'s
  Manage Alerts link (merged both agents' copy).

  **Full verification:** `tsc --noEmit` clean; `eslint src` clean;
  `vitest run` 267/267 passed (59 files); Go `build && vet && test ./...`
  all green (`services/backend-go`); Python `pytest -q` 230 passed/4
  skipped (unchanged — no Python touched); `npm run test:rules` (Firestore
  security rules suite) 28/28 passed against the real emulator (needs
  Java 21+ specifically — `firebase-tools` refuses older JDKs).

  **`firestore.rules` was modified and is deployed** (new `users/{uid}`
  subtree for Signals/Track, new `researchers/{uid}/cvShares`
  subcollection for CV sharing) — released to production (`skolab-vvi`)
  2026-09-13 via `npx firebase-tools deploy --only firestore:rules`, with
  explicit product-owner confirmation before running it (deploys are
  approval-gated, not run on general go-ahead alone). Track, notification
  settings, and CV sharing are live against real Firestore now.

  Design references (all seven Claude Design canvases, now implementation
  history rather than a forward spec): CoLab —
  https://claude.ai/code/artifact/b0629b28-fc26-45d1-a3fe-204eb7270e67;
  Discovery+Horizon —
  https://claude.ai/code/artifact/3638613e-3a06-4f18-a8bc-4012d8e72d04;
  Signals — https://claude.ai/code/artifact/918c193e-b166-4269-b629-f67a1f476929;
  Profile — https://claude.ai/code/artifact/04619c80-3a0b-45ef-aba4-cf2be07b49ca;
  Home feed — https://claude.ai/code/artifact/25456c65-ecb2-4c3d-b033-76277b72c96b;
  Paper detail — https://claude.ai/code/artifact/07cbf4cc-3ef0-4dab-af58-c8c7ad1b62bf;
  Settings — https://claude.ai/code/artifact/43da870e-a872-46d4-a35b-94ad671bcffb.

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
- **`network_collaborators`'s cache write-back genuinely completes but
  genuinely fails** — confirmed live: ~194 individual `researcher_profiles`
  row upserts, all timing out with `context deadline exceeded` even on a
  fresh 15s budget. Root cause is almost certainly Supabase Postgres
  living in `ap-southeast-1` while every Render service is in `oregon` —
  `DEPLOY.md` already names this exact cross-Pacific latency risk. Real
  fix is batching the upserts into one multi-row statement instead of
  ~194 individual ones (would cut round-trips by ~194x, likely enough on
  its own); moving Supabase to a US region is the other lever. Not fixed
  here — it's no longer a live-blocking bug (the response returns
  correctly around 30s either way, matching the pre-regression baseline),
  just a caching layer that's permanently a no-op today.
- ~~`INTERNAL_API_TOKEN` unset in production~~ **Resolved 2026-09-14.**
  Generated a real random secret and set it identically on both services
  via the Render API's single-key env-var endpoint (not a bulk replace —
  both services had fewer keys listed than they clearly use, so a bulk
  write risked wiping working config not shown by that call). One real
  gotcha: Render's `/restart` endpoint did **not** pick up the new env var
  — `teleport`/`embed_work`/`author_metrics_enrich` all kept accepting
  requests with a deliberately wrong token even after a restart and a
  3+ minute wait. A full `/deploys` trigger with `clearCache: "clear"`
  did pick it up. Verified end-to-end on all four internal routes
  (`teleport`, `embed_work`, `author_metrics_enrich`,
  `compute_metrics`): no token → 401, wrong token → 401, correct token →
  succeeds. **Lesson for next time: an env-var change on this Render
  account needs a real `/deploys` trigger, not `/restart`, to actually
  take effect on the running process.**
- **`GET /metrics` and `GET /api/v1/author_metrics` both 502 in production**
  with zero app-level log entry for either request (every other request in
  the same window, including ones fired seconds apart, does log) — strong
  evidence these two specific paths are being intercepted at Render's edge
  before the Go app ever sees them, not an application bug. Both handlers
  were read in full and run correctly locally. Needs a look in the Render
  dashboard for a reserved-path conflict or an edge/WAF rule matching
  these paths specifically.
