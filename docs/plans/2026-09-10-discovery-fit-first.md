# Discovery — Fit-First Collaborator Finder Implementation Plan

**Goal:** Replace Discovery's citation-sorted "Top researchers in <field>" list
with a fit-first collaborator finder that defaults to the signed-in user's own
resolved research area and shows per-researcher collaboration signals plus a
click-only filter rail, entirely in the `apps/web` Next.js + OpenAlex layer.

**Source brief:** `TASK.md` 2026-09-10 entry; the architecture analysis earlier
in the originating conversation (Option B, settled).

**Slug:** discovery-fit-first

**Risk:** medium — `python tools/scope.py --plan` reports "medium forced by:
volume" (27 declared paths). Not permission to skip Gate 2.

**Blast radius:** `apps/web` Discovery route + its OpenAlex proxy route handler,
two new `app/api/enrich/*` route handlers, the shared `lib/types.ts`, the
TanStack query registry. No backend service, no database, no auth, no persisted
state. The OpenAlex proxy change is a response **superset** so the onboarding
`?q=` picker and the drilldown list are untouched.

**Rollback:** delete all new files; `git checkout` the 7 modified files
(`api/openalex/authors/route.ts`, `lib/api/endpoints.ts`, `lib/api/queries.ts`,
`lib/types.ts`, `app/(app)/discovery/page.tsx`, `app/(app)/discovery/loading.tsx`,
`app/(app)/discovery/page.test.tsx`). No migration, no external-service config,
nothing persisted. Worst half-merged landing state: `discoveryResearchersQuery`
absent → `page.tsx` falls back to the existing taxonomy drilldown and still
renders.

**Architecture:** All new logic lives in the `apps/web` Next.js + OpenAlex
route-handler layer, where Discovery already lives
([endpoints.ts](../../apps/web/src/lib/api/endpoints.ts) comment: OpenAlex is
called "via the same-origin Next route handler … NOT the Go gateway"). No Go
toolchain exists on the build box ([decisions/0009](../../decisions/0009-phase1-authors-assessment.md)),
so the embedding-cosine fit term is deliberately out — fit is computed from
OpenAlex-available signals (shared topics/concepts Jaccard + shared institution +
topical focus), which is exactly the "degraded" blend
[decisions/0011](../../decisions/0011-similarity-engine.md) already specifies for
cold entities. Pure derivation modules (`lib/discovery/*.ts`) keep every
threshold and weight in one env-overridable `config.ts` and are unit-tested in
isolation; the route handlers and React components are thin over them.

**Tech stack and constraints:**
- Next.js (see `apps/web/AGENTS.md` — read `node_modules/next/dist/docs/` before
  route-handler work), React 19, TanStack Query v5, Tailwind v4, framer-motion,
  vitest + msw, Playwright + axe.
- **No changes under `services/backend-go/` or `services/backend/`.**
- Free-tier OpenAlex: **one `/authors` list call per view**; **one** extra
  batched Wikidata call per visible page, week-cached. No N+1 per-author calls.
- **No hardcoding:** every tunable in `lib/discovery/config.ts`,
  `process.env`-overridable where a tuning knob (mirrors Go `SIM_W_*`). Country /
  institution-type filter options are **derived from the fetched result set**,
  never a hand-typed list. Filter/sort definitions are one config array consumed
  by the rail, not duplicated JSX.
- **Click-only UX** (memory `no-typing-click-only-ux`; `page.test.tsx` asserts
  `container.querySelector("input, textarea")` is `null`): every filter is a
  toggle button / `Chip` / `SegmentedControl` with `aria-pressed`. No `<input>`
  of any kind.
- `DESIGN.md` is authority: status colours from existing tokens only
  (`--accent-live` active, `--warning` winding-down, `--text-muted` dormant;
  deceased = `--text-muted` + an "in memoriam" glyph, **never `--danger`**);
  sparkline uses `--metric-influence`. WCAG AA (`check:contrast` + axe).
  Dark-first "Instrument".
- `.claude/rules/markdown-style.md` for every `.md` written.
- Non-goals: `/discovery?tab=papers` beyond keeping it working; a real
  open-to-collaboration data pipeline; true 2nd-degree graph distance; the global
  `/leaderboard` view; `apps/android-app`.

## Resolved decisions (were clarification markers, answered at Gate 1)

- **Topical-focus control:** a **4-stop `SegmentedControl`** (Any / ≥25% / ≥50% /
  ≥75%), not a range `<input>` — keeps the click-only invariant and the existing
  test unchanged.
- **Connection-distance filter:** ship only a **"Shares your institution"**
  toggle, derived from the single `/authors` payload (zero extra calls). True
  2nd-degree (shared co-author) is deferred to a follow-up when the Go network
  graph is buildable.

---

## Context

Discovery's researcher surface today
([discovery/page.tsx](../../apps/web/src/app/(app)/discovery/page.tsx)) makes the
user click through a `field → subfield → topic` taxonomy the app already knows
(the user is resolved to an OpenAlex author at onboarding), then lists authors
sorted by `cited_by_count:desc`. That sort structurally surfaces old, famous,
oversubscribed people — the live site shows N. F. Mott (d. 1996), P. G. de Gennes
(d. 2007), P. W. Anderson (d. 2020) as "researchers you could collaborate with".
The only metric shown is a raw `H-154` — not field-normalised, never decreasing,
and silent on whether the person is active, reachable, or a topical match. There
is no liveness signal and no connection to *this* user.

This rebuilds the surface around **fit to the signed-in user**, adds
per-researcher signals (activity/liveness, momentum, career stage, active
decades, topical focus, field-normalised standing, ORCID-verified), a hard
"deceased" flag where verifiable, and a click-only filter rail — all in the
Next.js + OpenAlex layer.

---

## Grounding — patterns to mirror

| Category | Pattern | Cite |
|---|---|---|
| OpenAlex proxy route | `NextRequest` → build `api.openalex.org` URL, `withOpenAlexKey`, mailto `User-Agent`, `next.revalidate`, `429` single-backoff, map to trimmed shape | [api/openalex/works/route.ts](../../apps/web/src/app/api/openalex/works/route.ts) |
| Taxon filter grammar | `bareId()` last-segment; `TAXON_FILTER` map | [api/openalex/authors/route.ts:12](../../apps/web/src/app/api/openalex/authors/route.ts#L12) |
| Query wrapper | `queryOptions({ queryKey:[…] as const, queryFn, ...HOURLY, enabled })` | [queries.ts:256](../../apps/web/src/lib/api/queries.ts#L256) |
| `fetch` endpoint wrapper | `fetch('/api/…')`, `if(!res.ok) throw new ApiError`, `Array.isArray` guard | [endpoints.ts:219](../../apps/web/src/lib/api/endpoints.ts#L219) |
| Result card | `motion.div` stagger `Math.min(index*0.05,0.3)` → `Link` (`focusRing`, `shortOpenAlexId`) → `Card glow interactive accentSide="left"` → avatar + name + `Badge` | [AuthorResultCard.tsx](../../apps/web/src/components/discovery/AuthorResultCard.tsx) |
| Filter rail | `RailShell`, `Chip` list, breadcrumb buttons, `eyebrow` labels | [discovery/page.tsx:105](../../apps/web/src/app/(app)/discovery/page.tsx#L105) |
| Toggle control | `SegmentedControl` (`aria-label`, `layoutId`, `options`, `value`, `onChange`) | [discovery/page.tsx:92](../../apps/web/src/app/(app)/discovery/page.tsx#L92) |
| Component test | `renderWithProviders`, `screen`, msw `server.use`, `@/test/handlers`, `vi.mock('next/navigation')` | [discovery/page.test.tsx](../../apps/web/src/app/(app)/discovery/page.test.tsx) |
| Pure-lib test | plain `describe/it/expect`, inline fixtures | [AIDailyBriefCard.test.tsx](../../apps/web/src/components/feed/AIDailyBriefCard.test.tsx) |
| Playwright visual + axe | `emulateMedia({colorScheme})`, scroll-through, `AxeBuilder().withTags(['wcag2a','wcag2aa'])`, screenshot → `e2e/__screens__/` | [e2e/rollout-visual.spec.ts](../../apps/web/e2e/rollout-visual.spec.ts) |
| Env-tunable weights | `envFloat("SIM_W_*", default)` | [blend.go:11](../../services/backend-go/internal/similarity/blend.go#L11) |
| Temp preview route | `app/__preview/<name>/page.tsx`, screenshot, delete before done | this session's daily-brief work |

**Memory / decisions checked** (`tools/memory.py --paths` on the affected files):
0007 (n/a), 0008 (confirms "Python = LLM only, everything else Go/managed"; the
Next-layer path is the sanctioned third option — no conflict), MEMORY `apps/web`
= dark-first Instrument. Nothing names the discovery components. No stale entries.

---

## File map

**Create**
- `apps/web/src/lib/discovery/config.ts` — thresholds + filter/sort definitions; env overrides.
- `apps/web/src/lib/discovery/signals.ts` — pure `OpenAlexAuthorRaw → ResearcherSignals`.
- `apps/web/src/lib/discovery/signals.test.ts`
- `apps/web/src/lib/discovery/fit.ts` — pure `(viewer, candidate) → { score, why }`.
- `apps/web/src/lib/discovery/fit.test.ts`
- `apps/web/src/lib/discovery/mapAuthor.ts` — OpenAlex row → `ResearcherResult`; `buildAuthorsFilter`; `mapSort`.
- `apps/web/src/lib/discovery/mapAuthor.test.ts`
- `apps/web/src/lib/discovery/deaths.ts` — Wikidata SPARQL bindings → `Record<orcid, year>`; `buildDeathSparql`.
- `apps/web/src/lib/discovery/deaths.test.ts`
- `apps/web/src/lib/discovery/collabFlags.ts` — seam: ids → `Record<id, boolean>` (empty today).
- `apps/web/src/lib/discovery/collabFlags.test.ts`
- `apps/web/src/app/api/enrich/deaths/route.ts` — batched ORCID→death, week-cached.
- `apps/web/src/app/api/enrich/collab-flags/route.ts` — seam route returning `{}`.
- `apps/web/src/components/discovery/StatusDot.tsx`
- `apps/web/src/components/discovery/MomentumSparkline.tsx`
- `apps/web/src/components/discovery/ActiveDecadesStrip.tsx`
- `apps/web/src/components/discovery/ResearcherCard.tsx`
- `apps/web/src/components/discovery/ResearcherCard.test.tsx`
- `apps/web/src/components/discovery/DiscoveryFilters.tsx`
- `apps/web/src/components/discovery/DiscoveryFilters.test.tsx`
- `apps/web/e2e/discovery-researchers.spec.ts`
- `decisions/0014-discovery-fit-first-collaborator.md`

**Modify**
- `apps/web/src/app/api/openalex/authors/route.ts` — widen `select`; accept `hIndexMax`/`country`/`instType`/`hasOrcid`/`worksMin`/`sort`; superset response.
- `apps/web/src/lib/api/endpoints.ts` — `openAlexResearchers`, `fetchDeceasedFlags`, `fetchCollabFlags`.
- `apps/web/src/lib/api/queries.ts` — `discoveryResearchersQuery`, `deceasedFlagsQuery`, `collabFlagsQuery`.
- `apps/web/src/lib/types.ts` — `OpenAlexAuthorRaw`, `ResearcherSignals`, `ResearcherResult`, `DiscoveryFilterState`, `DiscoverySort`.
- `apps/web/src/app/(app)/discovery/page.tsx` — resolve viewer area; fit-ranked `ResearcherCard` grid; mount `DiscoveryFilters`; drilldown → "explore another field"; unresolved → drilldown fallback.
- `apps/web/src/app/(app)/discovery/page.test.tsx` — default-to-my-area, filter toggling, no-input invariant, unresolved fallback.
- `apps/web/src/app/(app)/discovery/loading.tsx` — skeleton matches new layout.

**No change:** `LeaderboardRow.tsx`, `AuthorResultCard.tsx` (kept for the drilldown list), `services/**`.

---

## Constitution gate
- [x] I Evidence — every task names a command + expected output.
- [x] II Test first — every behaviour task writes its failing test first.
- [x] III Smallest change — Go/Python untouched; drilldown reused not rewritten.
- [x] IV Reversibility — pure additive; revert = delete new files + `git checkout` 7 files. No migration, no persisted schema.
- [x] V No silent degradation — unresolved viewer, OpenAlex 429/error, Wikidata timeout, cold `expertise` each have a defined visible fallback.
- [x] VI Mechanism — "no hardcoding" enforced by `config.ts` single-source + a `signals.test.ts` env-override case; a `DiscoveryFilters.test.tsx` no-`<input>` assertion.
- [x] VII Secrets — none; OpenAlex key already via `withOpenAlexKey`, server-only.

## Complexity tracking
- 11 tasks: each lib/component is independently testable and reviewable; they
  form one deliverable (the researcher surface) sharing the `ResearcherResult`
  type, and the page is unusable until card + filters + query all land — not
  splittable into separately shippable plans.

## Execution deviations
- **Serial execution, not the parallel-round fan-out.** Implemented inline in
  dependency order (1 → 2,3,4,5,8 → 6,7 → 9 → 10 → 11), no subagents. The
  `parallel_groups.py` rounds were used only to order the work.
- **Task 11 (decision record) done last, not in round 2.** Its own verification
  ("the record must match what the code actually does") is only satisfiable
  after the code exists.
- **Commit strategy:** one commit at the end, after the full verification tier
  passes. No push / PR (not requested).

---

## Progress
- [x] Task 1 — `config.ts` + `signals.ts` + types + tests — `vitest run src/lib/discovery/signals.test.ts` → 7/7 pass; `tsc --noEmit` + `eslint src/lib/discovery` clean
- [x] Task 2 — `fit.ts` + tests — `vitest run src/lib/discovery/fit.test.ts` → 6/6 pass. Deviation: added `ResearcherResult.topics: string[]` (the plan's Task 2 already assumed "candidate topic labels" — made it explicit on the type).
- [x] Task 3 — widen `openalex/authors` route + `mapAuthor.ts` + tests — `vitest run src/lib/discovery/mapAuthor.test.ts` → 9/9; live smoke `GET /api/openalex/authors?subfield=3104&hasOrcid=1&hIndexMax=60&sort=standing` → 36 rows carrying `counts_by_year`+`summary_stats`+`topics`, `hIndexMax`/`sort` honoured; tsc+eslint 0. Plan corrected (recorded in Task 3 body): `/authors` has no `topics.subfield.id` filter — route now expands subfield/field → topic-id list via `/topics`.
- [x] Task 4 — Wikidata `deaths` route + `deaths.ts` + tests — `vitest run src/lib/discovery/deaths.test.ts` → 6/6; live `GET /api/enrich/deaths?orcids=<2 deceased>,<1 living>` → `{"…9153":2017,"…3025":2021}` (living one omitted); failure path returns `{}` 200.
- [x] Task 5 — `collab-flags` seam route + `collabFlags.ts` + tests — `vitest run src/lib/discovery/collabFlags.test.ts` → 3/3; live `GET /api/enrich/collab-flags?ids=A1,A2` → `{}`.
- [x] Task 6 — endpoint + query wrappers — `openAlexResearchers` / `fetchDeceasedFlags` / `fetchCollabFlags` in `endpoints.ts`; `discoveryResearchersQuery` / `deceasedFlagsQuery` / `collabFlagsQuery` in `queries.ts` (key holds only the server-affecting filter slice via `serverFilterParams`). `tsc --noEmit` 0; existing `page.test.tsx` still 34/34. Note: `hIndexMax` stays wired in `buildAuthorsFilter` (tested) but not yet passed from the client — career-stage is a client post-filter for now.
- [x] Task 7 — `ResearcherCard` + `StatusDot` + `MomentumSparkline` + `ActiveDecadesStrip` + test — `vitest run src/components/discovery/ResearcherCard.test.tsx` → 5/5 (identity/fit/why, top-% vs H- fallback, in-memoriam + no `--danger`, collab chip gated, ORCID badge gated); `check:contrast` all pass; tsc+eslint 0. Minor: dropped `AuthorResultCard`'s hover-prefetch (not in the Task 7 spec).
- [x] Task 8 — `DiscoveryFilters` (click-only) + test — `vitest run src/components/discovery/DiscoveryFilters.test.tsx` → 6/6; no `<input>` in the rail; country/instType chips built from `facets`; tsc+eslint 0.
- [x] Task 9 — rewire `page.tsx` + `loading.tsx` + `page.test.tsx` — `vitest run src/lib/discovery src/components/discovery "src/app/(app)/discovery"` → 52/52 (9 files); tsc+eslint 0. Resolved viewer → fit grid with no click; unresolved → leaderboard / drilldown; filter toggle re-renders; no `<input>` retained. Extra file touched: `src/test/handlers.ts` (added default `/api/v1/leaderboard/:field`, enrich-route, and enriched author stubs — test-infra hygiene for the new routes).
- [x] Task 10 — Playwright `discovery-researchers.spec.ts` + keep `rollout-visual` green — `playwright test discovery-researchers.spec.ts rollout-visual.spec.ts` → 10/10, axe AA clean, no `pageerror`; light+dark screenshots of the live route (`e2e/__screens__/discovery-fitfirst-*.png`) and of the fit grid (`discovery-fit-grid-*.png`, from a temp `/discovery-preview` route) captured and sent to the user. Preview route deleted; committed spec targets `/discovery` only (the authed grid is covered by the unit suite). Fixed en route: sparkline used a non-existent `--metric-influence` var → switched to `--primary`; sort control overflowed the rail → chips instead of a segmented control.
- [x] Task 11 — `decisions/0014-discovery-fit-first-collaborator.md` — written to the 0011 shape; `ls decisions/0014-*.md` ✓; `vitest run src/lib/discovery` → 31/31.

## Final verification (whole plan)
- `npx tsc --noEmit` → exit 0
- `npm run lint` → exit 0
- `npx vitest run` (full) → 39 files / 160 tests pass
- `npm run check:contrast` → all token pairs pass
- `npx playwright test e2e/discovery-researchers.spec.ts e2e/rollout-visual.spec.ts` → 10/10, axe serious/critical = 0, no pageerror
- `npx next build` → exit 0, `/discovery` + `/api/enrich/*` in the manifest, no preview route
- Light + dark screenshots of the fit grid sent to the user

---

## Tasks

### Task 1: `config.ts`, `signals.ts`, shared types
**Purpose:** pure, config-driven derivation of `ResearcherSignals` from one OpenAlex author row.
**Files:**
- Create: `apps/web/src/lib/discovery/config.ts` — `DISCOVERY_CONFIG` (`activeWithinYears` 2, `windingDownYears` 5, `momentumEpsilon` 0.15, `emergingMaxYears` 8, `seniorMinYears` 16, `pageSize` 36, `enrichPageSize` 24, fit weights `wConcept` 0.55 / `wInstitution` 0.15 / `wCollab` 0.15 / `wField` 0.15) each via `readEnv("DISCOVERY_*", default)`; `DISCOVERY_FILTERS` (`{key,label,kind:'toggle'|'segment',options?}[]`); `DISCOVERY_SORTS` (`fit|momentum|standing|recent`); `TOPICAL_FOCUS_STOPS` (`[0, .25, .5, .75]`).
- Create: `apps/web/src/lib/discovery/signals.ts` — `deriveSignals(a: OpenAlexAuthorRaw, ctx: { scopedFieldId?: string; scopedSubfieldId?: string; fieldHIndexPercentile?: (h: number) => number; now?: number }): ResearcherSignals`. Fields: `activity` (`active|winding_down|dormant` from latest `counts_by_year` year with `works_count>0` vs config), `momentum` (`rising|steady|cooling` from OLS slope of `cited_by_count` over the window ÷ mean vs `momentumEpsilon`; `n<3 → steady`), `yearsActiveVisible`, `careerStage` (`emerging|established|senior`), `activeDecades` (sorted decade buckets from `counts_by_year` active years ∪ `affiliations[].years`), `topicalFocus` (0–1: the `topics[].value` whose `subfield.id`/`field.id` matches `ctx.scoped*`, else max), `standingPercentile` (`ctx.fieldHIndexPercentile?.(h) ?? null`), `sparkline` (per-year `cited_by_count`).
- Modify: `apps/web/src/lib/types.ts` — add `OpenAlexAuthorRaw`, `ResearcherSignals`, `ResearcherResult` (`{ id, display_name, orcid, institution, country, instType, hIndex, i10, worksCount, citedBy, twoYrMean, signals, fit?: {score:number;why:string}, deceased?: {year:number}, openToCollaboration?: boolean }`), `DiscoveryFilterState`, `DiscoverySort`.
- Test: `apps/web/src/lib/discovery/signals.test.ts` — fixtures: active-rising early-career; dormant long-career; deceased-shaped (no recent years); sparse `counts_by_year`. Assert every field. One case sets `process.env.DISCOVERY_ACTIVE_WITHIN_YEARS='4'` and re-imports to prove the boundary moved (no literal).
**Dependencies:** none
**Implementation notes:** OLS on ≤10 points. `readEnv` parses int/float, ignores blank/NaN. Pure — no `Date.now()`; take `now` defaulting to current year at the call site. Decade bucket = `Math.floor(year/10)*10`.
**Rollback:** delete the two new files; revert the `types.ts` hunk.
**Preconditions:** none.
**Verification:**
- Run: `cd apps/web && npx vitest run src/lib/discovery/signals.test.ts`
- Expect: all cases pass, including the env-override case.
**Done when:** no literal threshold in `signals.ts` — every comparison reads `DISCOVERY_CONFIG`.

### Task 2: `fit.ts`
**Purpose:** a 0–100 collaboration-fit score + plain-language `why`, no embeddings.
**Files:**
- Create: `apps/web/src/lib/discovery/fit.ts` — `scoreFit(viewer: { expertise: string[]; institution?: string }, cand: ResearcherResult): { score: number; why: string }`. Blend with `DISCOVERY_CONFIG` weights: concept Jaccard(`viewer.expertise` × candidate topic labels) · `wConcept` + same-institution · `wInstitution` + shared-institution-token · `wCollab` + `cand.signals.topicalFocus` · `wField`; scale to 0–100, clamp. `why` = top 1–2 contributors joined by " · " ("4 shared topics", "same institution"). Empty `viewer.expertise` → score from `topicalFocus` + `standingPercentile` only, `why = "topical overlap"`; caller flags the list degraded.
- Test: `apps/web/src/lib/discovery/fit.test.ts` — high-overlap > low-overlap ordering; cold-viewer path; `why` strings; `[0,100]` clamp; never throws on `{expertise:[]}`.
**Dependencies:** 1
**Implementation notes:** port `jaccardSim` shape from [blend.go:30](../../services/backend-go/internal/similarity/blend.go#L30) (exact + 0.5 partial-substring credit over union, cap 1). Deterministic, no network.
**Rollback:** delete both files.
**Preconditions:** Task 1 types exist.
**Verification:**
- Run: `cd apps/web && npx vitest run src/lib/discovery/fit.test.ts`
- Expect: ordering + clamp + cold-path assertions pass.
**Done when:** `scoreFit` is pure and total.

### Task 3: widen the `openalex/authors` route + `mapAuthor.ts`
**Purpose:** one OpenAlex call returns everything the cards need; server-side filters/sort honoured; existing consumers unaffected.

**Plan correction (verified against the live API 2026-09-10):** OpenAlex's
`/authors` endpoint has **no `topics.field.id` / `topics.subfield.id` filter** —
the only topic filters are `topics.id`, `topic_share.id`, `x_concepts.id`. (The
*current* Discovery drilldown's `subfield`/`field` researcher queries are
therefore already broken — they 400.) Also author `topics[]` carries `count`, not
a share `value`; `subfield.id`/`field.id` are full URLs. Consequences:
- The researcher grid scopes by **subfield → its topic-id list**: the route
  accepts `?subfield=<bareId>`, server-side fetches `/topics?filter=subfield.id:<id>&per-page=100&select=id`
  (cached 24 h) and queries `/authors?filter=topics.id:T1|T2|…`. `?topic=<Tid>`
  is passed straight through. `?field=` is **not** a researcher scope (the page
  resolves the viewer to a subfield; a field-only match falls back to drilldown).
- `mapAuthorRow` derives per-topic `value = count / Σcount`, and `bareId()`s
  `subfield.id`/`field.id`.
- The legacy `openAlexAuthorsByTaxon` drilldown keeps calling with `?topic=` for
  a real topic node; its `?subfield=`/`?field=` paths now resolve through the
  same topic-id expansion, which *fixes* them.

**Files:**
- Create: `apps/web/src/lib/discovery/mapAuthor.ts` — `mapAuthorRow(row): ResearcherResult` (derives `topics[].value` from `count`, `bareId`s subfield/field ids, tolerates missing `summary_stats`/`last_known_institutions`); `buildAuthorsFilter(params): string` (compose `filter=` from `topicIds`→`topics.id:T1|T2|…` + `hIndexMax`→`summary_stats.h_index:<N` + `country`→`last_known_institutions.country_code:` + `instType`→`last_known_institutions.type:` + `hasOrcid`→`has_orcid:true` + `worksMin`→`works_count:>N`); `mapSort(sort): string` (`standing`→`summary_stats.h_index:desc`, `recent`→`summary_stats.2yr_mean_citedness:desc`, else `cited_by_count:desc`).
- Create: `apps/web/src/lib/discovery/mapAuthor.test.ts` — `buildAuthorsFilter` composition table; `mapSort` table; `mapAuthorRow` tolerates absent `summary_stats`/`topics`/`counts_by_year`.
- Modify: `apps/web/src/app/api/openalex/authors/route.ts` — `select` gains `summary_stats,counts_by_year,affiliations,last_known_institutions,topics`; add server-side subfield→topic-id expansion (`/topics?filter=subfield.id:<id>`, `next.revalidate` 24 h); parse the new params; use `buildAuthorsFilter`/`mapSort`; `per-page` = `DISCOVERY_CONFIG.pageSize` in the taxon branch; keep the `?q=` branch and `bareId` grammar untouched; add the `429` single-backoff from the works route. **Response stays flat and a superset** — keep every existing key (`id,display_name,orcid,works_count,cited_by_count,h_index,institution`) so `openAlexAuthorsByTaxon → AuthorResultCard` is unaffected; add the enrichment keys as flat fields typed by `OpenAlexAuthorRaw`.
**Dependencies:** 1
**Implementation notes:** author `topics[]` has `count` not a share — `mapAuthorRow` derives `value = count / Σcount`. Comma-join filter clauses; `|` for OR within one clause; never a comma inside a value.
**Rollback:** `git checkout` the route; delete the two lib files.
**Preconditions:** none.
**Verification:**
- Run: `cd apps/web && npx vitest run src/lib/discovery/mapAuthor.test.ts`
- Expect: filter/sort strings match the tables; mapping tolerates missing fields.
**Done when:** `GET /api/openalex/authors?subfield=<id>&hasOrcid=1&hIndexMax=40&sort=standing` returns rows carrying `counts_by_year` (spot-checked in Task 10).

### Task 4: Wikidata deceased-flag route
**Purpose:** never suggest emailing a dead researcher; hard flag where verifiable.
**Files:**
- Create: `apps/web/src/lib/discovery/deaths.ts` — `parseDeathBindings(json): Record<string, number>` (ORCID → death year); `buildDeathSparql(orcids: string[]): string`.
- Create: `apps/web/src/lib/discovery/deaths.test.ts` — binding fixture → map; `[]` → `{}`; malformed date tolerated (skipped, not thrown).
- Create: `apps/web/src/app/api/enrich/deaths/route.ts` — `GET ?orcids=a,b,c` (cap `DISCOVERY_CONFIG.enrichPageSize`, dedupe); query `query.wikidata.org/sparql?query=…&format=json` (`Accept: application/sparql-results+json`, descriptive `User-Agent`), `next: { revalidate: 604800 }`; on non-200 / thrown / timeout return `NextResponse.json({}, { status: 200 })`; success → `Record<orcid, year>`.
**Dependencies:** 1
**Implementation notes:** `SELECT ?orcid ?year WHERE { VALUES ?orcid { "0000-…" … } ?p wdt:P496 ?orcid . OPTIONAL { ?p wdt:P570 ?d } BIND(YEAR(?d) AS ?year) }`. Only ORCID-bearing authors are checkable; the rest rely on `signals.activity`. Use `AbortSignal.timeout(4000)`.
**Rollback:** delete the three files.
**Preconditions:** outbound network to Wikidata.
**Verification:**
- Run: `cd apps/web && npx vitest run src/lib/discovery/deaths.test.ts`
- Expect: parse + empty + malformed cases pass.
**Done when:** the route returns `{}` (HTTP 200), not an error, when Wikidata is unreachable.

### Task 5: "open to collaboration" seam route
**Purpose:** a tested seam professors' self-declared flag fills later — renders nothing today, fabricates nothing.
**Files:**
- Create: `apps/web/src/lib/discovery/collabFlags.ts` — `resolveCollabFlags(ids: string[]): Record<string, boolean>` → `{}` with a `// TODO(decisions/0014): fill from researchers/{uid}.openToCollaboration once a self-declared flag exists`. Typed so a future batched Firestore lookup drops in without touching callers.
- Create: `apps/web/src/lib/discovery/collabFlags.test.ts` — any input → `{}`; shape stable.
- Create: `apps/web/src/app/api/enrich/collab-flags/route.ts` — `GET ?ids=…` → `resolveCollabFlags` → `NextResponse.json`.
**Dependencies:** 1
**Implementation notes:** no server-side Firestore is wired; the seam is the deliverable. `ResearcherCard` shows the chip only when a flag is strictly `true`.
**Rollback:** delete the three files.
**Preconditions:** none.
**Verification:**
- Run: `cd apps/web && npx vitest run src/lib/discovery/collabFlags.test.ts`
- Expect: returns `{}`, stable shape.
**Done when:** the route responds `{}` and `ResearcherCard` has a covered `openToCollaboration` branch.

### Task 6: endpoint + query wrappers
**Purpose:** typed client access to the three data sources.
**Files:**
- Modify: `apps/web/src/lib/api/endpoints.ts` — `openAlexResearchers(params: { level: TaxonLevel; id: string; filters: DiscoveryFilterState; sort: DiscoverySort }): Promise<ResearcherResult[]>` (build query string, `fetch('/api/openalex/authors?…')`, `!res.ok → throw new ApiError`, `Array.isArray` guard, `map(mapAuthorRow)`); `fetchDeceasedFlags(orcids: string[])`; `fetchCollabFlags(ids: string[])`.
- Modify: `apps/web/src/lib/api/queries.ts` — `discoveryResearchersQuery(level, id, filters, sort)` (`...HOURLY`, `enabled: Boolean(id)`, `queryKey: ["discovery-researchers", level, id, serializeFilters(filters), sort] as const`); `deceasedFlagsQuery(orcids)` (`staleTime: 24*HR`, `gcTime: 7*24*HR`, `enabled: orcids.length > 0`); `collabFlagsQuery(ids)` (`staleTime: 30*MIN`, `enabled: ids.length > 0`).
**Dependencies:** 3, 4, 5
**Implementation notes:** `serializeFilters` = deterministic stable string so the key does not change identity between renders for equal state. Keep `discoveryAuthorsQuery`/`discoveryWorksQuery` — the "explore another field" drilldown still uses them.
**Rollback:** `git checkout` both files.
**Preconditions:** Tasks 3–5 merged.
**Verification:**
- Run: `cd apps/web && npx tsc --noEmit`
- Expect: exits 0.
**Done when:** equal filter state yields an equal (stable) query key across renders.

### Task 7: `ResearcherCard` and sub-components
**Purpose:** one card renders every signal, DESIGN.md-conformant.
**Files:**
- Create: `apps/web/src/components/discovery/StatusDot.tsx` — `activity`/`deceased` → token colour + `aria-label`; deceased = `--text-muted` + "✦ in memoriam", never `--danger`.
- Create: `apps/web/src/components/discovery/MomentumSparkline.tsx` — inline SVG polyline from `signals.sparkline`, stroke `--metric-influence`, wrapper `role="img"` + `aria-label` ("citations per year, rising"), no animation on the data path.
- Create: `apps/web/src/components/discovery/ActiveDecadesStrip.tsx` — one pip per decade in range, filled = active, `aria-label` "Active decades: 2000s, 2010s, 2020s".
- Create: `apps/web/src/components/discovery/ResearcherCard.tsx` — mirror `AuthorResultCard` shell (`motion.div` stagger → `Link` `focusRing` `shortOpenAlexId` → `Card glow interactive accentSide="left"`); accent = `--accent-live`/`--warning`/`--text-muted` by `activity`. Content: avatar + name + `StatusDot` + ORCID-verified `Badge` (only when `orcid`; `title` "ORCID-verified identity"); `institution · country`; `Fit NN` `Badge` + momentum arrow glyph; topical-focus bar (`topic_share` %); standing ("top N%" when `standingPercentile != null`, else `H-<n>`; raw `H-<n>` always in `title`); `careerStage` + "~N yrs"; `ActiveDecadesStrip`; `MomentumSparkline`; `why` line (`line-clamp-2`); "Open to collaboration" `Badge` only when `openToCollaboration === true`. All copy in local `const` maps.
- Create: `apps/web/src/components/discovery/ResearcherCard.test.tsx` — renders name/fit/why; deceased → memoriam text and no `--danger` class/style; `openToCollaboration` chip only when `true`; `standingPercentile == null` → `H-` fallback; ORCID badge only when `orcid` present.
**Dependencies:** 1, 2
**Implementation notes:** no `<input>`. Sparkline is decorative-with-label, not a data-changing animation (DESIGN.md motion rule).
**Rollback:** delete the five files.
**Preconditions:** Task 1–2 types.
**Verification:**
- Run: `cd apps/web && npx vitest run src/components/discovery/ResearcherCard.test.tsx`
- Expect: every branch assertion passes.
**Done when:** `cd apps/web && npm run check:contrast` still exits 0.

### Task 8: `DiscoveryFilters` — click-only rail
**Purpose:** filter/sort without typing; option lists derived from data.
**Files:**
- Create: `apps/web/src/components/discovery/DiscoveryFilters.tsx` — props `{ state: DiscoveryFilterState; sort: DiscoverySort; facets: { countries: {code:string;count:number}[]; instTypes: {type:string;count:number}[] }; onChange; onSortChange; onReset }`. Driven by `DISCOVERY_FILTERS`/`DISCOVERY_SORTS`. Career stage / activity / momentum / has-ORCID / "Shares your institution" = `Chip` or button toggles with `aria-pressed`; topical-focus = 4-stop `SegmentedControl` (`Any / ≥25% / ≥50% / ≥75%`); country + institution-type = toggle `Chip`s built from `props.facets` (with counts); sort = `SegmentedControl`; a "Reset" button. No `<input>`.
- Create: `apps/web/src/components/discovery/DiscoveryFilters.test.tsx` — toggling a chip calls `onChange` with updated state; `aria-pressed` flips; `container.querySelector("input, textarea")` is `null`; empty `facets.countries` hides the country group.
**Dependencies:** 1
**Implementation notes:** the rail never fetches its own options — `page.tsx` computes `facets` from the loaded results with `useMemo` and passes them down.
**Rollback:** delete both files.
**Preconditions:** Task 1 config.
**Verification:**
- Run: `cd apps/web && npx vitest run src/components/discovery/DiscoveryFilters.test.tsx`
- Expect: toggle / onChange / no-input / empty-facets assertions pass.
**Done when:** zero `<input>` nodes; every option traces to `config.ts` or `props.facets`.

### Task 9: rewire `discovery/page.tsx`
**Purpose:** the surface defaults to the viewer's area, ranks by fit, filters live.
**Files:**
- Modify: `apps/web/src/app/(app)/discovery/page.tsx` — resolve the viewer **subfield**: `useMyProfile()` → `author.field_of_study` / `firestoreProfile.researchFocus`, best-label-matched first against the viewer's field's `openAlexSubfieldsQuery()`, else against `openAlexFieldsQuery()` then that field's subfields (pick the first). The researcher grid always scopes by a subfield id (`node.level === "subfield"`) — a field-only or no match → today's drilldown + a calm "pick your area" prompt (no regression). Resolved → `discoveryResearchersQuery("subfield", node.id, filters, sort)`; merge `deceasedFlagsQuery(orcids)` + `collabFlagsQuery(ids)` into the rows; `scoreFit(viewer, row)` per row; order by `sort` (fit/momentum client-side; standing/recent already server-ordered); post-filter career stage / activity / momentum / topical-focus / shares-institution; render a `ResearcherCard` grid (reuse `gridClass`). `DiscoveryFilters` sits in the `RailShell` rail above the breadcrumb; the taxonomy drilldown stays in the rail relabelled "Explore another area". `mode` toggle + `/discovery?tab=papers` path unchanged. `facets` via `useMemo` over the loaded rows. Cold-`expertise` list shows a subtle "ranked by topical overlap" note (reuse the `degraded` idiom).
- Modify: `apps/web/src/app/(app)/discovery/loading.tsx` — skeleton = filter rail + card grid.
- Modify: `apps/web/src/app/(app)/discovery/page.test.tsx` — add: with `useMyProfile` mocked resolved, defaults to the viewer's area and shows `ResearcherCard`s (no drilldown click); unresolved mock → drilldown still renders; toggling a filter chip updates the visible grid; retain and pass the "has no text inputs" assertion.
**Dependencies:** 6, 7, 8
**Implementation notes:** `vi.mock('@/lib/hooks/useMyProfile')` in the test. All enrichment merges are client-side over the single list payload — no per-card fetch. Keep the existing `downloadFieldBrief` and "Test in Horizon" affordances.
**Rollback:** `git checkout` the three files.
**Preconditions:** Tasks 6–8 merged.
**Verification:**
- Run: `cd apps/web && npx vitest run "src/app/(app)/discovery" && npx tsc --noEmit && npm run lint`
- Expect: all exit 0; new + existing assertions pass.
**Done when:** a resolved viewer sees fit-ranked researchers with no click; an unresolved viewer sees no error.

### Task 10: Playwright verification
**Purpose:** prove it renders and is accessible in both themes; capture evidence.
**Files:**
- Create: `apps/web/e2e/discovery-researchers.spec.ts` — against `next dev`: (a) `/discovery` (public) renders the surface (unresolved fallback), no `pageerror`, light + dark, axe `wcag2a`+`wcag2aa` serious/critical = 0, screenshot to `e2e/__screens__/`; (b) a temp preview route `apps/web/src/app/__preview/discovery-researchers/page.tsx` (mock `ResearcherResult[]`, created then **deleted before the task is done**) screenshotted light + dark showing fit-ranked cards + filters + sparkline + status dots.
- Verify: `apps/web/e2e/rollout-visual.spec.ts` still green (Discovery is in its `ROUTES`).
**Dependencies:** 9
**Implementation notes:** reuse the `rollout-visual.spec.ts` scroll-through + `AxeBuilder` setup. Screenshots are gitignored (`e2e/__screens__`). Leave the tree clean — no preview route committed.
**Rollback:** delete the spec; ensure the preview route is gone.
**Preconditions:** `npm run dev` starts.
**Verification:**
- Run: `cd apps/web && npx playwright test e2e/discovery-researchers.spec.ts e2e/rollout-visual.spec.ts`
- Expect: all pass; `e2e/__screens__/discovery-*.png` written.
**Done when:** light+dark screenshots exist for both the live route and the preview, are sent to the user, and no preview route remains in the tree.

### Task 11: decision record
**Purpose:** capture the non-obvious calls.
**Files:**
- Create: `decisions/0014-discovery-fit-first-collaborator.md` — Context / Decision / Alternatives considered / Consequences, per [0011](../../decisions/0011-similarity-engine.md)'s shape. Records: the fit blend + weights + `DISCOVERY_*` env knobs; "OpenAlex has no death/active field — Wikidata `P570` for the verifiable few, `counts_by_year` activity proxy for the rest"; career stage is a coarse estimate bounded by the ~10-year `counts_by_year` window; topical-focus control is a segmented control not a slider (click-only rule); connection distance ships as a "shares your institution" proxy, real 2nd-degree deferred (no Go toolchain); "open to collaboration" is a self-declared seam, empty until professors populate it; "no hardcoding" = `config.ts` single-source + data-derived facets; Discovery stays in the Next layer (not Go) per no-toolchain + existing Discovery architecture.
**Dependencies:** none
**Implementation notes:** `.claude/rules/markdown-style.md` (headers, lists, tables).
**Rollback:** delete the file.
**Preconditions:** none.
**Verification:**
- Run: `ls decisions/0014-*.md && cd apps/web && npx vitest run src/lib/discovery`
- Expect: file present; `0014` is the next unused number (latest is `0013`); lib tests green.
**Done when:** `0014` exists, follows the 0011 section shape, and is linked from this plan.

---

## Verification (end to end)

From `apps/web` unless noted:

1. `npx tsc --noEmit` → 0
2. `npm run lint` → 0
3. `npx vitest run src/lib/discovery src/components/discovery "src/app/(app)/discovery"` → all green
4. `npm run check:contrast` → 0
5. `npx playwright test e2e/discovery-researchers.spec.ts e2e/rollout-visual.spec.ts` → pass, axe clean, screenshots in `e2e/__screens__/`
6. Manual: `npm run dev`, open `/discovery` resolved (or the preview route) → fit-ranked cards, status dots, sparkline, working filters; toggle every filter + sort; `document.querySelectorAll('aside input').length === 0`.
7. Send light + dark screenshots of the researcher grid to the user.

## Rollback (whole plan)

Delete all new files; `git checkout` the 7 modified files. No migration, no
persisted state, no external-service config. Worst half-merged landing: the
surface falls back to the drilldown and still renders.

## Approved
