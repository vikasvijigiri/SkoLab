# apps/web — World-Class Engineering Foundation (design)

**Date:** 2026-09-01
**Status:** proposed — for `task-analysis` to scope, Gate 1 to approve
**Scope:** `apps/web` only. Backend, Android, and the `.claude/` layer migration are out of scope.

---

## 1. The problem

`apps/web` works and looks good, but its engineering foundation is not
production-grade:

| Dimension | Current | Evidence |
|---|---|---|
| Data fetching | Hand-rolled `apiRequest` + `fetch`-in-`useEffect` across 12 pages, each with a `cancelled` flag and `.catch(()=>{})` | `lib/api/client.ts`, `app/(app)/author/[id]/page.tsx:98` |
| Caching / dedup | None — every navigation refetches; two components wanting the same resource fetch twice | no cache layer anywhere |
| Component size | 6 page files 285–499 LOC, sub-components + response types defined inline | `nexus/page.tsx` 499, `horizon/page.tsx` 470, `author/[id]/page.tsx` 444 |
| Client/server split | 48 of 58 `.tsx` are `"use client"`; every `(app)/` route renders client-side | `grep -rl '"use client"' src` |
| Tests | Zero. No runner, no `test` script | no `*.test.*`, no vitest/jest/playwright config |
| TS strictness | `strict: true` but `target: ES2017`, no `noUncheckedIndexedAccess` / `noImplicitOverride` | `tsconfig.json` |
| Lint | Runs in CI (since PR #3) but 2 `react-hooks` rules muted to `warn` | `eslint.config.mjs` |
| Dead code | `resolveAuthorEmail`, `getPeerRecommendations`, `checkRegisteredPeers` never imported | `lib/api/endpoints.ts` |
| Deps | `eslint-config-next@16.2.10` vs `next@^16.3.0` | `package.json` |
| Observability | No Sentry / RUM despite `.claude/rules/error-monitoring.md` mandating it | no `@sentry/*` dep |
| Accessibility | No automated check (`jsx-a11y`, axe) | eslint config has no a11y plugin |

## 2. Constraints that shape the approach

- **Firebase auth is 100% client-side.** No `firebase-admin` in `apps/web`, no
  session cookies, no server-side token verification. `app/(app)/layout.tsx` is
  a `"use client"` auth-gate.
- **CoLab Workspace + Profile read/write Firestore directly** for realtime sync
  (founding decision `decisions/0004`). Those pages cannot be server-rendered.
- **Most research-data endpoints are unauthenticated** (`/api/v1/author_suggestions`,
  `/search_author`, `/api/v1/daily_feed`, `/api/v1/analyze_paper`…). Only
  `/api/v1/users` and `/api/v1/user_memory` need the ID token.
- **The product's feel is animation-heavy** — framer-motion transitions,
  interactive charts (`RadarChart`, `CitationBarChart`), `cmdk` command palette,
  debounced live search. These are inherently client components.
- CI for `apps/web` exists as of PR #3 (`web-verification` job: `next build` →
  `tsc --noEmit` → `eslint`). No test step yet.
- `apps/web/AGENTS.md`: Next 16 has breaking changes — verify APIs against the
  installed package, not training data.

## 3. Approaches considered

### A — Incremental engineering hardening (keep the client-heavy shape)

Adopt a real data-fetching library (TanStack Query), extract god components,
stand up the test pyramid (Vitest + RTL + Playwright + MSW), tighten
TS/ESLint, remove dead code, align deps, wire Sentry + `jsx-a11y`/axe, add the
test step to CI. Architecture stays a client SPA over the App Router.

- **Wins:** ~90% of the "world-class" value; contained blast radius; every task
  independently verifiable; no auth re-architecture.
- **Loses:** the `48/58 "use client"` smell stays; no streaming/RSC benefit on
  first paint for the data-heavy routes.

### B — RSC re-architecture

Move read-path data fetching into Server Components / route handlers, shrink
the client boundary to genuinely-interactive leaves, stream with `<Suspense>`,
keep Firebase for auth + Firestore realtime only.

- **Wins:** smaller client bundle, faster first contentful paint on
  `paper/[id]` / `author/[id]`, the "modern Next 16" shape.
- **Loses:** needs Firebase **session cookies** (`firebase-admin` + a
  `/api/session` login route + `middleware.ts`) to server-fetch anything
  authed — a real migration with a real regression surface. Firestore-realtime
  pages (Workspace, Profile) stay client regardless, so the result is a
  split data model. Weeks of work; high risk; much of the payoff blocked by
  the auth-cookie prerequisite.

### C — Phased: A now, a bounded RSC slice later  ← chosen

Phase 1 = **all of A**, delivered as one plan. Phase 2 (separate spec + plan,
evaluated on Phase 1's real metrics) = convert only the routes that fetch
**public** data and benefit from streaming — `paper/[id]`, `author/[id]`
initial load — to Server Components using the unauthenticated endpoints, with
`<Suspense>`. **Firebase session cookies are explicitly deferred** — Phase 2
touches no authed route.

## 4. Chosen direction — C, and why it beat the others

- **Over pure A:** A leaves a permanent architectural smell and forecloses the
  streaming win on exactly the two routes (`paper/[id]`, `author/[id]`) where a
  cold load is slowest. Naming Phase 2 now keeps that door open without
  committing to it blind.
- **Over pure B:** B's value is gated behind a Firebase-session-cookie
  migration this codebase has no scaffolding for, and the realtime pages can't
  move anyway — so a "full RSC app" is not actually reachable. Doing B first
  means weeks before any measurable improvement lands.
- **Phase 1 is one coherent deliverable** — "the `apps/web` quality
  foundation" — with a single verification surface (web CI green + a passing
  test suite). It decomposes into ordered rounds, not separate plans:
  foundation (test infra, CI, TS/ESLint) → data layer (TanStack Query) →
  component extraction → observability + a11y → dead-code sweep.

**What would change the choice:** if a product requirement lands that needs SEO
/ shareable server-rendered pages (public author/paper pages indexed by
Google), B's priority jumps and Phase 2 becomes Phase 1.

**What C gives up:** first-paint performance on the data-heavy routes stays as
it is until Phase 2; the client bundle does not shrink in Phase 1 beyond what
dead-code removal and code-splitting buy.

## 5. Phase 1 design (what `task-analysis` scopes)

### 5.1 Data-fetching layer

- Introduce **TanStack Query v5** as the single client data layer. One
  `QueryClient` in a `providers.tsx` client boundary at the root.
- `lib/api/client.ts` keeps `apiRequest` as the transport (add: a default
  timeout via `AbortSignal.timeout`, typed error surface) — TanStack Query owns
  caching, dedup, retry/backoff, `status`/`error` state, `staleTime`.
- `lib/api/endpoints.ts` functions become the `queryFn`s; add a
  `lib/api/queries.ts` with typed `queryOptions` (keys + fn + staleTime) per
  resource so keys are defined once.
- Migrate the 12 `fetch`-in-`useEffect` pages to `useQuery` / `useMutation`.
  Delete the `cancelled`-flag boilerplate. Secondary-section fetches
  (`getNetworkCollaborators` etc.) become their own `useQuery` with
  `enabled: !!authorId`, so each section renders when its own data lands — no
  waterfall, no silent `.catch(()=>{})`.
- Optimistic updates (feed dismiss, workspace edits) get a real
  `onError` rollback.

[NEEDS CLARIFICATION: Data layer — TanStack Query v5 (this spec's choice; ~13kB
gz, the ecosystem standard, devtools, exact SWR/retry semantics) vs SWR
(~4kB, lighter, Vercel-native, thinner mutation story) vs staying hand-rolled
with a shared cache util. TanStack recommended.]

### 5.2 Component architecture

- Split each 285+ LOC page into: a thin route file (params, Suspense boundary,
  composition) + a `components/<feature>/` directory of the sub-components
  currently inline (`StatTile`, `MetricPill`, `SectionHeading`, the `nexus`
  chat panel, the `horizon` form, …).
- Move all inline response `interface`s (e.g. `nexus`'s `WorkspacePaper`,
  `OpenAlexSearchResult`) into `lib/types.ts`.
- `nexus/page.tsx` stops calling `apiRequest` raw — routes through
  `lib/api/endpoints.ts`.
- Target: no page file over ~150 LOC; no component file over ~200.

### 5.3 Test infrastructure

- **Vitest + @testing-library/react + jsdom** for unit/component tests,
  colocated as `*.test.tsx` next to the component.
- **MSW** mocks the network boundary (the gateway + OpenAlex), shared handlers
  in `src/test/handlers.ts`.
- **Playwright** for e2e — a handful of flows: load home, search an author,
  open a paper, open the command palette, sign-in error state when Firebase
  unconfigured. (Playwright over Cypress — already an MCP in this layer, better
  parallelism, first-class trace viewer.)
- Coverage target: the data hooks, the extracted components with real
  behavior, and the `apiRequest` error paths. Not a % gate initially — a named
  set of must-cover units.
- `package.json` scripts: `test`, `test:watch`, `test:e2e`.

### 5.4 TypeScript + ESLint strictness

- `tsconfig.json`: `target` → `ES2022`; add `noUncheckedIndexedAccess`,
  `noImplicitOverride`, `noFallthroughCasesInSwitch`, `forceConsistentCasingInFileNames`.
  Fix the fallout (expected: index-access guards in a few chart/data files).
- `eslint.config.mjs`: add `eslint-plugin-jsx-a11y` (recommended ruleset).
  Restore `react-hooks/set-state-in-effect` and
  `react-hooks/preserve-manual-memoization` to `error` — the migration in 5.1
  removes the effects that tripped them.
- Add `@vitest/eslint-plugin` for the test files.

### 5.5 Observability + accessibility

- **Sentry** (`@sentry/nextjs`) — client + edge + server config, `withSentryConfig`
  in `next.config.ts`, tunneled route to dodge ad-blockers. Wrap the root
  `error.tsx` / `(app)/error.tsx` to report. Session replay off initially
  (quota).
- **axe** — `@axe-core/playwright` assertion in the e2e flows; `jsx-a11y` for
  static coverage (5.4).

[NEEDS CLARIFICATION: Sentry — is a Sentry project + DSN available for
`skolab-web` (the layer's `.mcp.json` has a `sentry` entry but no token)? If
not: wire `@sentry/nextjs` fully but leave `NEXT_PUBLIC_SENTRY_DSN` unset (SDK
no-ops) and add it to `.env.local.example`, or defer Sentry entirely to a
follow-up.]

### 5.6 Dependency alignment + dead code

- Bump `eslint-config-next` to match `next` (`16.3.x`).
- Pin the `^`-ranged deps that PR #3's lockfile regen showed drift on
  (`@types/react`, `typescript`) to exact, or accept `~`.
- Delete the 3 unused `endpoints.ts` exports and any type they solely used.
- Run `refactoring`'s sweep over `apps/web` as the last round — dead files,
  unused exports, stale comments, `TODO`s — and apply what it finds within
  `apps/web`.

### 5.7 CI

- Add a `test` step (Vitest) and an `e2e` step (Playwright, `--project=chromium`)
  to the `web-verification` job in `.github/workflows/ci.yml`.
- Keep the existing `next build` → `tsc` → `eslint` order; tests after lint.

## 6. Error handling, data flow, testing (Phase 1)

- **Data flow:** component → `useQuery(queryOptions)` → `apiRequest` (transport,
  timeout, typed error) → gateway/OpenAlex. Cache + retry + dedup in TanStack
  Query. Auth token injected via a `queryFn` closure that calls `getIdToken()`
  only for the `/users` + `/user_memory` keys.
- **Error handling:** `apiRequest` throws `ApiError(status, message)`. Query
  errors surface through `ErrorBanner` with the existing retry affordance
  (`refetch`). No more `.catch(()=>{})`. `error.tsx` boundaries report to
  Sentry.
- **Testing:** MSW handler per endpoint; component tests assert loading →
  data → error transitions; Playwright asserts the real flows + axe.

## 7. Out of scope (Phase 1)

- Any RSC / Server Component conversion (that is Phase 2, separate spec).
- Firebase session cookies / server-side auth.
- Visual redesign, new tokens, motion changes — `globals.css` and the design
  system are untouched (`architecture/references/design-contract.md` owns that).
- Backend, Go gateway, Android, `.claude/` layer.
- New product features or routes.
- A coverage-percentage CI gate (named must-cover set instead, initially).

## 8. Open markers (batched at Gate 1)

1. §5.1 — data-fetching library choice (TanStack Query recommended).
2. §5.5 — Sentry DSN availability / defer decision.
3. Phase-2 RSC — confirm it stays a **separate** future spec, not folded into
   this plan. (This spec assumes yes.)
