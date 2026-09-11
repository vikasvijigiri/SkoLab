# LOG

> Chronological, append-only. Newest entry at the top. Never rewrite an
> existing entry — if something turns out to be wrong, say so in a new one.
> For current status (not history), see `HANDOFF.md`.

---

## 2026-09-11 — Close out everything pending after PR #176

Prompted directly: fix the two pre-existing `checks.yml` bugs found while
watching #176's CI, and finish anything else still pending. PR #177.

- **`checks.yml` fully fixed.** `pytest.ini` gained `pythonpath = .` —
  `tests/` has no `__init__.py`, so bare `pytest`'s default rootdir
  sys.path insertion (prepend mode) only ever added `tests/` itself, never
  `services/backend`, so `tests/conftest.py`'s `from app...` imports failed
  with `ModuleNotFoundError: No module named 'app'`. Reproduced locally
  with the bare `pytest` console-script entry point (`python -m pytest`
  masks it — `-m` always adds cwd to sys.path, which is not what
  `checks.yml`'s `pytest -q` does). Fixed with pytest's own built-in
  `pythonpath` ini option. Separately, the `slow` job's Postgres service
  was plain `postgres:16` — no pgvector extension, so `CREATE EXTENSION
  vector` failed and poisoned the whole `ci_bootstrap_db.py` bootstrap
  transaction, silently no-opping every later `CREATE TABLE` (even
  unrelated ones like `users`) while the script still printed "[ci] schema
  bootstrap ok" and exited 0. Switched to `pgvector/pgvector:pg16`.
- **`ThemeToggle` hydration mismatch fixed** — same bug class and same fix
  (`useSyncExternalStore`) as the Google-redirect flag in #176:
  `useState(initialTheme)` read `localStorage` straight into the lazy
  initializer, which SSR/the static shell can't see, causing exactly the
  live-caught mismatch from #176's HANDOFF note. New tests (component had
  none before): default rendering, a persisted theme on mount, and the
  full click-cycle including the DOM attribute and localStorage writes.
- **LLM output validation added for Horizon and Gap Finder** — the static
  audit from #176 found both grounded in real data but with nothing
  checking the LLM's output *structurally*. Horizon's parsed JSON now goes
  through `_BreakthroughContent`, a stricter internal Pydantic model
  (catches an off-contract `feasibility`, a blank narrative field, empty
  `roadmap_steps`) that falls through to the existing deterministic
  fallback on failure — no new failure path, just a stricter gate. Gap
  Finder's response now has to contain all three sections
  (`**Next Frontier**`/`**Toolkit**`/`**Logic**`) its own system prompt
  demands, or it raises (already caught gracefully by
  `researcher_worker.py`'s existing `except Exception`). 6 new tests
  against `PredictionService` directly with a mocked `LLMService.query` —
  no live LLM call or API key needed.
- **Both "couldn't verify in this environment" items from #176 are now
  actually verified.** Neither Chocolatey (`Access to the path
  '...\lib-bad' is denied` — needs admin) nor winget (silently hung on
  `msiexec` for 20+ minutes, 4s of CPU time total — almost certainly stuck
  on an unanswerable elevation prompt) could install a JDK in this
  environment; a portable Temurin 21 zip (extract, no installer) worked.
  Same for Go: a portable 1.25 zip. With both on `PATH` for the session:
  `npm run test:rules` passed 20/20 against the real Firestore emulator
  (fixed a real bug in the test's own `seedProject` helper along the way —
  it spread an explicit `editorUids: undefined` into a `setDoc` call,
  which Firestore's client SDK rejects outright; needed an actual delete
  of the key, not just spreading `undefined` over it), and
  `go vet && go build && go test ./...` were all clean for the Go gateway,
  for the first time this session.
- **Found, not fixed — a third pre-existing, unrelated flaky test**,
  surfaced only because the `pythonpath` fix let `checks.yml`'s tests run
  at all for the first time: `test_industry_academic.py::
  test_get_tieups_cache_miss_success` fails on Linux CI, passes standalone
  and in a full local Windows run, and returns a title that appears
  nowhere in its own mocks — points at global state (`LLM_LIMIT_EXCEEDED`
  in `llm_service.py`, or a cache/singleton in
  `industry_academic_service.py`) leaking across tests in a way this
  test's `@patch`es don't fully cover. Confirmed with the owner to merge
  #177 anyway rather than block on a second unrelated investigation;
  tracked as an open follow-up in `HANDOFF.md`.
- **Process note, for the record**: mid-session, a careless
  `git reset --hard` (done to move a commit that had landed directly on
  `main` by mistake onto the right branch) discarded the *uncommitted*
  portions of the ThemeToggle and LLM-validation fixes. Nothing was
  actually lost — both were rewritten from this same conversation's own
  prior tool output and re-verified identically — but the sequence should
  have been commit-everything-first, then branch surgery, not the
  reverse.
- Verified: `npx vitest run` 53/238, `tsc --noEmit` 0, `eslint .` 0,
  `pytest -q` (backend) 216 passed / 4 skipped, `ruff check .` clean,
  `npm run test:rules` 20/20, `go vet && go build && go test ./...` clean.

---

## 2026-09-11 — Smooth Google sign-in transition (no more frozen-button wait)

Prompted directly: after completing Google sign-in, the login/signup page
showed its ordinary idle "Continue with Google" button — unchanged, not
disabled, no spinner — for the several seconds `getRedirectResult()` plus
`createResearcherProfile()` actually take, then jumped straight to
`/home`/`/onboarding` with no transition. Root cause: `signInWithRedirect`
is a full browser navigation away and back; the page that receives the
redirect mounts completely fresh with no in-memory signal that a sign-in is
mid-flight.

- `markGoogleRedirectPending` / `hasGoogleRedirectPending` /
  `clearGoogleRedirectPending` (`apps/web/src/lib/firebase/auth.ts`) — a
  `sessionStorage` flag set right before `signInWithRedirect` fires, so the
  page that receives the redirect back can know, before
  `completeGoogleRedirectSignIn()` even starts, that it should show a
  loading view instead of the ordinary form.
- New `GoogleRedirectLoading` component (spinner + "Signing you in…"),
  shown via `AnimatePresence` in place of the login/signup form while the
  flag is set; falls back to the form (with an error, if any) once
  `completeGoogleRedirectSignIn()` settles.
- **Found and fixed a real hydration-mismatch bug while building this**: a
  first draft read the flag straight from a `useState(() =>
  hasGoogleRedirectPending())` lazy initializer — `sessionStorage` doesn't
  exist during Next's static/server render, so the static HTML always
  assumed "not pending" while the client's first render (where
  `sessionStorage` does exist) could assume "pending", a mismatch caught
  live by Playwright (`Error: Hydration failed...`) that a plain unit-test
  render wouldn't have surfaced. Fixed with `useSyncExternalStore` (new
  `apps/web/src/lib/hooks/useGoogleRedirectPending.ts`), which is the
  React-documented way to read a client-only source of truth without a
  server/client mismatch, and doesn't touch `useEffect` at all — sidesteps
  this repo's `react-hooks/set-state-in-effect` hard-error rule, which a
  second draft (setting state synchronously in the effect body) tripped
  twice before landing here.
- `GoogleSignInButton` now shows a real spinner (`Connecting to Google…`)
  while loading instead of just dimming — extracted the spinner out of
  `Button.tsx` into a shared `components/ui/Spinner.tsx` so both buttons
  (and future ones) use the same one.
- Found, while adding the first tests that ever exercised
  `@/lib/firebase/errors.ts`, that `@sentry/nextjs` crashes on import under
  this project's jsdom/Windows test environment (vendored
  `@apm-js-collab/code-transformer-bundler-plugins` throws `The URL must be
  of scheme file`) — a latent gap nothing had hit before since no test
  previously imported that module. Fixed at the shared level:
  `@sentry/nextjs` is now mocked (`captureException` only, which is all any
  caller uses) in `apps/web/src/test/setup.ts`, not worked around locally.
- Verified: `npx vitest run` 52 files / 235 tests, `tsc --noEmit` 0,
  `eslint .` 0, `next build` 0 (`/login` and `/signup` still statically
  prerendered). Live-verified in the real dev server with Playwright: set
  the pending flag via `sessionStorage`, reloaded, confirmed zero console
  errors (the hydration bug is what a first live check caught, before the
  `useSyncExternalStore` fix — a plain `npm test` pass alone would have
  shipped that bug, since Vitest/jsdom never triggers React's real
  server/client reconciliation the way an actual `next build` + browser
  load does).

---

## 2026-09-11 — Firestore security rules + Discovery→CoLab match bridge

Prompted by a market-research pass (competitive landscape for SkoLab's CoLab
bet) that flagged two things: no `firestore.rules` file anywhere in the repo
(access control was whatever's in the Firebase console, unversioned), and
that no discovery tool in the market lets a fit-based match become a working
collaboration in one click — the exact gap CoLab is positioned to close, but
wasn't wired end-to-end.

- **`firestore.rules`** (repo root) — first version-controlled access
  control for `researchers/{uid}` (self-write only) and
  `collabs_groups/{projectId}` (owner/editor/reviewer/viewer, matching the
  Overleaf-style role model already in `workspace.ts`). `deriveRoleArrays()`
  in `apps/web/src/lib/firebase/workspace.ts` now persists `editorUids` /
  `commenterUids` alongside `memberUids` so the rules language (no
  array-of-object predicate search) can express role checks. Legacy
  projects without the new fields fall back to "any member may edit" —
  today's de facto behavior, not a new restriction. Decision `0018`.
  **Not deployed** — needs `firebase login` (owner's credentials) then
  `firebase deploy --only firestore:rules`; `npm run test:rules` needs Java
  for the Firestore emulator, neither of which this environment had. The
  owner explicitly chose this over building a REST layer (which
  `decisions/0004` already rejected once) after being asked directly.
- **"Start a project with this match"** — `ResearcherCard` (Discovery) now
  has a click action that routes to `/workspace?withResearcher=<openAlexId>
  &withResearcherName=<name>`; the CoLab list page pre-fills and auto-opens
  the create form, and on creation resolves the match to a SkoLab account by
  `openAlexId` (`findResearcherByOpenAlexId`, new) — invites them as editor
  if found, or creates the project anyway with an honest "no SkoLab account
  yet" status if not. New `apps/web/src/lib/firebase/workspace.test.ts`
  (workspace.ts had zero tests before this); found and fixed a real gap in
  the shared Firestore test double while writing it — `setDoc` was never
  mocked, so `createProject`'s document-seeding call was silently
  untestable.
- **Verified live**, not just in tests: ran `npm run dev:web` against the
  real `skolab-vvi` Firebase project (Go gateway not runnable in this
  environment — no Go toolchain — so gateway-dependent surfaces degrade to
  their documented empty/error states, confirmed working, not crashing),
  signed up two real test accounts (`ada.verify.skolab@mailinator.com`,
  `marie.verify.skolab@mailinator.com`), ran onboarding both ways (full
  click-through and "Skip for now"), browsed Discovery's live OpenAlex
  fit-grid, clicked "Start a project" on a real match (Rod Ellis,
  `A5034271321`), created the project, confirmed the no-account message,
  sent a chat message and added a task (both round-tripped through real
  Firestore), then invited the second account by email, confirmed
  "added as editor", changed their role to viewer, and reloaded to confirm
  the role change persisted server-side. All of this ran against whatever
  Firestore rules existed in production *before* this change — the new
  `firestore.rules` has not been deployed, so this verifies the feature
  logic, not yet the new access control. Left both test accounts and the
  "Rod Ellis collaboration" project in production `skolab-vvi` for the owner
  to inspect or delete.
- Found, not fixed (flagged, out of scope for this pass): a pre-existing
  hydration mismatch in `ThemeToggle` on the landing page (server renders a
  different icon than the client) — unrelated to this work, first noticed
  during live verification.
- Verified: `npx vitest run` (49 files / 222 tests, up from 218), `tsc
  --noEmit` 0, `eslint .` 0, `next build` 0 (`/workspace` still statically
  prerendered — `useSearchParams` didn't force full dynamic rendering).
  `react-hooks/set-state-in-effect` (hard error in this repo's eslint
  config) caught a real anti-pattern in the first draft of the pre-fill
  logic — fixed by deriving initial state from the URL via lazy `useState`
  initializers instead of `useEffect` + `setState`.
- Decision `0018`. README's stale "Firebase not registered" / "no REST
  backend" framing corrected (the Firebase Web app has actually been
  registered since 2026-09-02 — the README and `decisions/0004`'s "Known
  gap" note were both out of date; `decisions/0004`'s note itself is left
  alone per the append-only rule, corrected here instead).

---

## 2026-09-10 — Discovery fit-first collaborator finder

Shipped via PR #119 (merge commit `cff6997`), merged to `main` and synced;
feature branch `feat/discovery-fit-first` deleted. Squash base `206e845`.

- Discovery's "Top researchers in <field>" list is now a fit-first collaborator
  finder: defaults to the signed-in user's resolved subfield, ranks by a
  no-embeddings fit score (concept Jaccard + institution + topical focus), and
  shows per-researcher signals — activity/liveness dot, momentum sparkline,
  career stage, active decades, topical focus, field-normalised standing,
  ORCID-verified, and a Wikidata-`P570`-verified "in memoriam" mark.
- All in the `apps/web` Next.js + OpenAlex route-handler layer — no Go/Python
  change (no toolchain on this box, `decisions/0009`). New pure modules under
  `lib/discovery/` with every threshold in one env-overridable `config.ts`;
  two `app/api/enrich/*` routes (Wikidata deaths, collab-flag seam).
- Found + fixed en route: `/authors` has no `topics.subfield.id`/`topics.field.id`
  filter, so the route now expands a node to its `topics.id` list — this also
  repaired the previously-400ing researcher drilldown.
- Click-only invariant kept (no `<input>`); topical focus is a 4-stop segmented
  control, connection distance a "shares your institution" proxy (real
  2nd-degree + an embedding fit term deferred). "Open to collaboration" is a
  typed seam, empty until professors self-declare.
- Plan `docs/plans/2026-09-10-discovery-fit-first.md`; decision `0014`.
- Verified: tsc 0, lint 0, vitest 39 files / 160 tests, check:contrast pass,
  Playwright `discovery-researchers` + `rollout-visual` 10/10 (axe AA clean),
  `next build` 0. No CI exists for this repo; branch protection unreadable on
  the free private repo (advisory only).

---

## 2026-09-08 — Agent (Claude)

Adopted UI/UX skills from the Notion "Web App Factory" page and pivoted the web
identity. Branch `feat/instrument-frontend`; `7844a28` `7616035` `8f6151e`.

- Chose **one** new skill `frontend-ui` (build + audit UI vs `DESIGN.md` + a
  WCAG floor) over three: the Notion page's own EVOHARNESSBENCH note warns
  more skills cause routing conflicts. The other two sources became reference
  files. `vercel-labs` content was reproduced by hand because the
  `deploy-spend-guard` hook blocks any command naming a cloud vendor, so
  `git clone …vercel…` is denied.
- `DESIGN.md` C → B was a product-owner **positioning** call — the exact switch
  condition direction C had named for itself (`decisions/0012`). Light theme
  kept as a first-class override.
- Phase C confirmed the token architecture is the real leverage: `globals.css`
  dark-as-base plus new values carried the pivot with only targeted
  per-component edits. Performance was audited against the new rules and found
  **nothing safe to change unmeasured** — no barrels, no await waterfalls,
  bounded result sets.
- One **pre-existing** e2e failure (`smoke.spec.ts:13`) was verified against
  the stashed pre-change tree — not a regression, untouched.

Not done: formal `testing`/`code-review` skill passes (all gates run + quoted
inline), and the branch is unmerged with no remote.

---

## 2026-08-18 — Agent (Claude)

Merged the 11-commit `fix/similar-authors-shape-hardening` branch to `main`
via PR, and cut the repository's first tag.

Before merging, found that the working tree's three uncommitted edits were
not work but **regressions**: the global layer bootstrap had re-installed
stock copies of `ruff.toml`, `tools/test_ci_shape.py` and
`tools/test_package.py` over the fixes committed in `c7aa5f9` and `c50455a`.
Proved it by running the tests against the working tree (`test_ci_shape` ->
1 failed on `ci.yml references requirements.txt`; `test_package` -> 2 failed
on "does not appear to be a Python project") and again against `HEAD`
(both green). Stashed rather than discarded, so they stay recoverable:
`stash@{0}`.

Verification actually run, not assumed:
- `python tools/run_checks.py` -> `PASS: 30 check(s) green (lint, test, typecheck)`
- `cd services/backend-go && go vet ./... && go test ./...` -> all packages `ok`
- `pytest services/backend/tests/` -> `85 passed, 6 errors` — the six are a
  pre-existing Python 3.14 event-loop teardown incompatibility in
  `test_threat_modeling.py`, unchanged by this branch and green on CI's
  pinned 3.10. Recorded in `ISSUES.md` rather than papered over.

`fix/empty-connections-fallback` needed nothing — it is zero commits ahead of
`main` and was left in place rather than deleted.

Not done: `mcp.json` (untracked, added 2026-08-09) was left untracked. Claude
Code reads `.mcp.json`, not `mcp.json`, so as it stands the file is inert;
renaming it is the owner's call, not a merge-time side effect.

---

## 2026-07-21 17:09 — Agent (Claude)

Implemented the MCP/hooks/skills set recommended earlier in the session:
`postgres` and `playwright` MCP servers (committed, `.mcp.json`), `grafana`
MCP server (local scope only — needs a live service-account token, unlike
Postgres's already-public dev password), a PreToolUse hook blocking raw
`gradlew` (the π-path Gradle crash from `AGENTS.md`), a PostToolUse hook
reminding about the `skolab_python_ai` docker-rebuild step, and two skills
(`backend-rebuild-verify`, `android-build`).

Verified every piece for real rather than trusting config: started
`./gradlew --version` and watched the hook block it; edited a
`services/backend` file and captured the reminder hook's stdin payload;
confirmed Postgres connectivity/credentials with a live `psql` query (20
tables); stood up the `infrastructure/` Prometheus+Alertmanager+Grafana+Loki
stack (user's explicit go-ahead, since that dir is normally off-limits) and
called the Grafana MCP's `query_prometheus` tool over raw JSON-RPC, getting
back real data (`up{job="skolab-backend"}=1`); called Playwright MCP's
`browser_navigate` the same way and got back page title "SkoLab" from the
real running dev server.

Hard blocker hit: newly-added `.mcp.json`/`.claude/skills` entries don't
attach to an already-running Claude Code session — `claude mcp list` still
shows all three servers "Pending approval" even after writing
`enabledMcpjsonServers` directly into `~/.claude.json`, and both `ToolSearch`
and `Skill` fail to find the new entries. The two hooks, by contrast, picked
up live with no restart (settings.json hooks apparently reload without a
restart; MCP/skill registration does not). Everything is proven correct one
layer below the Claude tool surface; the final loop — actually calling these
as native tools — needs a session restart to close out. See `HANDOFF.md` for
the full punch list.

---

## 2026-07-21 — Agent (Codex)

Added a portable repo-level agent contract for cross-tool workflow rules:
`docs/agent-contract.md` captures the shared reading order, portable
behavior rules, session-end expectations, and what remains tool-specific
(MCP servers, skills, plugins, global hooks). Linked it from `README.md`
and clarified the same boundary in `AGENTS.md` so Claude, Codex, and future
agents have a repo-visible source of truth instead of relying on client-only
settings.

---
## 2026-07-21 14:54 — Agent (Claude)

Two related pieces of work: grounding Journal Advisor in real data (planned
and executed first), then a much larger backend personalization/correctness
audit prompted by the user asking to check "literally every backend service
line by line" and make everything "revolve around the user."

**Journal Advisor**: root-caused the garbled-LaTeX bug the user screenshotted
back to the LLM being asked to write free-text "submission tips" for
completely invented journals — not a frontend rendering bug (confirmed via
sandboxed tests that `MathText.tsx`'s own repair/KaTeX pipeline handles bare,
mixed, and fully-escaped LaTeX correctly). Fixed at the root: real OpenAlex
journals via a new `search_sources()`, `type == "journal"` filter (an
unfiltered first pass surfaced "Open Science Framework" and a funding-agency
repository as "journals"), no size-based sort (an early version let *Science*/
*PLoS ONE* dominate every query regardless of topic — confirmed live a
physicist and an unrelated researcher got identical top-3 results before this
fix), and LLM used only for a short no-LaTeX rationale.

**Personalization audit**: a 3-pass Explore audit mapped every user-facing
recommendation path in the backend and scored each on how genuinely grounded
it is in the actual requesting researcher vs. generic/hallucinated. Found and
fixed: hardcoded fake grants (`match_grants`), Horizon predictions ignoring
who's asking entirely, a fabricated-coauthor fallback, a real cross-user chat-
history bug (`chat_with_author` keyed off a hardcoded shared literal user_id),
a conjecture prompt fed raw dict noise instead of a reconstructed abstract,
a collaborator-synergy feature inventing proposals from concept tags alone,
and the same `x_concepts`-is-empty bug (confirmed live: this field is
basically deprecated/empty on current OpenAlex author objects) independently
present in three different services. Also discovered a real, working
per-user "digital twin" foundation already exists (`user_memory_service`,
Postgres-backed activity tracking + LLM bio summary) but is wired up for
Android only — flagged and deliberately deferred building the web-side
equivalent, per the user's explicit call, rather than scope-creeping it in.

Extracted a shared `app/services/ai/user_context.py` module partway through
(needed for the Horizon fix, then reused for two more of the audit's fixes)
instead of writing a fourth slightly-different copy of "resolve this
researcher's concepts and recent-paper keywords" — this is exactly the kind
of drift that caused the `x_concepts` bug to exist in three places
independently.

Also persisted a previously-orphaned `skills`/`tools` LLM-derived field
(existed server-side, Android-only, never saved) to two new
`researcher_metrics` columns, and added a distinct "Research Areas & Skills"
section to the profile page — verified end-to-end with a real enrichment run
(real skills/tools computed and correctly served back through
`search_author`).

Found (not caused) something worth a permanent note: `PgBackedCache` stores
keys as `"{name}::{key}"`, not bare — cost real time this session hunting a
"stale cache" ghost that was actually just a wrong `DELETE` query never
matching the real row. Documented in `HANDOFF.md`'s gotchas.

Diff: uncommitted at time of writing (spans `services/backend` extensively,
smaller touches in `apps/web`).

---

## 2026-07-20 13:48 — Agent (Claude)

Ran two full-app audits (functional-correctness across every page/screen;
separately, a smooth/fast/efficient pass on animation consistency, data-
fetching, and bundle size) via parallel Explore agents, then merged both
into one 6-phase, 23-item plan and executed it phase by phase — the
largest single-session change set so far. Highlights:

- Root-caused and fixed a real production bug: `/api/v1/analyze_paper` 500s
  came from `LLMService` treating an empty OpenRouter `choices` response as
  a successful result, combined with a dead model first in the fallback
  list. Both paths now raise on empty content so the fallback loop actually
  works.
- Fixed a real perf bug, not just a UX one: home/author pages gated 5
  independent backend calls behind one `Promise.allSettled`, so the whole
  section waited on the single slowest call (measured up to 259s) even
  though most resolved in seconds. Split into independent per-section
  state.
- Added `score_candidates_against_profile()` to `embedding_service.py` and
  wired it into `journal_advisor`/`industry_opportunities` — both had been
  trusting the LLM's/scraper's self-reported match score with nothing
  grounding it against the researcher's actual profile. Verified live: a
  96%-match ML journal for a physicist dropped to a grounded 70-75%; a
  contradictory-explanation biomedical job now correctly ranks below
  genuinely on-topic postings instead of scoring the same as them.
- Found (via `main.go`) that the leaderboard's live route is the Go
  gateway's own SQL implementation, not the Python one both `router.py`
  and `main.go` register — the Python leaderboard code is effectively dead
  behind the public gateway. Fixed the `id`-field addition and the leaked
  `"David W. Test"` Postgres fixture row (deleted after explicit user
  approval — the auto-mode classifier correctly blocked the first,
  unapproved attempt) on the side that's actually live.
- Built `apps/web/src/lib/motion.ts` as the JS-side counterpart to
  `globals.css`'s motion tokens and delegated the ~35-site mechanical
  migration to a background agent while working Phase 4/5 items that
  didn't touch the same files in parallel — no merge conflicts, verified
  by re-running `tsc --noEmit` after both landed.
- Phase 5 bundle claims were verified against real `next build` output, not
  assumed: katex's chunk is confirmed absent from `rootMainFiles`; firebase
  tree-shaking confirmed via chunk contents (used APIs present, unused
  products' actual code absent, only stray string literals). Font
  `display: "swap"` turned out to already be Next 16.2.10's default —
  caught by checking the installed source before adding a no-op prop.

Diff: uncommitted at time of writing (large — spans `apps/web`,
`services/backend`, `services/backend-go`).

---

## 2026-07-19 21:20 — Agent (Claude)

Worked the two remaining `HANDOFF.md` items that were actually code-fixable
(the other two — CPU allocation, Firebase Web app registration — need a
human decision/console action, confirmed out of scope for this pass).

Retired the dormant `/api/v1/recommendations` unified endpoint: confirmed
via grep across both clients that neither ever called it, removed
`get_unified_recommendations` + its 3 builders from `service.py`, the
unified route from `router.py`, 6 now-fully-unused `engine.py` techniques
+ their schemas/tests — while keeping the live `/peers`/`/peers/invite`/
`/peers/check-registered` routes and the `cosine_similarity`/`mmr_diversify`
functions still imported by `pipeline_services.py`. Recorded as
`decisions/0007-retire-dormant-unified-recommendations.md`.

Documented `POST /api/v1/daily_feed/dismiss` in `api-contracts/openapi.yaml`
— the specific gap the new docs-sync hook caught. Left the broader
pre-existing gap (openapi.yaml only covers a handful of the real endpoint
surface) as a separate, larger-scope task per the plan's agreed boundary.

Diff: uncommitted at time of writing.

---

## 2026-07-17 14:45 — Agent (Claude)

Built the global docs-sync Stop hook (`~/.claude/hooks/check_docs_sync.py`,
5 new mtime-comparison rules: decisions/ index, dependency manifests, infra
files, env templates, API routes vs. `api-contracts/openapi.yaml` — all
gated on their reference file existing, so they no-op in other repos), plus
a "Keeping docs in sync" checklist section in `AGENTS.md` for the judgment
calls a hook can't make, and a "Project Documentation" pointer table in
`README.md`. Verified live: the hook's first real run caught 3 genuine
things, including a real pre-existing gap (the `/daily_feed/dismiss`
endpoint was never added to `openapi.yaml`).

Then worked the `HANDOFF.md` "unverified" list: re-verified the desktop-
native redesign rollout page-by-page against the original plan (confirmed
Phases 1-3 fully shipped) and found one real gap — `next.config.ts`'s
`experimental.viewTransition` flag was never added (verified against the
installed Next 16.2.10 docs before fixing, not assumed). Fixed it; Phase 4
(cross-page view-transition motion) itself was never started and remains a
deferred, optional-polish decision. The other three "unverified" items
(dormant `/api/v1/recommendations` endpoint, backend CPU allocation,
Firebase Web app registration) each need a decision or action from the repo
owner, not just more agent investigation — surfaced back to them rather
than picked unilaterally.

Diff: uncommitted at time of writing.

---

## 2026-07-17 00:52 — Agent (Claude)

Added the repo's core doc set: `PLAN.md` (retrospective founding plan),
`AGENTS.md` (agent cold-start guide) + `CLAUDE.md` (`@AGENTS.md` import,
matching the existing `apps/web/CLAUDE.md` convention), `HANDOFF.md`
(current-state snapshot), `decisions/` (6 ADRs covering the caching split,
the Go/Python service split, the self-hosted-embeddings call, the
Firebase-for-CoLab/Profile call, and two decisions from the most recent
recommendation-engine session), and this file. Requested by the repo owner
to formalize documentation practice going forward.

---

## 2026-07-14 ~11:00 IST — Agent (Claude) + human (Vikas)

Continued the recommendation-engine session below:
- Root-caused an embedding-latency bug: `sentence-transformers` pads every
  text in a batch to the length of the longest one, so full-length abstracts
  among ~150 candidates were inflating batches from single-digit seconds to
  80-180s. Fixed via candidate-text truncation (300 chars) and moving the
  CPU-bound `.encode()` call off the event loop (`asyncio.to_thread`).
  See `decisions/0003-self-hosted-embeddings.md`.
- Found and fixed the similar-researchers channel misusing OpenAlex's
  author-name search on topic phrases (returned nothing/garbage); rebuilt it
  to derive peers from authorships of already-matched papers instead.
  See `decisions/0005-similar-researchers-via-authorship.md`.
- Decoupled the displayed match % from the pool-relative ranking score,
  fixing an artificial "96% then a cliff to 80%/66%" pattern.
  See `decisions/0006-decoupled-ranking-vs-display-score.md`.
- Fixed a transient paper-detail-page 404 by adding retry affordance to
  `ErrorBanner` and wiring it into `paper/[id]/page.tsx` — the underlying
  route was confirmed working; there was previously no way to recover from
  a transient failure without manual navigation.

Diff: squashed into [`26d37e2`](https://github.com/NG-VikasV/ResQit/commit/26d37e2ab3458bb88a514570dbe6ff328ffc168a).

---

## 2026-07-13 ~13:00 IST — Agent (Claude) + human (Vikas)

Recommendation engine overhaul, prompted by a user complaint that daily-feed
recommendations for a condensed-matter-physics researcher were surfacing
unrelated papers (e.g. ML papers, then later a solar-cell/materials paper).
Traced through several distinct root causes across the session:
- Replaced naive bag-of-words similarity with self-hosted `bge-small`
  embeddings (`app/services/ai/embedding_service.py`).
  See `decisions/0003-self-hosted-embeddings.md`.
- Fixed a mean-centering bug causing every candidate to score ~97% regardless
  of true relevance (bge's raw cosine similarity has a high anisotropic
  floor); added pool-mean-centering to restore real discrimination.
- Found the `is_relevant` hard-gate was driven by a disconnected, overly
  generic keyword-substring matcher (`is_work_relevant_to_discipline`)
  instead of the actual embedding signal — this is what let an unrelated
  solar-cell paper through despite good embedding infrastructure already
  being in place. Switched the gate to use the embedding signal directly.
- Broadened candidate retrieval (more search terms, LLM-extracted
  recent-paper keywords now actually drive search instead of only feeding
  the embedding text) and added the similar-researchers candidate channel
  (later fixed on 07-14, see above).
- Added a per-author dismiss/feedback loop (`POST /api/v1/daily_feed/dismiss`)
  with frontend wiring in `PulseFeedCard`.

Diff: squashed into [`26d37e2`](https://github.com/NG-VikasV/ResQit/commit/26d37e2ab3458bb88a514570dbe6ff328ffc168a).

---

## 2026-06-30 17:37 — Human (Vikas)

Android: harmonized typography to Space Grotesk (Display/Syne), Inter (Body),
JetBrains Mono (Metrics); fixed `HomeTopWidget` fallback to global trending
papers when user memory is empty; `run-app.ps1` now resolves the ABI-split
APK dynamically.

Diff: [`a7a578b`](https://github.com/NG-VikasV/ResQit/commit/a7a578b), [`f74663e`](https://github.com/NG-VikasV/ResQit/commit/f74663e).

---

## 2026-06-30 16:05–16:16 — Human (Vikas)

Android APK size/perf pass: R8 resource shrinking, ABI splits, tighter
ProGuard rules, packaging exclusions; removed unused `appcompat`/legacy
Material dependencies (~1.2MB saved); added centralized `SkoLabIconSize` /
`SkoLabIconButtonSize` / `SkoLabRadius` / `SkoLabFontSize` design tokens.

Diff: [`9da7411`](https://github.com/NG-VikasV/ResQit/commit/9da7411)..[`1ff59c7`](https://github.com/NG-VikasV/ResQit/commit/1ff59c7).

---

## 2026-06-30 15:32–15:58 — Human (Vikas)

Android contacts/invite flow: native SMS/Email invite intents, backend
recommendation-log triggers for unregistered contacts, simulated academic/
Gmail contact sync, registered-contacts-first sorting, single toggle-state
add/remove button, `LazyColumn` perf (stable keys, memoized regex); migrated
check-registered logic to a high-speed Postgres endpoint.

Diff: [`b7fe563`](https://github.com/NG-VikasV/ResQit/commit/b7fe563)..[`c7ac19d`](https://github.com/NG-VikasV/ResQit/commit/c7ac19d).

