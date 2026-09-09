# Plan — Instrument-grade frontend: adopt external UI skills + pivot the web visual identity

**Slug:** main
**Date:** 2026-09-08
**Scope:** major · **Risk tier:** high (control-surface: `.claude/` skills + `workflow.md`; volume: ~42 web files)
**Plan mode file** — mirrors what will land in `docs/plans/2026-09-08-instrument-frontend.md` on approval.

---

## Context

The Notion research page *"Harness Repository Collection → 02 — Web App Factory"*
curates external agent-skill repos for frontend/UI/UX. Two are directly useful
and **do not overlap** our lifecycle skills:

- **`addyosmani/agent-skills`** (vendored verbatim at
  `vendor/harness-skills/addyosmani-agent-skills/`, commit `6ca0cd7`) —
  `skills/frontend-ui-engineering` (build production-quality accessible UI,
  "avoid the AI aesthetic"), `references/accessibility-checklist.md`,
  `references/performance-checklist.md`, `agents/web-performance-auditor.md`.
- **`vercel-labs/agent-skills` + `vercel-labs/web-interface-guidelines`** —
  `web-design-guidelines` (audit UI against a ~110-rule checklist),
  `react-best-practices` (70 React/Next performance rules). The guidelines
  `command.md` checklist is captured in this session.

Our layer has code-level frontend engineering (`engineering-standards/
references/frontend-standards.md`) and a visual contract (`DESIGN.md` +
`architecture/references/design-contract.md`), but **no routed capability for
component-level build quality, the WCAG floor, or a UI-specific audit pass**.

Separately, the user has made a **positioning decision**: pivot `apps/web` from
the just-adopted *Confident Modernist* identity (direction C, light-first) to
*Instrument / Terminal* (direction B in `DESIGN.md`): **dark-first**,
high-density, one vivid accent that pops on dark, monospace promoted to
structure. Target: world-class / FAANG-level / futuristic / modern / ultra-fast.

**Outcome:** one new project-local skill + two reference files wired into the
capability layer; `DESIGN.md` rewritten to direction B; `apps/web` migrated to
the new contract with all gates green and React performance rules applied.

---

## The six fields

| Field | Value |
|---|---|
| **Goal** | A routed "instrument-grade frontend" capability in `.claude/`, a dark-first *Instrument* `DESIGN.md`, and `apps/web` fully migrated to it — fast, accessible, distinctive. |
| **Constraints** | Stack unchanged (Next 16 App Router, React 19, Tailwind v4, framer-motion, `next/font` Inter + JetBrains Mono). `.claude/` stays the only canonical source — no second registry, no hand-edits under `vendor/`. New skill must pass `tools/new_skill_check.py` **and** `tools/test_process_router.py`. `DESIGN.md` change goes through `architecture`'s design-contract procedure. Every `apps/web` gate stays green: `vitest run`, `tsc`, `eslint`, `next build`, `@axe-core/playwright` WCAG AA on every public route (light **and** dark), `scripts/check-contrast.mjs`. Keep the 3-block token sync (`:root` / `@media prefers-color-scheme` / `:root[data-theme=...]`). No new colour without a token + dated `decisions/` entry. Preserve all existing tests' intent. |
| **Input** | Vendored `addyosmani-agent-skills`; the Vercel Web Interface Guidelines checklist (in-session); `DESIGN.md` (current, direction C, fully rolled out per commit `f5fe5d2`); `apps/web/src/app/globals.css` token system; ~40 components under `apps/web/src/components/`; existing skills `engineering-standards`, `architecture`, `code-review`, `testing`. |
| **Output** | `.claude/skills/frontend-ui/SKILL.md` (+ `references/`); perf rules added under `engineering-standards/references/`; `workflow.md` off-chain row; updated skill counts wherever validators require. Rewritten `DESIGN.md` (direction B). Migrated `globals.css` (dark as base) + `apps/web` components. New `decisions/2026-09-08-instrument-visual-identity.md`. Updated `docs/plans/`. |
| **Done Checks** | `python tools/new_skill_check.py --all` exits 0 · `python tools/test_process_router.py` exits 0 · `python tools/test_referenced_paths.py` exits 0 · `python tools/run_checks.py --tier all --require-test` exits 0 · in `apps/web/`: `npm run test` exits 0, `npx tsc --noEmit` exits 0, `npm run lint` exits 0, `npm run build` exits 0, `npm run check:contrast` exits 0, `npm run test:e2e` (axe specs) exits 0. |
| **Out of Scope** | `apps/android-app` (separate flat surface, explicitly excluded by `DESIGN.md`). Backend/`services/`. No new npm dependencies. No deploy/publish/merge. Not adopting `wshobson/agents`, `obra/superpowers`, `anthropics/skills` (they overlap our lifecycle layer). No `web-performance-auditor` **agent** in this pass (agents have their own standards suite — deferred). Not vendoring the full `vercel-labs` repo (blocked by spend-guard hook; the extracted checklist suffices). No product copy rewrites beyond token/'component churn. |

Confidence: **82** — Constraints and Done Checks are firm; the open question is how far Phase C's component churn reaches (bounded below by "gates green", not by a fixed file list).

---

## Grounded patterns

| Category | Pattern in repo | Reference |
|---|---|---|
| Skill layout | `SKILL.md` + `references/*.md`; frontmatter `name/description/effort/model/allowed-tools` | `.claude/skills/engineering-standards/` |
| Skill routing prose | "Consulted, not entered as a stage"; explicit handoff table | `engineering-standards/SKILL.md` "Routing" |
| Adding a skill | two disjoint validators; description ≤700 chars, first sentence ≤12 words, ≥6 quoted triggers, literal "Use this whenever/proactively", ≥3 ordered steps in body; a `workflow.md` row | `.claude/skills/capability-layer-maintenance/references/adding-a-skill-or-agent.md` |
| Design contract | direction table + "Why X wins" + token tables (light/dark) + component deltas + motion + a11y floor | current `DESIGN.md`; `architecture/references/design-contract.md` |
| Token system | raw vars on `:root`, `@media (prefers-color-scheme: dark)`, `:root[data-theme=...]`; `@theme inline` maps to `--color-*`; `@utility eyebrow/data` | `apps/web/src/app/globals.css` |
| Component variants | `cn()` + `Record<Variant,string>`; framer-motion `motion.button`; hover = colour + 1px lift, never scale | `apps/web/src/components/ui/Button.tsx` |
| Theme toggle | `data-theme` attribute, system default | `apps/web/src/components/ui/ThemeToggle.tsx` |
| Contrast gate | `apps/web/scripts/check-contrast.mjs`, `npm run check:contrast` | `apps/web/package.json` |
| a11y gate | `@axe-core/playwright` in `apps/web/e2e/` | `apps/web/playwright.config.ts` |

**Memory / decisions check:** `decisions/` holds `0001`–`0011` (backend/similarity;
none touch web visual identity). `DESIGN.md` header cites
`docs/plans/2026-09-07-web-visual-identity-round1.md` (the direction-C rollout).
No `MEMORY.md`/`ISSUES.md` entry names `globals.css` or `apps/web/src/components/`.
The direction-C rollout is recent and complete — this plan **supersedes** it and
must say so in the new `decisions/` record.

---

## Progress

- [x] A1 — Draft `frontend-ui` skill (SKILL.md + references) from the vendored sources
- [x] A2 — Wire it into the layer (workflow.md row, counts, routing delineation vs `engineering-standards`)
- [x] A3 — Add React/Next performance rules to `engineering-standards/references/`
- [x] A4 — Layer validators green
- [x] B1 — Rewrite `DESIGN.md` to direction B (Instrument, dark-first) via design-contract procedure
- [x] B2 — Write `decisions/0012-instrument-visual-identity.md` (supersedes direction C)

> **Deviations (reconciled):**
> - Executed serially in the main context, not via `tools/parallel_groups.py` — the tool needs `### Task N:` headings this phased plan does not use, and Phases A→B→C are strictly ordered anyway.
> - Decision record is `decisions/0012-instrument-visual-identity.md` (numbered, per `decisions/README.md`), not the date-named path in the task text.
> - Vendored `addyosmani-agent-skills` trimmed to skill/agent/reference content only (harness-config trees removed) — 53 files, not the full 250-file repo. Provenance (URL + commit `6ca0cd7`) is in the adapted skill's frontmatter.
- [x] C1 — `globals.css`: dark is the base palette; light is `@media (prefers-color-scheme: light)` + `[data-theme]` override; `check:contrast` green on both themes; `check-contrast.mjs` mirror dicts + `layout.tsx` theme-color updated; `color-scheme: dark` on `:root`.
- [x] C2 — UI primitives: Card (p-4, rounded-sm, no `active:scale`, y:-1 glow), Input (h-10, rounded-xs), Modal (surface-raised, border-strong, page-bg 72% scrim, y:8), + Card.test updated. Button already compliant (kept h-12/h-14; DESIGN.md reconciled to match).
- [x] C3 — Shell: AppShell `active:scale-90` → `active:opacity-60`; CommandPalette scrim → page-bg 72%, blur dropped. Token layer carries colour/elevation/motion automatically.
- [x] C4 — Feature surfaces: swept for the direction-B tells — avatar `text-white` → `text-text-on-primary` (profile, IdentityRailCard, PeerSuggestionsCard, ProfileMenu×2, TopBar, PresenceStack) so initials stay AA on the bright dark-theme accent fills. No `rounded-2xl`/`shadow-2xl` slabs existed (direction-C rollout already removed the AI tells); remaining per-surface work is token-driven and needs no edit.
- [x] C5 — Performance: audited against `frontend-performance-rules.md`. **No safe unmeasured win found** — no barrel imports, no await waterfalls (the `await` chains are sequential-by-necessity mutation flows), API routes already carry `next: { revalidate }`, result sets are bounded (click-only UX, nothing needs virtualization). `content-visibility` deferred pending a real measurement per the reference's own "measure first" rule.
- [x] C6 — Gate: `npm run test` (110 ✓), `tsc` ✓, `lint` ✓ (0 errors; 1 pre-existing unrelated warning), `build` ✓, `check:contrast` ✓ both themes, `test:e2e` 18/19 (axe WCAG AA specs pass; the 1 failure — `smoke.spec.ts:13` Firebase-not-configured notice — is **pre-existing**, verified against the stashed pre-change tree, and is an env/config issue unrelated to this work). Root `run_checks.py --tier all --require-test` ✓.

> **Phase C deviations (reconciled):**
> - The token architecture carried the bulk of the pivot (colour, elevation, motion, radius scale, type scale) with no per-component edits — so C3/C4 are targeted fixes (the direction-B tells) + a sweep, not a 40-file rewrite. DESIGN.md's per-component deltas describe the intended end state; where a primitive was already compliant it was left and DESIGN.md reconciled to it.
> - `smoke.spec.ts:13` fails pre-existing (not introduced here); left as-is — outside this plan's scope.
- [ ] D1 — `documentation`: LOG / HANDOFF / MEMORY / plan status

---

## Tasks

### Phase A — Capability layer (own round; control-surface)

#### A1 — Draft the `frontend-ui` skill
- **Files:**
  - Create: `.claude/skills/frontend-ui/SKILL.md`
  - Create: `.claude/skills/frontend-ui/references/ui-build-checklist.md` (from `vendor/harness-skills/addyosmani-agent-skills/skills/frontend-ui-engineering/SKILL.md` + `references/accessibility-checklist.md`)
  - Create: `.claude/skills/frontend-ui/references/web-interface-guidelines.md` (the captured Vercel checklist — verbatim, with a source/date header)
- **Content:** capability = "Build and audit production-grade, accessible, distinctive user-facing UI." Body ≥3 ordered phases: **1.** SCAN the surface + read `DESIGN.md` and the two checklists; **2.** BUILD/FIX against the checklist (component boundaries, states, WCAG floor, "avoid the AI aesthetic" table, motion, responsive breakpoints); **3.** VERIFY (axe, contrast, keyboard walk, the checklist's own verification list). Description: first sentence ≤12 words, ≥6 quoted triggers ("build the UI", "make this component production-quality", "check accessibility", "audit the design", "review my UI", "why does this look AI-generated", "improve the UX"), literal "Use this whenever". `effort: high`, `model: sonnet`, `allowed-tools: Read Grep Glob Bash`.
- **Provenance block** (Notion rule): source URL → upstream commit `6ca0cd7` → verified 2026-09-08 → applies to Next 16 / React 19 / Tailwind v4 → validation test `npm run test:e2e` axe specs → review trigger: next major Tailwind/Next.
- **Dependencies:** none.
- **Verification:** `python tools/new_skill_check.py frontend-ui` exits 0.

#### A2 — Wire `frontend-ui` into the layer
- **Files:**
  - Modify: `.claude/workflow.md` — add an "Off-chain capabilities" row: `| Build UI | \`frontend-ui\` | a user-facing surface being built or audited for quality, accessibility, and distinctiveness against \`DESIGN.md\` |`
  - Modify: `engineering-standards/SKILL.md` — one line in "Routing" delineating the boundary (this skill = code architecture/state/data-fetching; `frontend-ui` = visual build quality + WCAG floor + component-level a11y + audit).
  - Modify: `frontend-ui/SKILL.md` — reciprocal "Routing" line naming `engineering-standards`, `code-review`, `architecture`/`DESIGN.md`.
  - Modify: skill-count strings **only where a validator flags them** — run `tools/test_referenced_paths.py` and fix each named file (candidates: `README.md`, `MEMORY.md`; `.claude/README.md` and `docs/evals/` do not exist in this repo, so the `adding-a-skill` reference over-lists — follow the validator, not the doc).
- **Dependencies:** 1.
- **Verification:** `python tools/test_process_router.py` exits 0; `python tools/test_referenced_paths.py` exits 0.

#### A3 — React/Next performance rules into engineering-standards
- **Files:**
  - Create: `.claude/skills/engineering-standards/references/frontend-performance-rules.md` — the `react-best-practices` rule set condensed by category (waterfalls, bundle size, server perf, client fetching, re-render, rendering, JS, advanced), each rule one bullet with the anti-pattern → fix and impact figure. Header cites `vercel-labs/agent-skills` `react-best-practices` + date.
  - Modify: `.claude/skills/engineering-standards/references/frontend-standards.md` — add a "Performance rules" pointer section linking the new file; keep Core Web Vitals budgets where they are.
- **Dependencies:** none (parallel with 1).
- **Verification:** `python tools/new_skill_check.py --all` exits 0 (advisory backtick-reference check passes).

#### A4 — Layer validators
- **Files:** none (fix-forward only if red).
- **Dependencies:** 2, 3.
- **Verification:** `python tools/run_checks.py --tier all --require-test` exits 0.

### Phase B — Design contract pivot (depends on A for the checklists it cites)

#### B1 — Rewrite `DESIGN.md` to direction B (Instrument / dark-first)
- **Files:** Modify: `DESIGN.md` (root).
- **Procedure:** follow `.claude/skills/architecture/references/design-contract.md`. Read `.claude/rules/ui-ux-resources.md` and `references/platform-guidance.md` (web) first.
- **Deltas from the current file:**
  - **Status/Direction:** direction **B adopted**, supersedes direction C; record the Gate-1 positioning decision and date. Keep the "Approaches considered" table; move the ✓ to B; rewrite "Why B wins" around the user's brief (futuristic, FAANG, ultra-fast, dark-native instrument).
  - **The single feeling:** keep "authoritative instrument" but commit to dark-native.
  - **Color tokens:** dark palette becomes the primary column. New base ground ~`#0b0c0e`–`#101215` (near-black, faint cool cast), surfaces `#15171a` / `#1c1f23`, hairlines `#26292e`. One vivid accent that pops on dark for `--primary` (e.g. `#7c6cff` indigo-violet per the B sketch) + a live-green signal (`#3ddc84`-ish) for "live/new"; `--accent-signal` stays warm for the single primary action. Light theme retained as a first-class **override** (`[data-theme="light"]` + `@media (prefers-color-scheme: light)`), not dropped — dark is just the default. Every pair re-verified with `check-contrast.mjs` (targets in the table).
  - **Type:** mono promoted further — mono for all eyebrows, data, IDs, timestamps, table headers, and nav labels; Inter for prose/headings. Tighten the scale one notch for density.
  - **Layout:** density up (row heights, padding scale trimmed at the low end); hairline-grid backbone becomes mandatory, section numerals in mono.
  - **Motion:** keep "no scale-pop"; add fast, mechanical transitions (120–160ms), `prefers-reduced-motion` honored; optional View Transitions API note for route changes (no dependency — native).
  - **Accessibility floor:** restate AA on the dark base; focus ring = signal-derived, offset; contrast table with dark-first numbers.
  - **Components:** update each delta block (Button/Card/Nav/Segmented/Forms/Data display/Dialogs) for dark-native values.
- **Dependencies:** A4 (so it can cite `.claude/skills/frontend-ui/` and the guidelines reference).
- **Verification:** `DESIGN.md` contains a dark-first token table with both columns; `grep -c "direction C" DESIGN.md` reflects "superseded" framing; manual read confirms every component delta names concrete tokens. (No code yet.)

#### B2 — Decision record
- **Files:** Create: `decisions/2026-09-08-instrument-visual-identity.md` — why the pivot (positioning call, user-confirmed), what it supersedes (`docs/plans/2026-09-07-web-visual-identity-round1.md` + direction C), the cost accepted (re-migration of ~40 components, re-run of the WCAG pass), rollback (revert `DESIGN.md` + `globals.css` to the `f5fe5d2` state).
- **Dependencies:** B1.
- **Verification:** `python tools/run_checks.py --scoped` picks up the new decision file without error.

### Phase C — Apply across `apps/web` (depends on B)

#### C1 — Token layer: dark as base
- **Files:** Modify: `apps/web/src/app/globals.css`.
- **Change:** move the dark values onto bare `:root`; convert the current light `:root` block into `@media (prefers-color-scheme: light)` + keep `:root[data-theme="light"]`; keep `:root[data-theme="dark"]` as an explicit echo of the base. Update `<meta name="theme-color">` and add `color-scheme: dark` to `<html>` (`app/layout.tsx`). Apply the new hues/radii/spacing from `DESIGN.md` B1. Update `scripts/check-contrast.mjs` pair list if token names change.
- **Dependencies:** B1.
- **Verification:** `cd apps/web && npm run check:contrast` exits 0.

#### C2 — UI primitives
- **Files:** Modify: `apps/web/src/components/ui/*` (`Button`, `Card`, `Input`, `Badge`, `Modal`, `SegmentedControl`, `ThemeToggle` default, `ErrorBanner`, `MagneticCTA`, `Reveal`, `AnimatedCounter`, `MathText`) + their `.test.tsx` where assertions encode colours/classes.
- **Change:** dark-native variant values per `DESIGN.md` Components; `ThemeToggle` default state = dark; remove any residual light-only assumptions; motion to the 120–160ms mechanical curve.
- **Dependencies:** C1.
- **Verification:** `cd apps/web && npm run test -- src/components/ui` exits 0; `npx tsc --noEmit` exits 0.

#### C3 — Layout & shell
- **Files:** Modify: `apps/web/src/components/layout/*` (`AppShell`, `TopBar`, `RailShell`, `ProfileMenu`, `NotificationsBell`), `apps/web/src/app/(app)/layout.tsx`, `apps/web/src/components/MotionProvider.tsx`, `apps/web/src/app/layout.tsx`.
- **Dependencies:** C2.
- **Verification:** `cd apps/web && npm run test -- src/components/layout` exits 0.

#### C4 — Feature surfaces
- **Files:** Modify, by feature folder: `components/discovery/*`, `components/feed/*`, `components/author/*`, `components/paper/*`, `components/workspace/*`, `components/nexus/*`, `components/horizon/*`, `components/landing/*`, `components/auth/*`, `components/command/*`; pages under `app/` (`page.tsx`, `login`, `signup`, `onboarding`, `(app)/*`, `error/not-found/global-error`).
- **Change:** token swaps, density, mono-label promotion, hairline structure, states (loading/empty/error) audited against the `frontend-ui` checklist. No behaviour change.
- **Dependencies:** C3. Can be split per-folder into parallel sub-tasks with disjoint files if dispatched.
- **Verification:** `cd apps/web && npm run test` exits 0; `npm run build` exits 0.

#### C5 — Performance pass
- **Files:** Modify: data-fetching call sites (`app/**/page.tsx`, `src/lib/`), list renderers (leaderboard, feed, related papers).
- **Change:** apply `frontend-performance-rules.md` — parallelize independent `await`s (`Promise.all`), Suspense boundaries for layout-first paint, `content-visibility: auto` on long lists, virtualization only past the ~50-item threshold, drop barrel imports on hot routes, `React.cache()` for per-request dedupe where server components re-fetch.
- **Dependencies:** C4.
- **Verification:** `cd apps/web && npm run build` exits 0 and route bundle sizes in the build output are ≤ current; `npm run test:e2e` exits 0.

#### C6 — Full gate
- **Files:** none (fix-forward).
- **Dependencies:** C5.
- **Verification:** in `apps/web/`: `npm run test`, `npx tsc --noEmit`, `npm run lint`, `npm run build`, `npm run check:contrast`, `npm run test:e2e` — all exit 0. At root: `python tools/run_checks.py --tier all --require-test` exits 0.

### Phase D — Record

#### D1 — Documentation
- Hand to `documentation`: `LOG.md`, `HANDOFF.md`, `MEMORY.md` (new pointer for the skill + the identity pivot), `TASK.md` status, move this plan to `docs/plans/2026-09-08-instrument-frontend.md` and mark `## Approved`.
- **Dependencies:** C6.

---

## Risks & rollback

| Risk | Mitigation / rollback |
|---|---|
| Pivot churns a 2-day-old completed rollout (`f5fe5d2`) | Decision record B2 states the supersession explicitly; rollback = `git checkout f5fe5d2 -- DESIGN.md apps/web/src/app/globals.css` and revert component commits. Phase C is staged so each sub-round is independently revertible. |
| Dark-first breaks the WCAG pass on some route | `check:contrast` + axe light+dark are Done Checks on C1 and C6; a failing pair blocks the phase, not just warns. |
| Adding a skill destabilises routing (Notion's EVOHARNESSBENCH warning) | One skill, not three; the other two sources become **reference files**, not routable skills. Reciprocal routing lines + `test_process_router.py` guard the boundary with `engineering-standards`. |
| `adding-a-skill` reference lists count-locations that don't exist here | Follow `test_referenced_paths.py`'s actual output, not the doc (noted in A2). |
| Component churn scope unbounded | Bounded by "gates green" + "no behaviour change"; `.test.tsx` intent preserved; per-folder split keeps each task reviewable. |
| `vercel-labs` repo can't be vendored (spend-guard hook blocks `git clone …vercel…`) | The Web Interface Guidelines `command.md` checklist is captured verbatim in-session and lands as `frontend-ui/references/web-interface-guidelines.md`; `react-best-practices` rules land as the engineering-standards reference. Full repo not needed. |

## Verification summary (end to end)

1. **Layer:** `python tools/new_skill_check.py --all` · `python tools/test_process_router.py` · `python tools/test_referenced_paths.py` · `python tools/run_checks.py --tier all --require-test` — all exit 0.
2. **Contract:** `DESIGN.md` reads as direction B, dark-first token table present, every component delta cites concrete tokens; `decisions/2026-09-08-instrument-visual-identity.md` exists.
3. **Web:** in `apps/web/` — `npm run test`, `npx tsc --noEmit`, `npm run lint`, `npm run build`, `npm run check:contrast`, `npm run test:e2e` — all exit 0, axe WCAG AA clean on every public route in **both** themes, no route bundle larger than today.
4. **Visual:** run the app (`npm run dev`), walk discovery / home / paper / author / workspace in dark (default) and light; confirm the instrument feel, hairline structure, mono data, single signal region per viewport, no scale-pop.

## Approved

Gate 1 approved 2026-09-08 (AskUserQuestion: direction B + full applied overhaul). Executed on branch `feat/instrument-frontend`; commits 7844a28 (Phase A), 7616035 (Phase B), 8f6151e (Phase C).
