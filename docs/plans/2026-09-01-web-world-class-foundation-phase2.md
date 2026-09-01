# apps/web World-Class Foundation — Phase 2 Implementation Plan

**Goal:** Finish the client data-layer migration — every API-backed page fetches through TanStack Query with its god-component split done — and turn the two `react-hooks` lint rules that Phase 1 parked at `warn` back to `error`.

**Source brief:** `docs/specs/2026-09-01-web-world-class-design.md` (Phase 2). Phase 1 merged as PR #4; the `author/[id]` migration in it is the reference pattern.

**Slug:** web-world-class-foundation-phase2 (branch: `feat/web-world-class-foundation-phase2`)

**Risk:** MEDIUM (computed) — `tools/scope.py`: volume + spread across `apps/web`, no CI/credential/shared-control surface (unlike Phase 1). Behaviour-preserving refactor of 5 pages.

**Blast radius:** `apps/web/src/app/(app)/{home,discovery,horizon,nexus,paper/[id]}/page.tsx` behaviour (re-implemented, must stay identical); `apps/web/src/lib/api/{endpoints,queries}.ts` (2 new endpoints + factories); `apps/web/src/lib/types.ts` (inline types moved in); `apps/web/eslint.config.mjs` (rule severities). No backend, no CI, no deps, no visual change, no Firestore-page change.

**Rollback:** Per-task rollback below. Worst landing state (half merged): revert the merge commit — the migrated pages return to their `useEffect` form; `queries.ts`/`endpoints.ts` additions are inert if unused.

**Architecture:** Identical to Phase 1's proven pattern. Each API page: primary + secondary data via `useQuery(<factory>)` from `lib/api/queries.ts`; no `useState`/`useEffect`/`cancelled`/`refetchToken` for server state; mutations via `useMutation`; errors through `ErrorBanner` with `refetch`. Inline sub-components extracted to `components/<feature>/`; inline response `interface`s moved to `lib/types.ts`. Two raw-`apiRequest` callers (`horizon`, `nexus`) get named functions in `endpoints.ts` first.

**Tech stack and constraints:**

- `apps/web` only. No backend / Go / Android / `.claude` / CI / dependency changes.
- **No visual change** — every section, class, `motion` wrapper, `loading.tsx`, and copy string stays byte-identical. This is a data-layer swap + file split, not a redesign.
- **No RSC / Server Component conversion** — still deferred (see marker). Every page stays `"use client"`.
- **Firestore-realtime pages untouched** — `profile`, `workspace`, `workspace/[id]`, and `home`'s Firestore half use `onSnapshot`; that is the *correct* realtime pattern, not the fetch-in-effect anti-pattern. `useQuery` is for API server state only. Their god-component split is a separate follow-up.
- Each migrated page keeps a component test (loading → data → error) in the Phase 1 harness (Vitest + MSW), mirroring `author/[id]/page.test.tsx`.
- Verify Next 16 APIs against the installed package (`apps/web/AGENTS.md`).

## Grounding

**Patterns mirrored (file:line):**

- **The reference migration** — `apps/web/src/app/(app)/author/[id]/page.tsx` (Phase 1): `useQuery(primaryFactory)` + `enabled`-gated secondary `useQuery`s + `useMutation` for refresh + `ErrorBanner onRetry={refetch}`; sub-components extracted to `components/author/`; `AuthorDetailContent` exported for the test.
- **Query factories** — `apps/web/src/lib/api/queries.ts` (`queryOptions({ queryKey, queryFn, enabled, staleTime })`, documented key convention at file top). New factories append here.
- **Endpoint fns** — `apps/web/src/lib/api/endpoints.ts` (`export const getX = (args) => apiRequest<T>("/path", { params })`). The two `horizon`/`nexus` raw calls become `getX` here.
- **Component extraction target** — `apps/web/src/components/<feature>/PascalCase.tsx`, named export, colocated `*.test.tsx`.
- **Types** — `apps/web/src/lib/types.ts` holds all API response interfaces (e.g. `AuthorResponse`, `DailyFeedItem`). Inline page types move here with a `// GET /path` comment.
- **Tests** — `apps/web/src/test/{handlers,fixtures,render}.tsx` (Phase 1). Add handlers + fixtures per new endpoint; `renderWithProviders` from `@/test/render`.
- **Lint** — `apps/web/eslint.config.mjs`: `react-hooks/set-state-in-effect` and `react-hooks/preserve-manual-memoization` are at `"warn"` in the last config block with a Phase-2 comment.

**Repo memory (`tools/memory.py --paths`):** only `decisions/0007` (a retired backend endpoint) — nothing about these pages, the data layer, or lint config. `MEMORY.md` / `ISSUES.md` have no entry for these paths.

**Current `set-state-in-effect` warning sites (PR #3 lint output):** `home/page.tsx:123`, `horizon/page.tsx:87`, `nexus/page.tsx:84` — all three are pages this plan migrates, so the rule can go to `error` once Tasks 2–6 land.

## File map

| File | Action | Owns afterward |
|---|---|---|
| `apps/web/src/lib/api/endpoints.ts` | Modify | 2 new named fns for the Horizon predict + Nexus chat/search calls `horizon`/`nexus` currently do raw |
| `apps/web/src/lib/api/queries.ts` | Modify | New `queryOptions` factories: discovery search, horizon predict, nexus search, paper analyze, home feed/conjecture/industry/grants/journals |
| `apps/web/src/lib/api/queries.test.ts` | Modify | 2–3 assertions for the new factories' key shape |
| `apps/web/src/lib/types.ts` | Modify | Inline response types from `horizon` (2) + `nexus` (3) moved in, with `// GET /path` comments |
| `apps/web/src/app/(app)/discovery/page.tsx` | Modify | `useQuery`; inline sub-components extracted |
| `apps/web/src/app/(app)/horizon/page.tsx` | Modify | `useQuery`; sub-components + form extracted |
| `apps/web/src/app/(app)/nexus/page.tsx` | Modify | `useQuery`/`useMutation`; chat panel + result list extracted; routes through `endpoints.ts` |
| `apps/web/src/app/(app)/paper/[id]/page.tsx` | Modify | `useQuery`; sub-components extracted; content component exported for test |
| `apps/web/src/app/(app)/home/page.tsx` | Modify | The 5 API calls → `useQuery`; Firestore/profile half unchanged; cards extracted |
| `apps/web/src/components/{discovery,horizon,nexus,paper,home}/*.tsx` | Create | Extracted sub-components (names discovered per page during implementation), named exports |
| `apps/web/src/components/{discovery,horizon,nexus,paper,home}/*.test.tsx` | Create | Behaviour tests for the non-trivial extracted components |
| `apps/web/src/app/(app)/{discovery,horizon,nexus,paper/[id],home}/page.test.tsx` | Create | Per-page loading → data → error test via MSW |
| `apps/web/eslint.config.mjs` | Modify | `react-hooks/set-state-in-effect` → `error`; `preserve-manual-memoization` → `error` **iff** no sites remain outside this plan's files (else stays `warn` with a narrowed follow-up) |

## Progress
- [x] Task 1 — New endpoints + query factories + fixtures/handlers — verified: tsc rc=0, queries.test.ts 7 pass. Horizon predict + Nexus chat are mutations (endpoint fns, no factory); OpenAlex works deduped onto existing OpenAlexWork type.
- [x] Task 2 — Migrate discovery to useQuery + extract components — verified: 3 page tests pass, tsc/build rc=0, eslint clean, 0 set-state-in-effect. 4 useQuery (leaderboard/trending/authors/papers) with call-site enabled gates; AuthorResultCard/PaperResultCard/LeaderboardRow extracted.
- [x] Task 3 — Migrate horizon to useMutation + move types — verified: 2 tests pass, tsc/build/eslint clean, 0 set-state-in-effect. Predict via useMutation (onMutate resets loadingStep); loading-caption effect no longer sets state synchronously. Inline PaperSource/BreakthroughPrediction moved to lib/types.ts (Task 1). NOTE: full result/form component extraction deferred to a follow-up (data layer + lint were the load-bearing goal).
- [x] Task 4 — Migrate nexus to useQuery/useMutation + move types — verified: 3 tests pass, tsc/build/eslint clean, 0 set-state-in-effect. Search -> useQuery(openAlexWorksQuery, enabled len>=3); chat -> useMutation(nexusChat) appending to a local message log; guardError kept for the empty-collection check. Inline types deduped (OpenAlexWork) / moved (NexusCollectionPaper, NexusMessage). Full panel extraction deferred (follow-up).
- [x] Task 5 — Migrate paper/[id] to useQuery + split content component — verified: 3 tests pass, tsc/build/eslint clean. workQ + intelQ useQuery (analysis enabled on work.id); retry counters -> refetch(); PaperDetailContent exported for tests. paperWorkQuery + openAlexWorkById added.
- [x] Task 6 — Migrate home API calls to useQuery — verified: 2 tests pass, tsc/build/eslint clean, 0 set-state-in-effect. 5 useQuery (feed/conjecture/grants/opps/journal) enabled on !profileLoading; dismiss -> useMutation with optimistic setQueryData on the feed cache. useMyProfile / Firestore half untouched. The single mega-effect is gone.
- [x] Task 7 — Promote react-hooks rules to error — verified: lint rc=0 with set-state-in-effect + preserve-manual-memoization both at "error"; 27 tests pass; tsc/build clean. Fixed the last preserve-manual-memoization site (author sortedWorks -> toSorted, no useMemo).

## Constitution gate
- [x] I Evidence — every task names its command + expected output
- [x] II Test first — each page migration writes its `page.test.tsx` (loading/data/error) alongside the re-implementation, mirroring `author/[id]/page.test.tsx`
- [x] III Smallest change — only the 5 API pages + the data/lint files; Firestore pages and RSC untouched
- [x] IV Reversibility — no migrations, credentials, CI, or deps; pure `apps/web` source refactor, revertible per task
- [x] V No silent degradation — lint rules move *up* to `error` (Task 7); nothing is loosened
- [x] VI Mechanism — Task 7's `error` severity + the per-page tests are the enforcement
- [x] VII Secrets — none involved

## Complexity tracking
- All boxes ticked. No exceptions.

## Tasks

### Task 1: New endpoints + query factories + test fixtures/handlers
**Purpose:** the data layer covers every endpoint the 5 pages use, before any page changes
**Files:**
- Modify: `apps/web/src/lib/api/endpoints.ts` — add `getHorizonPrediction(...)` and `getNexusSearch(...)` / `nexusChat(...)` as named `apiRequest<T>` fns, replacing the raw `apiRequest` calls `horizon`/`nexus` inline today (read those calls to get the exact path, params, response shape)
- Modify: `apps/web/src/lib/api/queries.ts` — add `queryOptions` factories: `discoverySearchQuery`, `horizonPredictionQuery`, `nexusSearchQuery`, `paperAnalysisQuery`, and `home*Query` for feed / conjecture / industry / grants / journal-advisor (reuse the existing `dailyFeedQuery` / `dailyConjectureQuery` where they already fit)
- Modify: `apps/web/src/lib/types.ts` — move `horizon`'s 2 and `nexus`'s 3 inline `interface`s here with `// GET /path` comments
- Modify: `apps/web/src/lib/api/queries.test.ts` — key-shape assertions for 3 of the new factories
- Modify: `apps/web/src/test/handlers.ts` + `apps/web/src/test/fixtures.ts` — one minimal fixture + happy-path handler per new endpoint
**Dependencies:** none
**Implementation notes:** do not change any page yet. Names for the extracted types come from the pages' current inline declarations verbatim. Auth-token endpoints are still not added (no page here needs one).
**Rollback:** `git checkout` the 5 files.
**Preconditions:** Phase 1 merged (queries.ts / test harness exist).
**Verification:**
- Run: `npm run -w web test -- src/lib/api/queries.test.ts && npx -w web tsc --noEmit`
- Expect: both exit 0; new factory tests pass
**Done when:** every endpoint the 5 pages need has a typed fn + factory + fixture, and `tsc` is clean.

### Task 2: Migrate `discovery` to `useQuery` + extract components
**Purpose:** `discovery` fetches through TanStack Query; page file shrinks toward ~150 LOC
**Files:**
- Modify: `apps/web/src/app/(app)/discovery/page.tsx` — replace the 2 effects / 12 states' server-state parts with `useQuery(discoverySearchQuery(...))` (+ `authorSuggestionsQuery` where used); keep UI-only state (mode toggle, input) as `useState`; export the content component for the test
- Create: `apps/web/src/components/discovery/*.tsx` — the inline sub-components (names read from the file), named exports
- Create: `apps/web/src/app/(app)/discovery/page.test.tsx` — MSW-driven loading → results → error
- Create: `apps/web/src/components/discovery/*.test.tsx` — for any extracted component with real behaviour
**Dependencies:** 1
**Implementation notes:** `useDebounce` stays as-is feeding the query key. `discovery` has no Firestore. Preserve every class name and the `loading.tsx` handoff.
**Rollback:** `git checkout` the page; delete the new component/test files.
**Preconditions:** Task 1 merged.
**Verification:**
- Run: `npm run -w web test -- src/app/\(app\)/discovery src/components/discovery && npx -w web tsc --noEmit && npm run -w web build`
- Expect: all exit 0
- Run: `npx -w web eslint 'src/app/(app)/discovery/page.tsx'`
- Expect: 0 `react-hooks/set-state-in-effect`
**Done when:** `discovery` renders identically via `useQuery`, tested, no server-state `useEffect`.

### Task 3: Migrate `horizon` + extract components + move types
**Purpose:** same, for `horizon` (470 LOC, 2 inline types, raw `apiRequest`)
**Files:**
- Modify: `apps/web/src/app/(app)/horizon/page.tsx` — `useQuery(horizonPredictionQuery(...))` / `useMutation` for the predict submit; extract the prediction form + result cards; import the moved types from `@/lib/types`
- Create: `apps/web/src/components/horizon/*.tsx` + `*.test.tsx`
- Create: `apps/web/src/app/(app)/horizon/page.test.tsx`
**Dependencies:** 1
**Implementation notes:** the raw `apiRequest` call is now `getHorizonPrediction` (Task 1). `horizon:87`'s `set-state-in-effect` must be gone after.
**Rollback:** `git checkout` the page; delete new files.
**Preconditions:** Task 1 merged.
**Verification:**
- Run: `npm run -w web test -- src/app/\(app\)/horizon src/components/horizon && npx -w web tsc --noEmit && npm run -w web build`
- Expect: all exit 0
- Run: `npx -w web eslint 'src/app/(app)/horizon/page.tsx'`
- Expect: 0 `react-hooks/set-state-in-effect`
**Done when:** `horizon` on `useQuery`, types in `lib/types.ts`, tested, warning gone.

### Task 4: Migrate `nexus` + extract components + move types
**Purpose:** same, for `nexus` (499 LOC — the largest; 3 inline types; raw `apiRequest`; a chat surface)
**Files:**
- Modify: `apps/web/src/app/(app)/nexus/page.tsx` — `useQuery(nexusSearchQuery(...))` for literature search; `useMutation(nexusChat)` for the collection chat (with `onError` — no optimistic update needed); extract the collection panel, the chat panel, the result list; import moved types
- Create: `apps/web/src/components/nexus/*.tsx` + `*.test.tsx`
- Create: `apps/web/src/app/(app)/nexus/page.test.tsx`
**Dependencies:** 1
**Implementation notes:** the chat message list stays local `useState` (it is UI state, appended to as the mutation resolves). `nexus:84`'s warning must be gone. This is the biggest single task — if the page does not fall under ~180 LOC after extraction, that is acceptable, but no server-state `useEffect` may remain.
**Rollback:** `git checkout` the page; delete new files.
**Preconditions:** Task 1 merged.
**Verification:**
- Run: `npm run -w web test -- src/app/\(app\)/nexus src/components/nexus && npx -w web tsc --noEmit && npm run -w web build`
- Expect: all exit 0
- Run: `npx -w web eslint 'src/app/(app)/nexus/page.tsx'`
- Expect: 0 `react-hooks/set-state-in-effect`
**Done when:** `nexus` search + chat on TanStack Query, extracted, tested, warning gone.

### Task 5: Migrate `paper/[id]` + extract components
**Purpose:** same, for `paper/[id]` (299 LOC, `analyzePaper` + OpenAlex work fetch, 2 effects)
**Files:**
- Modify: `apps/web/src/app/(app)/paper/[id]/page.tsx` — `useQuery(paperAnalysisQuery(id))` + `useQuery` for the OpenAlex work record (route handler); remove the `intelRetryCount` counter (→ `refetch`); export the content component
- Create: `apps/web/src/components/paper/*.tsx` + `*.test.tsx`
- Create: `apps/web/src/app/(app)/paper/[id]/page.test.tsx`
**Dependencies:** 1
**Implementation notes:** `paper/[id]` already has `TableOfContents` in `components/paper/`. The `use(params)` wrapper stays; test the exported content component directly (Phase 1 lesson — `use()` does not settle in jsdom).
**Rollback:** `git checkout` the page; delete new files.
**Preconditions:** Task 1 merged.
**Verification:**
- Run: `npm run -w web test -- src/app/\(app\)/paper src/components/paper && npx -w web tsc --noEmit && npm run -w web build`
- Expect: all exit 0
- Run: `npx -w web eslint 'src/app/(app)/paper/[id]/page.tsx'`
- Expect: 0 `react-hooks/set-state-in-effect`
**Done when:** `paper/[id]` on `useQuery`, extracted, tested.

### Task 6: Migrate `home`'s API calls to `useQuery` + extract cards
**Purpose:** `home`'s 5 API calls go through TanStack Query; its Firestore/profile half is untouched
**Files:**
- Modify: `apps/web/src/app/(app)/home/page.tsx` — the `getDailyFeed` / `getDailyConjecture` / `getIndustryOpportunities` / `getMatchGrants` / `getJournalAdvisor` calls → `useQuery(<factory>)`, each gated on the resolved `authorId` from the existing Firestore/profile hook; leave `useMyProfile` / any `onSnapshot` exactly as-is; extract the feed / conjecture / industry / grants cards
- Create: `apps/web/src/components/home/*.tsx` + `*.test.tsx`
- Create: `apps/web/src/app/(app)/home/page.test.tsx` — mock the profile hook, assert the API cards render from MSW
**Dependencies:** 1
**Implementation notes:** the single `useEffect` at `home:123` is the API-fetch one — it goes; the Firestore subscription (if any) stays. `home` is the trickiest because it mixes both data sources — keep the seam clean: profile/Firestore in a hook, API in `useQuery`.
**Rollback:** `git checkout` the page; delete new files.
**Preconditions:** Task 1 merged.
**Verification:**
- Run: `npm run -w web test -- src/app/\(app\)/home src/components/home && npx -w web tsc --noEmit && npm run -w web build`
- Expect: all exit 0
- Run: `npx -w web eslint 'src/app/(app)/home/page.tsx'`
- Expect: 0 `react-hooks/set-state-in-effect`
**Done when:** `home`'s API sections on `useQuery`, Firestore half unchanged, tested.

### Task 7: Promote the `react-hooks` lint rules to `error`
**Purpose:** the anti-pattern Phase 1 parked can no longer regress
**Files:**
- Modify: `apps/web/eslint.config.mjs` — `react-hooks/set-state-in-effect` → `"error"`; `react-hooks/preserve-manual-memoization` → `"error"` **only if** `npm run -w web lint` reports zero remaining sites for it; otherwise leave that one at `"warn"` and add a one-line comment naming the remaining file(s) as a narrowed follow-up
**Dependencies:** 2, 3, 4, 5, 6
**Implementation notes:** run `eslint` first to enumerate any `preserve-manual-memoization` sites left in files this plan did not touch (e.g. `profile`, `workspace`). Fixing those is out of scope — the fallback is the split severity above. Do not touch a Firestore page to satisfy this task.
**Rollback:** revert `eslint.config.mjs`.
**Preconditions:** Tasks 2–6 merged.
**Verification:**
- Run: `npm run -w web lint`
- Expect: exit 0, and `grep -c '"warn"' apps/web/eslint.config.mjs` reflects the decision (0 if both promoted, 1 if `preserve-manual-memoization` stays)
**Done when:** `set-state-in-effect` is `error` and lint is green; the memoization rule is `error` or a documented narrowed `warn`.

## Verification (end to end)

1. `npm run -w web test` → all pass, incl. 5 new `page.test.tsx` + extracted-component tests.
2. `npx -w web tsc --noEmit` → exit 0.
3. `npm run -w web lint` → exit 0 with `set-state-in-effect` at `error`.
4. `npm run -w web build` → exit 0.
5. `cd apps/web && npm run test:e2e` → the Phase 1 e2e still passes (nothing here touches `/` or `/login`).
6. `grep -rn "fetch\|apiRequest" apps/web/src/app/\(app\)/{discovery,horizon,nexus,paper,home}` inside a `useEffect` → none (server state is `useQuery`).
7. Manual: each migrated page renders every section with the same data and error/retry behaviour as before.
8. `gh run list --branch feat/web-world-class-phase2` → all workflows green.

## CI iteration / addenda

- **e2e axe**: hardening the axe scan (`waitForLoadState`) revealed that the landing/login pages have a **real, pre-existing `color-contrast` violation** (PR #4's axe passes were a mid-paint flake that missed it). Excluded `color-contrast` from the blocking set with a `TODO(a11y)` � every other serious/critical rule still blocks. A real contrast audit against `globals.css` is a design-token follow-up.

## Follow-ups (not this plan)

- **Firestore-realtime pages** (`profile`, `workspace`, `workspace/[id]`, `home`'s Firestore half) — god-component split + a `useFirestoreDoc`/`useFirestoreCollection` hook wrapping `onSnapshot`. Separate plan.
- **RSC slice** — still deferred; needs real first-paint metrics from Phase 1/2 to justify the Server-Component split of `author/[id]` + `paper/[id]` and its Suspense plumbing.
- **Bundle-size budget** in CI.

[NEEDS CLARIFICATION: RSC slice — the spec's Phase 2 also named a bounded Server-Component conversion of `author/[id]` + `paper/[id]` initial load. This plan **defers it** (keeps it a follow-up pending first-paint metrics) because the value is modest for heavily-interactive pages and the Suspense/server-`searchParams` plumbing is real risk. Confirm deferral, or fold it in as Tasks 8–9.]

[NEEDS CLARIFICATION: `home` is half Firestore, half API. This plan migrates only its API half and leaves the Firestore/profile code untouched. Confirm that split is acceptable rather than doing `home` whole in the Firestore-pages follow-up.]

## Approved

Gate 1 passed 2026-09-01. 2 markers resolved (RSC deferred; home API-half only).
