# apps/web World-Class Foundation — Phase 1 Implementation Plan

**Goal:** Give `apps/web` a real engineering foundation — a running test pyramid, a caching data layer, tighter type/lint/observability tooling, and CI that runs it all — plus one fully-migrated reference page proving the data-fetching pattern for Phase 2.

**Source brief:** `docs/specs/2026-09-01-web-world-class-design.md` (§5, Phase 1). Approach decided there (option C, phased).

**Slug:** buzzing-singing-avalanche (branch: `feat/web-world-class-foundation`)

**Risk:** HIGH (computed) — `tools/scope.py` forces HIGH on a CI-config surface; also touches the app's dependency manifest and every page's future data path.

**Blast radius:** `apps/web` dependency tree + lockfile; `apps/web` build config (`tsconfig`, `eslint`, `next.config.ts`); the root `layout.tsx` (new provider boundary); `app/(app)/author/[id]/page.tsx` behavior (re-implemented, must stay identical); `.github/workflows/ci.yml` `web-verification` job. No backend, no other app, no visual/token change.

**Rollback:** Per-task rollback below. Worst landing state (half merged): revert the merge commit — the app returns to its current hand-rolled-fetch state; the only durable leftover is added devDependencies (harmless) and new `src/test/` + `e2e/` files (inert without the CI steps).

**Architecture:** Follows the spec. TanStack Query v5 as the single client data layer behind a root provider; `lib/api/client.ts` stays the transport (gains a timeout); `lib/api/queries.ts` centralizes typed `queryOptions`. Vitest + RTL + jsdom + MSW for unit/component tests colocated as `*.test.tsx`; Playwright + axe for e2e. `author/[id]` is migrated end to end as the reference; the other ~7 pages are **Phase 2** (own plan).

**Tech stack and constraints:**

- `apps/web` only. No backend, Go gateway, Android, or `.claude/` layer changes.
- **No visual change** — `globals.css`, tokens, motion, layout untouched (`architecture/references/design-contract.md` owns those).
- **No RSC / Server Component conversion** — that is Phase 2's separate spec. Every page stays `"use client"` in Phase 1.
- `author/[id]` must render the same sections, same data, same error/retry behavior after migration — verified by a component test and the e2e flow.
- Pin new deps to exact or `~`; regenerate `package-lock.json` on this (POSIX-capable) shell so `npm ci` works in CI — the Windows-lock/`lightningcss` lesson from PR #3.
- Verify every Next 16 / `@sentry/nextjs` API against the installed package (`apps/web/AGENTS.md`), not training data.
- `react-hooks/set-state-in-effect` + `preserve-manual-memoization` **stay at `warn`** in Phase 1 — the other 7 pages still have fetch-in-effect until Phase 2; promoting to `error` is a Phase 2 task.

## Grounding

**Patterns mirrored (file:line):**

- **Endpoint fns** — `apps/web/src/lib/api/endpoints.ts:19` (`export const getX = (args) => apiRequest<T>("/path", {params})`). `queries.ts` wraps these as `queryFn`s; naming stays `getX`/`searchX`.
- **Transport + errors** — `apps/web/src/lib/api/client.ts:3` `ApiError(status, message)` thrown on `!res.ok`; kept. `apps/web/src/components/ui/ErrorBanner.tsx` is the retry surface, already wired into pages — TanStack Query's `error` + `refetch` feed it.
- **The anti-pattern being replaced** — `apps/web/src/lib/hooks/useMyProfile.ts` and `app/(app)/author/[id]/page.tsx:98` (`useState` machine + `cancelled` flag + `refetchToken` counter + `console.error` + `.catch(()=>{})`). TanStack Query removes all of it.
- **Hooks** — `apps/web/src/lib/hooks/useX.ts`, `"use client"`, named export. New query hooks follow this.
- **Components** — `apps/web/src/components/<feature>/PascalCase.tsx`, named export. Extracted `author/` sub-components follow this.
- **Tests** — **none exist anywhere in the repo.** This plan defines the convention: Vitest, colocated `*.test.tsx`, RTL `screen` queries, MSW for the network boundary, `@axe-core/playwright` in e2e.
- **CI job shape** — `.github/workflows/ci.yml` `web-verification` (checkout → setup-node 20 → `npm ci` → `next build` → `tsc --noEmit` → `eslint`). New steps append in that job.

**Repo memory (`tools/memory.py --paths` on the target files):** only `decisions/0007` (a retired backend endpoint) came back — nothing about `apps/web` tooling, the data layer, or config. `MEMORY.md`/`ISSUES.md` have no entry for these paths.

## File map

| File | Action | Owns afterward |
|---|---|---|
| `apps/web/package.json` | Modify | Phase-1 deps + scripts (`test`, `test:watch`, `test:e2e`); `eslint-config-next` aligned to `next` |
| `apps/web/package-lock.json` | Modify | Regenerated, cross-platform, `npm ci`-clean |
| `apps/web/vitest.config.ts` | Create | Vitest config — jsdom env, `src/test/setup.ts`, React plugin, coverage off |
| `apps/web/src/test/setup.ts` | Create | `@testing-library/jest-dom` matchers; MSW server `beforeAll`/`afterEach`/`afterAll` |
| `apps/web/src/test/handlers.ts` | Create | MSW request handlers for the gateway + OpenAlex endpoints under test |
| `apps/web/src/test/render.tsx` | Create | `renderWithProviders` — wraps RTL `render` in a fresh `QueryClientProvider` |
| `apps/web/src/lib/utils.test.ts` | Create | Smoke test (`cn` merge) proving the runner works |
| `apps/web/playwright.config.ts` | Create | Playwright config — chromium project, `webServer` = `next dev`, baseURL |
| `apps/web/e2e/smoke.spec.ts` | Create | Load `/`, open command palette, load an author via MSW-free real dev flow (or skip-if-no-backend guard) |
| `apps/web/e2e/axe.spec.ts` | Create | `@axe-core/playwright` scan of `/` and `/login` — no serious/critical violations |
| `apps/web/src/components/providers.tsx` | Create | `"use client"` — single `QueryClient` (staleTime 60s, retry 2, no refetchOnWindowFocus) + devtools in dev |
| `apps/web/src/app/layout.tsx` | Modify | Wrap `{children}` in `<Providers>` inside the existing `AuthProvider` |
| `apps/web/src/lib/api/client.ts` | Modify | `apiRequest` gains `AbortSignal.timeout(15_000)` default (still overridable); `ApiError` unchanged |
| `apps/web/src/lib/api/queries.ts` | Create | Typed `queryOptions` factories (key + fn + staleTime) for author/paper/feed/discovery resources |
| `apps/web/src/lib/api/queries.test.ts` | Create | Assert key shape + `queryFn` wiring for 2–3 factories |
| `apps/web/src/app/(app)/author/[id]/page.tsx` | Modify | Re-implemented with `useQuery`; no `useState`/`cancelled`/`refetchToken`; sub-components imported |
| `apps/web/src/components/author/StatTile.tsx` | Create | Extracted from the page (was inline) |
| `apps/web/src/components/author/MetricPill.tsx` | Create | Extracted from the page |
| `apps/web/src/components/author/SectionHeading.tsx` | Create | Extracted from the page |
| `apps/web/src/app/(app)/author/[id]/page.test.tsx` | Create | loading → data → error transitions via MSW |
| `apps/web/src/components/author/StatTile.test.tsx` | Create | Renders label + animated value |
| `apps/web/src/lib/api/endpoints.ts` | Modify | Delete `resolveAuthorEmail`, `getPeerRecommendations`, `checkRegisteredPeers` (unused) |
| `apps/web/src/lib/types.ts` | Modify | Delete types used only by the removed endpoints (if any) |
| `apps/web/tsconfig.json` | Modify | `target` ES2022; `noUncheckedIndexedAccess`, `noImplicitOverride`, `noFallthroughCasesInSwitch`, `forceConsistentCasingInFileNames` |
| `apps/web/src/**/*.tsx` | Modify | Guarded index access / override fallout from the tsconfig change (chart + data files) |
| `apps/web/eslint.config.mjs` | Modify | Add `eslint-plugin-jsx-a11y` recommended + `@vitest/eslint-plugin` for test files |
| `apps/web/src/instrumentation-client.ts`, `apps/web/sentry.server.config.ts`, `apps/web/sentry.edge.config.ts` | Create | `@sentry/nextjs` init (exact filenames per installed SDK version) |
| `apps/web/next.config.ts` | Modify | `withSentryConfig` wrapper |
| `apps/web/src/app/error.tsx`, `apps/web/src/app/(app)/error.tsx` | Modify | `Sentry.captureException(error)` in the boundary |
| `apps/web/.env.local.example` | Modify | `NEXT_PUBLIC_SENTRY_DSN=` documented |
| `.github/workflows/ci.yml` | Modify | `web-verification`: add `npm test`; add a Playwright e2e step |

## Progress
- [x] Task 1 — Add Phase-1 dependencies, align `eslint-config-next` — verified: `npm ci` clean, `next build` exit 0, lock cross-platform. jsdom pinned ~26 (node-20 compat).
- [x] Task 2 — Vitest + RTL + jsdom + MSW harness — verified: `vitest run` 6 passed (utils + queries). jsdom hoisted to root (workspace nesting broke vitest's env resolution).
- [x] Task 3 — Playwright + axe harness — verified: `npm run test:e2e` 5 passed (3 smoke + 2 axe, 0 serious/critical a11y violations on / and /login).
- [x] Task 4 — TanStack Query provider + `queries.ts` + `apiRequest` timeout — verified: `queries.test.ts` 4 passed, tsc rc=0, build rc=0.
- [x] Task 5 — Migrate `author/[id]` to `useQuery` + extract sub-components — verified: 11 tests pass, tsc/build rc=0, author page eslint 0 errors + 0 set-state-in-effect warnings. Tested `AuthorDetailContent` directly (default export is just the Next route wrapper).
- [x] Task 6 — Remove the 3 unused `endpoints.ts` exports (+ dead `PeerRecommendation` type) — verified: tsc/lint/build exit 0, grep clean.
- [x] Task 7 — TypeScript strictness + fallout — verified: tsc rc=0 with 4 flags on (ES2022 + noUncheckedIndexedAccess + noImplicitOverride + noFallthroughCasesInSwitch + forceConsistentCasingInFileNames); 4 mechanical index-guard fixes; 11 tests + build still green.
- [x] Task 8 — ESLint jsx-a11y recommended + vitest plugin — verified: `npm run lint` rc=0 (0 errors). 3 real label-association fixes (profile/onboarding/ManuscriptTab), 1 documented no-autofocus exception (command palette).
- [x] Task 9 — Sentry @sentry/nextjs wiring — verified: tsc/build/test/lint all green. SDK enabled:false with no DSN (no-op). v10 layout: src/instrumentation{,-client}.ts + src/sentry.{server,edge}.config.ts, withSentryConfig in next.config.ts, captureException in both error.tsx.
- [ ] Task 10 — CI: add `npm test` + Playwright e2e to `web-verification`
- [ ] Task 11 — `refactoring` sweep over `apps/web`, apply findings

## Constitution gate
- [x] I Evidence — every task names its command + expected output
- [x] II Test first — Tasks 2/3 stand up the runners; Task 5 writes the `author` page/component tests before/with the re-implementation; Task 4 writes `queries.test.ts`
- [x] III Smallest change — `author/[id]` is the only page touched; other pages are Phase 2
- [ ] IV Reversibility — Task 10 (CI) and the dep/lockfile change (Task 1) are the outward/irreversible-ish steps; both land on a branch behind Gate 2
- [x] V No silent degradation — no check disabled; `react-hooks` rules stay at `warn` (not lowered — they were already `warn` from PR #3) with the Phase-2 promotion noted
- [x] VI Mechanism — the new CI steps (Task 10) enforce that the suite runs
- [x] VII Secrets — no credential enters the repo; `NEXT_PUBLIC_SENTRY_DSN` stays empty in the example file

## Complexity tracking
- **IV Reversibility:** Task 1 regenerates `package-lock.json` (reversible via revert); Task 10 edits CI (reversible via revert). Neither pushes/merges/deploys — `release-git` + Gate 2 own that. No migrations, no credentials created.

## Tasks

### Task 1: Add Phase-1 dependencies, align `eslint-config-next`
**Purpose:** every later task's tooling is installed and `npm ci` reproduces it
**Files:**
- Modify: `apps/web/package.json` — add devDeps `vitest`, `@vitejs/plugin-react`, `jsdom`, `@testing-library/react`, `@testing-library/jest-dom`, `@testing-library/user-event`, `msw`, `@playwright/test`, `@axe-core/playwright`, `eslint-plugin-jsx-a11y`, `@vitest/eslint-plugin`; deps `@tanstack/react-query`, `@tanstack/react-query-devtools`, `@sentry/nextjs`; bump `eslint-config-next` `16.2.10` → the `16.3.x` matching `next`; add scripts `"test": "vitest run"`, `"test:watch": "vitest"`, `"test:e2e": "playwright test"`
- Modify: `apps/web/package-lock.json` — regenerated by `npm install` on this shell
**Dependencies:** none
**Implementation notes:** pin the test/tooling deps to `~` minor; `@tanstack/react-query` to `^5`. Run `npm install` from repo root (workspaces). Confirm the regenerated lock carries all `node_modules/lightningcss-*` and `@tanstack` platform entries (PR #3 lesson). Do **not** run `npx playwright install` here — that is Task 3 / CI.
**Rollback:** `git checkout apps/web/package.json apps/web/package-lock.json`
**Preconditions:** clean `apps/web` working tree.
**Verification:**
- Run: `npm ci && npm run -w web build`
- Expect: install clean, `next build` exit 0 (no code changed yet)
- Run: `grep -c '"eslint-config-next": "16.3' apps/web/package.json`
- Expect: `1`
**Done when:** all deps resolve, `npm ci` is clean, `next build` still passes.

### Task 2: Vitest + RTL + jsdom + MSW harness
**Purpose:** `npm test` runs, with the network boundary mockable
**Files:**
- Create: `apps/web/vitest.config.ts` — `environment: "jsdom"`, `setupFiles: ["src/test/setup.ts"]`, `plugins: [react()]`, `globals: true`, `include: ["src/**/*.test.{ts,tsx}"]`, alias `@` → `src`
- Create: `apps/web/src/test/setup.ts` — `import "@testing-library/jest-dom/vitest"`; MSW `server` lifecycle (`beforeAll(listen)`, `afterEach(resetHandlers)`, `afterAll(close)`)
- Create: `apps/web/src/test/handlers.ts` — MSW `http.get`/`http.post` handlers for `/api/v1/author_suggestions`, `/search_author`, `/network_collaborators`, `/citation_heatmap`, `/journal_advisor`, `/api/v1/daily_feed`, and `https://api.openalex.org/*` — returning minimal fixtures typed against `lib/types.ts`
- Create: `apps/web/src/test/render.tsx` — `renderWithProviders(ui)` wrapping RTL `render` in a per-call fresh `QueryClient` (retry off in tests)
- Create: `apps/web/src/lib/utils.test.ts` — asserts `cn("a", false && "b", "c") === "a c"`
**Dependencies:** 1
**Implementation notes:** `render.tsx` imports the `QueryClient` config from `providers.tsx` shape but builds its own instance (tests must not share cache). Keep `handlers.ts` fixtures tiny — one array element each.
**Rollback:** delete the created files (scripts are Task 1's, left in place — harmless).
**Preconditions:** Task 1 merged.
**Verification:**
- Run: `npm run -w web test`
- Expect: exit 0, `utils.test.ts` 1 passed, MSW logs no unhandled-request warnings
**Done when:** `vitest run` is green with the smoke test.

### Task 3: Playwright + axe harness
**Purpose:** `npm run test:e2e` runs a real browser flow + an a11y scan
**Files:**
- Create: `apps/web/playwright.config.ts` — `projects: [{ name: "chromium" }]`, `webServer: { command: "npm run dev", url: "http://localhost:3000", reuseExistingServer: !process.env.CI }`, `testDir: "e2e"`
- Create: `apps/web/e2e/smoke.spec.ts` — visit `/`, assert the hero renders; visit `/login`, assert the "Firebase is not configured" banner (no env in CI); open the command palette with the keyboard shortcut and assert it appears
- Create: `apps/web/e2e/axe.spec.ts` — `new AxeBuilder({ page })` on `/` and `/login`; assert zero `serious`/`critical` violations
**Dependencies:** 1
**Implementation notes:** the app builds and runs without Firebase env (README) — e2e targets that state. No backend needed for `/` and `/login`. Do not test authed routes here (they redirect to `/login`).
**Rollback:** delete `playwright.config.ts` and `e2e/`.
**Preconditions:** Task 1 merged; `npx playwright install chromium` available locally.
**Verification:**
- Run: `cd apps/web && npx playwright install chromium && npm run test:e2e`
- Expect: exit 0, smoke + axe specs pass
**Done when:** both e2e specs pass locally against `next dev`.

### Task 4: TanStack Query provider, `queries.ts`, `apiRequest` timeout
**[DEVIATION 2026-09-01]** `Dependencies` corrected to **1, 2** — Task 4's `queries.test.ts` and its `npm test` verification need Task 2's Vitest harness. `parallel_groups` already schedules Task 2 (round 3) before Task 4 (round 4); the stated dependency line just missed it. Execution order: 2 then 4.
**Purpose:** the caching data layer exists and is wired at the root
**Files:**
- Create: `apps/web/src/components/providers.tsx` — `"use client"`; `QueryClient` with `defaultOptions: { queries: { staleTime: 60_000, retry: 2, refetchOnWindowFocus: false } }`; `<QueryClientProvider>` + `<ReactQueryDevtools initialIsOpen={false} />` (dev only)
- Modify: `apps/web/src/app/layout.tsx` — wrap `{children}` with `<Providers>` inside `<AuthProvider>`
- Modify: `apps/web/src/lib/api/client.ts` — default `signal ??= AbortSignal.timeout(15_000)`; on `AbortError` throw `ApiError(408, "Request to <path> timed out")`
- Create: `apps/web/src/lib/api/queries.ts` — `authorQuery(name, id, focus)`, `collaboratorsQuery(...)`, `heatmapQuery(id)`, `journalAdvisorQuery(id)`, `dailyFeedQuery(...)` etc. as `queryOptions({ queryKey: [...], queryFn: () => getX(...), staleTime })`
- Create: `apps/web/src/lib/api/queries.test.ts` — assert `authorQuery("Ada", "A1").queryKey` deep-equals the documented shape; assert `queryFn` calls the endpoint (spy/MSW)
**Dependencies:** 1
**Implementation notes:** `queryKey` convention: `["author", { id, name, focus }]`, `["author", id, "collaborators"]`, etc. — documented as a comment at the top of `queries.ts`. Auth-token endpoints (`/users`, `/user_memory`) are **not** added here in Phase 1 (their pages are Phase 2).
**Rollback:** delete `providers.tsx`, `queries.ts`, `queries.test.ts`; revert `layout.tsx` and `client.ts`.
**Preconditions:** Task 1 merged.
**Verification:**
- Run: `npm run -w web test -- src/lib/api/queries.test.ts` and `npx -w web tsc --noEmit` and `npm run -w web build`
- Expect: all exit 0
**Done when:** provider mounts (build passes), `queries.test.ts` green, no type errors.

### Task 5: Migrate `author/[id]` to `useQuery` + extract its sub-components
**Purpose:** the reference implementation of the Phase-2 pattern, behavior-identical
**Files:**
- Create: `apps/web/src/components/author/StatTile.tsx`, `MetricPill.tsx`, `SectionHeading.tsx` — verbatim extractions of the `StatTile` / `MetricPill` / `SectionHeading` functions currently defined inline near the top of `app/(app)/author/[id]/page.tsx` (above `AuthorDetailPage`), named exports
- Modify: `apps/web/src/app/(app)/author/[id]/page.tsx` — replace the `useState`/`useEffect`/`cancelled`/`refetchToken` machine with `useQuery(authorQuery(...))` for the primary fetch and `useQuery(collaboratorsQuery/heatmapQuery/journalAdvisorQuery)` (`enabled: !!author?.id`) for the three secondary sections; `handleRefresh` → `refetch()` + `refreshAuthor` mutation; errors → `<ErrorBanner onRetry={refetch}>`
- Create: `apps/web/src/app/(app)/author/[id]/page.test.tsx` — with MSW: renders skeleton while pending; renders StatTiles + RadarChart on success; renders `ErrorBanner` on 500 and `refetch` re-requests
- Create: `apps/web/src/components/author/StatTile.test.tsx` — renders label + value
**Dependencies:** 2, 4
**Implementation notes:** the page stays `"use client"`. Keep every existing section, class name, and `motion` wrapper — this is a data-layer swap, not a redesign. The secondary fetches were already independent (`page.tsx:105-120`); `useQuery` makes that structural. Confirm the `react-hooks/set-state-in-effect` warning count for this file drops to 0 after.
**Rollback:** `git checkout` the page; delete the new component + test files.
**Preconditions:** Tasks 2 and 4 merged.
**Verification:**
- Run: `npm run -w web test -- src/app/\(app\)/author && npx -w web tsc --noEmit && npm run -w web build`
- Expect: all exit 0; page test covers pending/success/error
- Run: `npx -w web eslint 'src/app/(app)/author/[id]/page.tsx'`
- Expect: 0 `react-hooks/set-state-in-effect` warnings for that file
**Done when:** the page renders identically (test + manual), driven by `useQuery`, with the three inline components extracted.

### Task 6: Remove the 3 unused `endpoints.ts` exports
**Purpose:** dead code gone
**Files:**
- Modify: `apps/web/src/lib/api/endpoints.ts` — delete `resolveAuthorEmail`, `getPeerRecommendations`, `checkRegisteredPeers` and any now-unused imports from `lib/types.ts`
- Modify: `apps/web/src/lib/types.ts` — delete any type referenced only by those 3 (verify with grep first)
**Dependencies:** none
**Implementation notes:** `logPeerInvite` **is** used — keep it. Grep each type name repo-wide before deleting.
**Rollback:** `git checkout` both files.
**Preconditions:** none.
**Verification:**
- Run: `npx -w web tsc --noEmit && npm run -w web lint && npm run -w web build`
- Expect: all exit 0
- Run: `grep -rn 'resolveAuthorEmail\|getPeerRecommendations\|checkRegisteredPeers' apps/web/src`
- Expect: no matches
**Done when:** the 3 exports are gone and the build is clean.

### Task 7: TypeScript strictness + fallout
**Purpose:** the compiler catches index and override bugs
**Files:**
- Modify: `apps/web/tsconfig.json` — `target: "ES2022"`; add `"noUncheckedIndexedAccess": true`, `"noImplicitOverride": true`, `"noFallthroughCasesInSwitch": true`, `"forceConsistentCasingInFileNames": true`
- Modify: `apps/web/src/**/*.tsx` (fallout only) — guard newly-flagged index access (`arr[0]` → check/`?.`), mainly in `components/author/RadarChart.tsx`, `components/author/CitationBarChart.tsx`, chart/data mappers
**Dependencies:** 5, 6
**Implementation notes:** fallout is expected to be small and mechanical (add `?`/guards, no logic change). Do **not** widen types with `!` to silence — add a real guard or a narrowed early return.
**Rollback:** `git checkout apps/web/tsconfig.json` and the touched files.
**Preconditions:** Tasks 5 and 6 merged (so new code is fixed once).
**Verification:**
- Run: `npx -w web tsc --noEmit`
- Expect: exit 0
- Run: `npm run -w web test && npm run -w web build`
- Expect: exit 0 (no behavior regression)
**Done when:** `tsc --noEmit` passes with the four flags on.

### Task 8: ESLint — `jsx-a11y` + vitest plugin
**Purpose:** a11y regressions and test-file mistakes are linted
**Files:**
- Modify: `apps/web/eslint.config.mjs` — add `jsxA11y.flatConfigs.recommended`; add a `@vitest/eslint-plugin` block scoped to `**/*.test.{ts,tsx}` and `src/test/**`; keep the existing `react-hooks` `warn` overrides (Phase-2 promotes them)
- Modify: `apps/web/src/**/*.tsx` (fallout only) — fix `jsx-a11y` findings (labels, `alt`, roles) that are real; `// eslint-disable-next-line` with a reason only where the rule is a false positive
**Dependencies:** 5, 6
**Implementation notes:** run `eslint` after adding the plugin, triage findings: fix real ones, disable-with-reason false positives, do **not** turn rules off wholesale. If a real finding needs markup restructuring beyond a line, record it as a Phase-2 follow-up rather than expanding scope.
**Rollback:** `git checkout apps/web/eslint.config.mjs` and touched files.
**Preconditions:** Tasks 5 and 6 merged.
**Verification:**
- Run: `npm run -w web lint`
- Expect: exit 0 (0 errors; warnings allowed)
**Done when:** `jsx-a11y` recommended + the vitest plugin are active and `eslint` exits 0.

### Task 9: Sentry (`@sentry/nextjs`) wiring
**Purpose:** unhandled errors and boundary catches report to Sentry
**Files:**
- Create: `apps/web/src/instrumentation-client.ts`, `apps/web/sentry.server.config.ts`, `apps/web/sentry.edge.config.ts` — `Sentry.init({ dsn: process.env.NEXT_PUBLIC_SENTRY_DSN, tracesSampleRate: 0.1, enabled: !!process.env.NEXT_PUBLIC_SENTRY_DSN })`; **filenames/exports verified against the installed `@sentry/nextjs` version's docs**
- Modify: `apps/web/next.config.ts` — wrap the export in `withSentryConfig(nextConfig, { silent: true, tunnelRoute: "/monitoring" })`
- Modify: `apps/web/src/app/error.tsx`, `apps/web/src/app/(app)/error.tsx` — `useEffect(() => Sentry.captureException(error), [error])`
- Modify: `apps/web/.env.local.example` — add `NEXT_PUBLIC_SENTRY_DSN=` with a comment (create a project at sentry.io → paste DSN; leave blank to disable)
**Dependencies:** 1
**Implementation notes:** with no DSN the SDK is `enabled: false` — a no-op, and `next build` must still pass. Do not add session replay (quota). Keep `tunnelRoute` so ad-blockers don't drop events.
**Rollback:** delete the sentry config files; revert `next.config.ts`, the two `error.tsx`, `.env.local.example`.
**Preconditions:** Task 1 merged.
**Verification:**
- Run: `npm run -w web build`
- Expect: exit 0; build log shows the Sentry webpack plugin ran (or "disabled, no auth token" — acceptable)
- Run: `NEXT_PUBLIC_SENTRY_DSN= npx -w web tsc --noEmit`
- Expect: exit 0
**Done when:** build passes with Sentry wired; SDK no-ops without a DSN.

### Task 10: CI — add `npm test` + Playwright e2e to `web-verification`
**Purpose:** the suite runs on every PR
**Files:**
- Modify: `.github/workflows/ci.yml` — in `web-verification`, after the `eslint` step add `- name: Unit tests` → `npm run --workspace web test`; add `- name: E2E` → `npx --workspace web playwright install --with-deps chromium` then `npm run --workspace web test:e2e` (with `CI=true`)
**Dependencies:** 2, 3, 5
**Implementation notes:** Playwright's `webServer` starts `next dev` in CI (`reuseExistingServer: false` when `CI`). Chromium-only to keep it fast. If the browser install step is too slow, split e2e into its own job — but try inline first.
**Rollback:** revert the `ci.yml` diff.
**Preconditions:** Tasks 2, 3, 5 merged (tests exist and pass locally).
**Verification:**
- Run: push the branch; `gh run list --branch feat/web-world-class-foundation`
- Expect: `SkoLab CI Pipeline` green, `web-verification` job runs unit + e2e steps and passes
**Done when:** CI runs and passes the new steps on the PR.

### Task 11: `refactoring` sweep over `apps/web`, apply findings
**Purpose:** catch remaining dead files / stale comments / dangling refs in `apps/web`
**Files:**
- Modify: whatever the sweep flags **within `apps/web`** — dead files, unused exports, stale counts, TODOs with no owner, duplicated guidance
**Dependencies:** 5, 6, 7
**Implementation notes:** invoke the `refactoring` skill scoped to `apps/web`; apply only findings inside `apps/web` (backend/layer are out of scope). Anything structural beyond a delete/rename → Phase-2 follow-up.
**Rollback:** `git checkout apps/web` for any file the sweep changed that proves wrong.
**Preconditions:** Tasks 5, 6, 7 merged.
**Verification:**
- Run: `npx -w web tsc --noEmit && npm run -w web test && npm run -w web build && npm run -w web lint`
- Expect: all exit 0
**Done when:** the sweep's `apps/web` findings are applied and the full web check set is green.

## Verification (end to end)

1. `npm ci` (repo root) → clean.
2. `npm run -w web test` → exit 0, includes `queries.test.ts`, `author` page + component tests, `utils` smoke.
3. `cd apps/web && npm run test:e2e` → smoke + axe specs pass.
4. `npx -w web tsc --noEmit` → exit 0 with the four strict flags.
5. `npm run -w web lint` → exit 0 with `jsx-a11y` recommended active.
6. `npm run -w web build` → exit 0 with Sentry wired (DSN blank).
7. `grep -rn 'resolveAuthorEmail\|getPeerRecommendations\|checkRegisteredPeers' apps/web/src` → empty.
8. Manual/e2e: `/author/<id>` renders every section with the same data and the same retry behavior as before, now via `useQuery`.
9. `gh run list --branch feat/web-world-class-foundation` → all workflows green.

## Phase 2 (separate plan, immediately after this)

Migrate the remaining pages to `useQuery` + full component extraction (`nexus`, `horizon`, `discovery`, `paper/[id]`, `home`, `profile`, `workspace`, `workspace/[id]`); move all inline response types to `lib/types.ts`; promote `react-hooks/set-state-in-effect` + `preserve-manual-memoization` to `error`; then the bounded RSC slice from the spec's Phase 2.

## Known risks / follow-ups

- **Playwright browser download in CI** (~120 MB, ~1 min) — mitigated by chromium-only + cache; fallback is a separate job.
- **`noUncheckedIndexedAccess` fallout** could be larger than "a few files" — if it exceeds ~15 files, split Task 7's fixes by directory into sub-tasks rather than one sprawling diff.
- **Sentry** wired but inert until a DSN is provided (see marker).
- **`jsx-a11y`** may surface findings that need real markup work — those become Phase-2 items, not Task 8 scope creep.

## Resolved at Gate 1

- **Data-fetching library** → TanStack Query v5 (plan as written).
- **Sentry** → Task 9 wires `@sentry/nextjs` fully; SDK stays `enabled:false` until `NEXT_PUBLIC_SENTRY_DSN` is set. No DSN provided now; no credential enters the repo.
- **Phase 2** → its own plan/PR, written immediately after Phase 1 merges. Not folded in here.

## Approved

Gate 1 passed 2026-09-01. Three markers resolved with recommended options (TanStack Query v5, Sentry wired-but-inert, Phase 2 as a separate plan). Canonical plan; harness copy at ~/.claude/plans/buzzing-singing-avalanche.md.
