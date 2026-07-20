# HANDOFF

> Current-state snapshot, not history. Overwritten in place at the end of
> every session. For history, see `LOG.md`; for why a decision was made, see
> `decisions/`.

**Last updated:** 2026-07-20 13:48 IST

## What's done

**"World-class" unified plan — all 5 active phases (23 items) shipped this
session.** Two audits (functional-correctness + smooth/fast/efficient) were
merged into one plan and executed phase by phase, each item verified via
`tsc --noEmit`, a backend docker rebuild + live curl, or a real
`npm run build`, not just code inspection. Full plan:
`C:\Users\VikasVijigiri\.claude\plans\swirling-wiggling-puffin.md`.

**Phase 1 — critical breakage:**
- `/api/v1/analyze_paper` 500: `LLMService.query_openrouter()`/`query()` were
  treating an empty LLM `choices` response as success; removed a dead model
  (`openrouter/owl-alpha`) from the fallback list and made both paths raise
  on empty content so the existing fallback loop actually retries. Paper
  page now shows a real `ErrorBanner`+retry for this section instead of
  silently rendering nothing.
- Home and author pages: replaced one shared `Promise.allSettled` (whole
  section waited on the *slowest* call, measured up to 259s) with
  independent per-section fetch/loading state.
- Firestore crash: `requireDb()` threw synchronously before `onSnapshot`'s
  own error callback could register. Added `safeSubscribe()` wrapper in
  `lib/firebase/workspace.ts`, plus `apps/web/src/app/error.tsx` and
  `(app)/error.tsx` — this app had zero error boundaries before.
- Nexus chat now uses `MarkdownText` (handles `**bold**`) instead of
  `MathText`.
- `MathText.tsx` strips unrecognized tags (MathML from OpenAlex) instead of
  rendering raw tag soup; author page's `submission_tips`/`connection_path`
  wrapped in it (both carry real LaTeX).
- Author page's Connect/Message/Collaborate buttons were cosmetic
  (local-only state, no backend, reset on refresh) — now honestly disabled
  with a "Coming soon" title until real backend wiring is a separate,
  scoped feature (Phase 6).

**Phase 2 — regression + recommendation quality:**
- Landing page's 6th button-in-anchor instance (`<Link><MagneticCTA>`)
  fixed via `router.push` on click instead of nesting a button in an anchor.
- `journal_advisor` and `industry_opportunities` both self-reported LLM
  match scores with nothing grounding them (confirmed live: a 96%-match ML
  journal for a physicist; a biomedical job whose own explanation admitted
  it was a stretch). Added `score_candidates_against_profile()` to
  `embedding_service.py` — same embedding model + calibration formula as
  `daily_feed` — and wired it into both `get_journal_advisor`
  (`pipeline_services.py`) and `fetch_industry_opportunities`
  (`industry_service.py`) to recompute match scores from real cosine
  similarity instead of trusting the LLM's self-reported number. Verified
  live against a real physicist author: journal scores dropped from
  arbitrary 90+ to a grounded 70-75 range; industry opportunities now rank
  genuinely off-topic postings (Vascular Brain Health Institute, Psychology
  postdoc) below on-topic ones (Quantum faculty), where they previously all
  scored in the same inflated band.

**Phase 3 — minor UX/data:**
- Landing stats: "4 Live Collab Modes" corrected to 5 (real `workspace/[id]`
  tab count); removed the unsourced "100%" stat rather than inventing a
  replacement number.
- `syncUserProfile` failures (onboarding + profile) now `console.warn`
  instead of swallowing silently.
- Profile page now renders `useMyProfile()`'s `error` via `ErrorBanner` with
  retry (was fetched, never displayed).
- Leaderboard: deleted the leaked `"David W. Test"` fixture row from
  Postgres `researcher_metrics` (user-approved — the delete was correctly
  blocked by the auto-mode classifier as destructive, then run after
  explicit sign-off). Added `id` (openalex_id) to `LeaderboardEntry` in
  **both** the Go gateway (`services/backend-go/internal/quest/quest.go` —
  this is the live path; the Python `quest/service.py`/`schemas.py` version
  was updated too but is currently unreachable behind the gateway) and the
  frontend type; discovery page's leaderboard rows now deep-link straight
  to `/author/{id}` instead of setting a name-based search query that could
  resolve to the wrong same-initial person.
- `Input` component gained a `label`/`htmlFor` prop (via `useId()`); wired
  on login, signup, onboarding, horizon, profile — replacing several
  manually-placed, unassociated `<label>` elements.
- Nexus search results: keyboard-accessible now (`role="button"`,
  `tabIndex`, Enter/Space), plus click-outside/Escape closes the dropdown.
- `workspace/[id]`'s native `confirm()` replaced with the same inline
  confirm/cancel button pattern already used on the profile page's account
  deletion (no generic Modal component exists in this codebase — reused the
  existing convention rather than inventing one for a single call site).
- Horizon's error state got a Retry button matching the `ErrorBanner`
  pattern used elsewhere.

**Phase 4 — motion consistency + real jank fixes:**
- New `apps/web/src/lib/motion.ts`: `EASE_STANDARD`/`DURATION_FAST/NORMAL/
  SLOW`/`TRANSITION_FAST/NORMAL/SLOW`. Note: `EASE_STANDARD` ([0.22, 1,
  0.36, 1]) intentionally does *not* match `globals.css`'s `--ease-standard`
  (a different cubic-bezier) — it centralizes the value ~18 files had
  already converged on independently, not the CSS token. 28 hardcoded
  transition sites across 16 files migrated to the shared constants (3
  deliberately left as literals — durations outside the fast/normal/slow
  ranges; 10 files in the original ~27-file estimate had zero matching
  occurrences).
- `workspace/page.tsx`: create-form reveal changed from animating `height`
  to opacity/scale wrapped in `AnimatePresence`; manuscript progress bar
  changed from animating `width` to a `scaleX` transform (avoids per-frame
  layout reflow).
- ~8 conditional `{error && <ErrorBanner/>}` sites (discovery ×2, workspace,
  workspace/[id], author/[id], EquationsTab, ManuscriptTab) wrapped in
  `AnimatePresence` for a real fade instead of an instant pop.

**Phase 5 — bundle efficiency (verified via real `npm run build`, not
assumed):**
- `MathText.tsx`'s katex import is now lazy (`import("katex")` on first
  actual math segment, module-level singleton promise). Confirmed via build
  output: katex (260K) sits in its own chunk, absent from
  `build-manifest.json`'s `rootMainFiles` (the always-loaded set).
- Font `display: "swap"`: checked the actual installed Next 16.2.10 source
  (`validate-google-font-function-call.js`) — `display` already defaults to
  `'swap'` for every `next/font/google` call. No code change was needed;
  this item was already satisfied.
- Firebase tree-shaking verified (not assumed): the one chunk containing
  "firebase" is not in `rootMainFiles`; used APIs (`getAuth`, `onSnapshot`,
  `signInWithPopup`) are present, unused products (storage/messaging/
  analytics/functions) show no real bound code.

**Recommendation engine work from the prior session** (embeddings-based
`daily_feed`, dormant `/api/v1/recommendations` retirement) — unchanged,
still live; see `decisions/0003` through `0007`.

## What's NOT done / unverified

- **Phase 6 (deliberately deferred, not a regression):** real backend
  wiring for Connect/Message/Collaborate (needs an actual connections/
  messaging data model — a separately-scoped feature); Server Component
  conversion for discovery's default view and horizon's initial form
  (bigger architectural change, sequenced last on purpose).
- **Concurrent cold-compute contention** — backend container's 2-CPU limit,
  not stress-tested under real concurrent load. Needs a deliberate call
  before touching deployment resource config.
- **Firebase Web app for `apps/web`** still not registered in the
  `skolab-vvi` Firebase project — auth/Firestore features show an explicit
  "not configured" error until someone registers one in the console.
- **No browser-automation tool in this environment** — every fix this
  session was verified via `tsc`, live curl, or `npm run build`/bundle
  inspection, not actual rendered-pixel/interaction testing. The user
  should spot-check in a real browser: Phase 1's split loading states
  actually reveal independently; Phase 4's motion changes feel identical
  (just token-driven now) and reduced-motion still suppresses everything;
  Phase 5's lazy katex import doesn't flash unstyled math on math-heavy
  pages (paper detail, author's journal advisor).
- `api-contracts/openapi.yaml` still only documents a handful of the real
  endpoint surface — pre-existing, larger-scope gap, out of bounds for this
  session.

## Gotchas (see AGENTS.md for the full list)

- `services/backend`'s Docker path does **not** hot-reload — every backend
  fix needs `docker compose build web && docker compose up -d --no-deps web`
  from `services/backend` (postgres service is named `db`, not `postgres`;
  gateway service is `gateway`).
- The Go gateway (`services/backend-go`) directly implements some routes
  that also exist in the Python backend's routers (e.g. `/api/v1/
  leaderboard/:field`) — when both exist, the Go one registered in
  `main.go` is what's actually live behind the public gateway port (8080);
  the Python route becomes dead code reachable only by hitting the Python
  container's own port (8000) directly. Check `main.go`'s route table
  before assuming a Python endpoint edit is live.
- `curl` without a browser-like User-Agent gets blocked ("Automated
  scraping signatures detected") on some endpoints — pass
  `-A "Mozilla/5.0 ..."`.
- Clearing the Postgres `cache_entries` rows for a doc_id is not enough to
  force a fresh recompute — the Firestore-cached copy (up to 1h old) will
  still be served and re-seed Postgres.
- OpenAlex's `/authors?search=` is a name search, not a topic search.

## Next steps (not yet started)

1. Register a Firebase Web app for `skolab-vvi` (Firebase console — not
   agent-doable from the repo).
2. Revisit backend container CPU allocation if concurrent multi-user cold-
   compute load becomes a real scenario.
3. Decide whether Phase 6 (real Connect/Message backend, RSC conversion for
   discovery/horizon) is worth building.
4. Manual in-browser spot-check of this session's changes (see "What's NOT
   done" above) — no browser-automation tool is available in this
   environment to do it directly.
5. `api-contracts/openapi.yaml` completeness — separate, larger-scope task.
