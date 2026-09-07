# Web Visual Identity — Round 1: tokens, primitives, Landing reference screen

**Goal:** Land the "Confident Modernist" token system and shared primitives, and
rebuild the Landing page to `DESIGN.md`, with a rendered desktop/tablet/mobile/
dark identity sign-off.

**Source brief:** `TASK.md`, plus `DESIGN.md` (root) and
`docs/specs/2026-09-01-web-world-class-design.md`.

**Slug:** design/web-visual-contract

**Risk:** high — `python tools/scope.py` reports `risk: high -- the change
could not be classified, and an unclassifiable change is never low-risk`
(its path clauses do not cover an `apps/web` visual-layer change). Substantively
this is a medium-blast, no-sensitive-surface change (no auth, credentials,
installer, packaging or CI config), but the classifier's verdict stands and
Gate 2 is not skipped on that basis.

**Blast radius:** every `apps/web` screen (via `globals.css` tokens + `ui/Button`
+ `ui/Card`), the Discovery mode toggle (via the `SegmentedControl` extraction),
and the Landing page. No backend, no Android, no `.claude/` layer. Dark mode and
the existing WCAG work are preserved, not replaced.

**Rollback:** worst landing state is tokens + primitives merged, Landing
half-done. `git revert` the round's commits; `globals.css` and the primitives
return to their current values; no data migration, no persisted state, nothing
left behind. The `DESIGN.md` document stays regardless (it is the contract, not
the change).

**Architecture:** Tailwind v4 CSS-first — tokens are bare custom properties in
`globals.css`, mapped into Tailwind's `--color-*` / `--text-*` / `--shadow-*`
namespaces via `@theme inline` so semantic utilities need no `dark:` prefix (the
underlying variable swaps per scheme). Three synced blocks (`:root`,
`@media (prefers-color-scheme: dark)`, `:root[data-theme=...]`). Primitives are
`forwardRef` components in `src/components/ui/` with `framer-motion` for
interaction and `cn()` for class merging — this plan follows that shape exactly,
it does not introduce a new pattern.

**Tech stack and constraints:**
- Next.js 16 App Router, React 19, Tailwind v4 (`@tailwindcss/postcss`, no
  `tailwind.config`), framer-motion 12, lucide-react, `next/font/google`.
- Tests: Vitest + RTL + jsdom (`vitest.config.ts`, `css: false` — so token
  colour/contrast is **not** unit-testable; a standalone contrast script and
  Playwright cover it). MSW at the network boundary. Playwright + `@axe-core/
  playwright` for e2e/a11y. Colocated `*.test.tsx`.
- `apps/web/AGENTS.md`: Next 16 has breaking changes — verify APIs against
  `node_modules/next/dist/docs/`, not training data.
- **Typeface (Gate 1 resolution): swap Space Grotesk → Inter.** `DESIGN.md` now
  specifies a two-family stack (Inter for display + body, JetBrains Mono for
  data) — "LinkedIn-style" humanist professional typography, hierarchy from
  weight + scale not a display face. Done in Task 1 via `--font-display` remap
  and the `layout.tsx` import removal; every `font-display` class keeps working.
  Adding a *new* font (Source Sans etc.) stays out of scope.
- Keep the existing landing copy — it already matches `DESIGN.md` voice.
- One `signal` action per viewport; never `--primary` and `--accent-signal`
  both filling a CTA on the same screen.

---

## Context

`can you design more beautiful UI for our web app using Figma?` — the user then
chose a **bolder visual identity**, all surfaces in scope, best-practice path.
Figma editor access is unavailable (view-only seat, no linked file), so the
deliverable is code + a written contract, not a Figma file.

The design contract now exists: **`DESIGN.md`** at the repo root (committed on
branch `design/web-visual-contract`), direction **"Confident Modernist"** —
keep the calm light ground and the `globals.css` token architecture, inject
identity through scale, saturation, a warm signal colour for action, the metric
palette promoted to brand-level data-viz, and disciplined geometry. Directions A
(editorial/serif) and B (dark-first terminal) were considered and recorded as
rejected.

`DESIGN.md`'s own Rollout section says the identity lands **screen by screen**,
proven on a reference screen first. **This plan is Round 1 only:** the token
changes, the shared primitives every later screen depends on, and the **Landing
page** as the reference screen — rendered at desktop/tablet/mobile/dark as the
identity sign-off. Discovery, auth, core app, and workspace/nexus/horizon are
each their own later plan, evaluated once Round 1's identity is signed off.

**Prior art that constrains this:** `docs/specs/2026-09-01-web-world-class-design.md`
explicitly put "Visual redesign, new tokens, motion changes" out of its scope
and deferred them to the design contract — this plan is that deferred work.

---

## Approved

Approved via `ExitPlanMode` on 2026-09-07. Direction C, Inter font swap, Landing
reference screen — all confirmed (see resolutions below).

## Gate 1 resolutions

The three open questions were answered before implementation started:

- **Brand positioning:** direction **C ("Confident Modernist")** confirmed —
  "do recommended / industry-standard best practice." No pivot.
- **Brand colour + fonts:** no existing brand assets; palette delegated
  ("if you have better solid colors, use them"). `DESIGN.md` now specifies
  `--primary #3552cf` (professional royal-indigo) and `--accent-signal #c9401f`
  (warm coral); Task 1's `check-contrast.mjs` verifies and tunes. **Fonts:**
  "LinkedIn-style" — swap Space Grotesk → Inter, two-family stack (see
  Constraints).
- **Reference screen:** **Landing first** confirmed ("do recommended,
  industry-standard best practice"). Tasks 5–6 as written.

---

## Grounding — patterns this plan mirrors

| Category | Pattern in repo | Cite |
|---|---|---|
| Token definition | Bare custom props in 3 synced blocks, mapped via `@theme inline`; radius via static `@theme` | `apps/web/src/app/globals.css:26-239` |
| Primitive shape | `forwardRef`, `variants: Record<Variant,string>`, `cn()` merge, `motion.button` with conflicting handlers `Omit`-ted | `apps/web/src/components/ui/Button.tsx:35-96` |
| Motion constants | JS-side timing in `lib/motion.ts` (`TRANSITION_FAST`, `EASE_STANDARD`) — framer-motion needs JS values, not CSS vars | `apps/web/src/lib/motion.ts:20-33` |
| Sliding-pill toggle | `layoutId` shared-element pill + spring, `whileTap` scale, `capitalize` labels | `apps/web/src/app/(app)/discovery/page.tsx:91-116` |
| Colour-blend for AA | `color-mix(... 62%, var(--text-primary))` to lift small tinted text to AA | `apps/web/src/components/ui/Badge.tsx:23-27` |
| Component test | `renderWithProviders` from `src/test/render.tsx`, `vi.mock` for `next/navigation` + `AuthProvider`, RTL role/text queries | `apps/web/src/app/page.test.tsx:1-40` |
| e2e/a11y | `playwright test` via `test:e2e` script; `@axe-core/playwright` in devDeps | `apps/web/package.json` |

**Repo memory (`tools/memory.py --paths` on the affected files):** 2 hits, both
backend recommendation-endpoint decisions (`0007`, `0008`) — **neither relevant**
to the visual layer. Nothing in `MEMORY.md` / `ISSUES.md` / `decisions/` governs
`globals.css` or the `ui/` primitives.

---

## File map

| File | Action | Owns after |
|---|---|---|
| `apps/web/src/app/globals.css` | Modify | The full token set incl. `--primary` (`#3552cf`/`#93a5ff`), `--accent-signal`/`-dark` (`#c9401f`/`#ff7a5c`), dark values for `--accent-orange/violet/cyan/pink/emerald`, `--ring` (signal-derived), `--shadow-signal`, type-scale `--text-*` tokens, `.eyebrow`/`.data` utilities, and `--font-display` remapped to `var(--font-inter)` |
| `apps/web/src/app/layout.tsx` | Modify | Remove the `Space_Grotesk` import + its `.variable` from the `<html>` className |
| `apps/web/scripts/check-contrast.mjs` | Create | Standalone WCAG relative-luminance contrast check for the token pairs; exits 1 on any miss |
| `apps/web/package.json` | Modify | Adds `"check:contrast"` script |
| `apps/web/src/components/ui/Button.tsx` | Modify | Adds `signal` variant + `size` prop; removes `scale` hover/tap |
| `apps/web/src/components/ui/MagneticCTA.tsx` | Modify | Signal fill + `--shadow-signal`; drops `scale` hover/tap (keeps magnetic translate); padding on grid |
| `apps/web/src/components/ui/Button.test.tsx` | Create | Variant rendering, disabled, loading-hides-label |
| `apps/web/src/components/ui/Card.tsx` | Modify | `p-5` default; `accentSide?: "top" \| "left"` (default `top`) |
| `apps/web/src/components/ui/Card.test.tsx` | Create | `accentSide="left"` border, default padding, interactive hover class |
| `apps/web/src/components/ui/SegmentedControl.tsx` | Create | Generic sliding-pill segmented control (from the Discovery inline toggle) |
| `apps/web/src/app/(app)/discovery/page.tsx` | Modify | `modeToggle` inline markup → `<SegmentedControl>` (behaviour identical) |
| `apps/web/src/components/ui/SegmentedControl.test.tsx` | Create | Options render, `onChange` fires, selected marked via `aria-*` |
| `apps/web/src/app/page.tsx` | Modify | Landing rebuilt to `DESIGN.md` — type scale, 4px grid, eyebrow/numeral structure, signal CTA, metric-palette accents |
| `apps/web/src/app/page.test.tsx` | Modify | Keep the 3 behaviour assertions; adjust selectors only if structure moves |
| `apps/web/e2e/landing-visual.spec.ts` | Create | Playwright: axe clean + screenshots at 1440 / 834 / 390 and dark |

Every `DESIGN.md` requirement maps here: tokens/type/spacing → Task 1; button &
card & segmented-control component rules → Tasks 2-4; the Landing surface + its
voice/anti-pattern compliance → Task 5; the accessibility floor + the "render
and inspect" rule → Tasks 1 (contrast) and 6 (axe + visual).

---

## Constitution gate
- [x] I Evidence — every task names an exact command and its expected output
- [x] II Test first — Tasks 2-4 write the failing `*.test.tsx` before the change;
      Task 1's failing check is `check-contrast.mjs` red on the old hue set only
      if a proposed hue misses (else it is a characterization check); Task 5's is
      the existing `page.test.tsx`; Task 6's is the axe assertion
- [x] III Smallest change — no primitive refactor beyond the variant/prop added;
      Discovery is touched only to consume the extracted control, not redesigned
- [x] IV Reversibility — pure `git revert`; no migration, no persisted state
- [x] V No silent degradation — token colour/contrast is not unit-testable
      (`css: false`); that gap is covered explicitly by `check-contrast.mjs`
      (Task 1) and Playwright axe (Task 6), not skipped
- [x] VI Mechanism — the contract is enforced by `check-contrast.mjs` (tokens),
      the new component tests (primitive rules), and `refactoring`'s post-round
      sweep against `DESIGN.md`
- [x] VII Secrets — none involved

## Complexity tracking
(no unticked boxes)

---

## Progress
- [x] Task 1 — Token system in globals.css + contrast check
- [x] Task 2 — Button/MagneticCTA: signal variant, remove scale hover
- [x] Task 3 — Card: p-5 default + left accent bar
- [x] Task 4 — Extract SegmentedControl, adopt in Discovery
- [x] Task 5 — Rebuild Landing to the contract
- [x] Task 6 — Landing visual QA + identity sign-off render

---

## Tasks

### Task 1: Token system in globals.css + contrast check
**Purpose:** the "Confident Modernist" tokens exist and every token colour pair
provably clears its WCAG threshold on both themes.
**Files:**
- Modify: `apps/web/src/app/globals.css` — in all three token blocks
  (`:root` line ~26, `@media (prefers-color-scheme: dark)` line ~84,
  `:root[data-theme="dark"]` line ~124, `:root[data-theme="light"]` line ~151):
  set `--primary` / `--primary-dark` / `--primary-deeper` to the new
  professional royal-indigo (`#3552cf` / `#2c46b8` / `#233a99` light;
  `#93a5ff` / `#7f92f5` / `#6b80ea` dark); add `--accent-signal` /
  `--accent-signal-dark` (`#c9401f` / `#af3819` light; `#ff7a5c` / `#ff6749`
  dark); add the missing dark values for `--accent-orange` `--accent-violet`
  `--accent-cyan` `--accent-pink` `--accent-emerald` (per `DESIGN.md` data-viz
  table); change `--ring` to
  `color-mix(in srgb, var(--accent-signal) 30%, transparent)`; add
  `--shadow-signal: 0 6px 20px color-mix(in srgb, var(--accent-signal) 28%, transparent)`.
  **Remap `--font-display`** (currently `var(--font-space-grotesk), ...`) to
  `var(--font-inter), ui-sans-serif, system-ui, sans-serif`.
  In `@theme inline`: map `--color-accent-signal`, `--color-accent-signal-dark`,
  and `--shadow-signal`. Add a static type scale (`@theme`): `--text-display-xl`
  … `--text-eyebrow` per `DESIGN.md`'s Scale table with matching `--text-*--line-height`.
  Add `@utility eyebrow { ... }` (mono, 11.5px, `0.08em`, uppercase,
  `--text-muted`) and `@utility data { font-variant-numeric: tabular-nums; ... }`.
- Modify: `apps/web/src/app/layout.tsx` — remove the `Space_Grotesk` import and
  the `spaceGrotesk` const; drop `${spaceGrotesk.variable}` from the `<html>`
  `className`. Leave `inter` and `jetbrainsMono` untouched. (`DESIGN.md` Type
  section: LinkedIn-style two-family stack.)
- Create: `apps/web/scripts/check-contrast.mjs` — pure Node, no deps: sRGB→
  relative-luminance→contrast-ratio (WCAG 2.1 formula). Assert, for light and
  dark: `text-on-primary` vs `primary` ≥ 4.5; `text-on-primary` vs
  `accent-signal` ≥ 4.5; `primary` vs `surface` ≥ 3; `accent-signal` vs
  `surface` ≥ 3; `text-primary/secondary/muted` vs `surface` & `surface-subtle`
  ≥ 4.5. Hard-code the hex values (mirrors `globals.css`; a drift is caught by
  eye in review and by Task 6). Print a `token pair | ratio | threshold | PASS/
  FAIL` table; `process.exit(fails ? 1 : 0)`.
- Modify: `apps/web/package.json` — add `"check:contrast": "node scripts/check-contrast.mjs"`.
**Dependencies:** none
**Implementation notes:** keep the three-block sync — a value added to one block
and missed in another is the classic bug here (see the `globals.css` header
comment). Tailwind v4 generates `text-display-xl` etc. from `--text-*` names.
`@utility` is the v4 replacement for `@layer utilities` custom classes. Do not
touch `--motion-*`, radius, or the shadow scale beyond adding `--shadow-signal`.
**Rollback:** `git checkout globals.css package.json src/app/layout.tsx`; delete
the script.
**Preconditions:** on branch `design/web-visual-contract`.
**Verification:**
- Run: `cd apps/web && node scripts/check-contrast.mjs`
- Expect: table prints; every row `PASS`; exit 0. (If a proposed hue FAILs,
  darken/lighten within the hue family, update `globals.css` + the script, and
  record the final value in `DESIGN.md`'s token table — this is expected
  iteration, not a blocker.)
- Run: `cd apps/web && npx tsc --noEmit && npm run build`
- Expect: build succeeds; no CSS parse error from the new `@theme` / `@utility`;
  no unresolved `--font-space-grotesk` reference
  (`grep -rn "space.grotesk\|Space_Grotesk" src` returns nothing).
- Run: `cd apps/web && npx vitest run` — Expect: the full existing suite still
  green (font/token changes are `css:false`-invisible to jsdom, so this is a
  regression guard, not a new assertion).
**Done when:** `check:contrast` is green, `npm run build` is clean, no Space
Grotesk reference remains, and the existing unit suite is unbroken (visual proof
is Task 6).

### Task 2: Button/MagneticCTA — signal variant, remove scale hover
**Purpose:** the primary-action button uses `--accent-signal`, and the
generic-AI `scale()` hover-pop is gone from both button primitives.
**Files:**
- Test: `apps/web/src/components/ui/Button.test.tsx` — create first, RED:
  renders `<Button variant="signal">Go</Button>` and asserts the rendered
  `<button>` carries the signal background utility (`bg-accent-signal` or the
  class the impl uses) and `text-text-on-primary`; `variant="signal" disabled`
  → `disabled` attr + disabled styles; `loading` → label hidden, spinner
  present, `disabled`. Use `renderWithProviders`.
- Modify: `apps/web/src/components/ui/Button.tsx` — add `"signal"` to `Variant`;
  add its entry to `variants` (`bg-accent-signal text-text-on-primary shadow-card`,
  `error` branch as for `primary`); add `size?: "md" | "lg"` (`lg` → `h-14
  text-[15px]`, default `md` keeps current heights). Remove
  `whileHover={{ scale: 1.025 }}` and `whileTap={{ scale: 0.97 }}`; add
  `transition-[background-color,color,border-color,transform]` and a
  `hover:-translate-y-px active:translate-y-0` on non-inert state, plus
  `hover:` background to the `-dark` token per variant.
- Modify: `apps/web/src/components/ui/MagneticCTA.tsx` — inner `<span>`:
  `bg-primary` → `bg-accent-signal`, `shadow-card` → `shadow-[var(--shadow-signal)]`,
  `px-7 py-3.5` → `px-8 py-4`. Remove `whileHover={{ scale: 1.04 }}` and
  `whileTap={{ scale: 0.96 }}` (keep the magnetic `x/y` spring translate — that
  is the intended motion, not a scale-pop).
**Dependencies:** 1
**Implementation notes:** `signal` is now the recommended variant for the single
primary action; `primary` (indigo) stays for brand/nav actions. Keep
`fullWidth` default and the `MotionSafeButtonAttributes` `Omit`. Do not change
`outlined` / `ghost` / `text`.
**Rollback:** `git checkout` the three files; delete the test.
**Preconditions:** Task 1 merged (`--accent-signal`, `--shadow-signal` exist).
**Verification:**
- Run: `cd apps/web && npx vitest run src/components/ui/Button.test.tsx`
- Expect: was RED before the `Button.tsx` change (assertion on missing `signal`
  class), GREEN after; all cases pass.
- Run: `cd apps/web && npx eslint src/components/ui/Button.tsx src/components/ui/MagneticCTA.tsx`
- Expect: clean.
**Done when:** the test is green and no `scale` remains in either file
(`grep -n "scale" src/components/ui/Button.tsx src/components/ui/MagneticCTA.tsx`
returns nothing).

### Task 3: Card — p-5 default + left accent bar
**Purpose:** cards sit on the 4px grid at `p-5`, and data cards can show a 3px
left accent instead of the 2px top bar.
**Files:**
- Test: `apps/web/src/components/ui/Card.test.tsx` — create first, RED:
  `<Card>` → has `p-5`; `<Card accentColor="#0e7490" accentSide="left">` →
  inline style sets a `3px` left border in that colour and no top border;
  `<Card accentColor="#0e7490">` (default) → 2px `borderTop` as today;
  `<Card interactive>` → carries the hover-translate class.
- Modify: `apps/web/src/components/ui/Card.tsx` — `p-4` → `p-5` in both the
  `glow` and default returns; add `accentSide?: "top" | "left"` (default
  `"top"`); in `sharedStyle`, when `accentSide==="left"` set
  `borderLeft: \`3px solid ${accentColor}\`` and leave `borderTop` as the
  hairline; else keep current `borderTop` accent behaviour.
**Dependencies:** none
**Implementation notes:** default `"top"` keeps every current caller unchanged.
The hover model already uses `translate`/`y`, not `scale` — leave it. `p-5` is
20px, on-grid.
**Rollback:** `git checkout src/components/ui/Card.tsx`; delete the test.
**Preconditions:** none.
**Verification:**
- Run: `cd apps/web && npx vitest run src/components/ui/Card.test.tsx`
- Expect: RED before the change (no `p-5`, no `accentSide`), GREEN after.
- Run: `cd apps/web && npx vitest run src/components/author src/app/page.test.tsx`
- Expect: existing Card consumers' tests still pass (no visual regression in
  behaviour).
**Done when:** the new test is green and existing consumer tests are unbroken.

### Task 4: Extract SegmentedControl, adopt in Discovery
**Purpose:** the sliding-pill toggle is a reusable primitive, proven by
swapping Discovery's inline copy for it with no behaviour change.
**Files:**
- Test: `apps/web/src/components/ui/SegmentedControl.test.tsx` — create first,
  RED: renders `<SegmentedControl options={[{value:"a",label:"A"},{value:"b",
  label:"B"}]} value="a" onChange={fn} />`; both labels present; clicking "B"
  calls `onChange("b")`; the selected option has `aria-selected="true"` (or
  `role="tab"` + `aria-selected`, decide in impl and assert it).
- Create: `apps/web/src/components/ui/SegmentedControl.tsx` — generic version of
  `discovery/page.tsx:91-116`: `options: {value,label}[]`, `value`, `onChange`,
  optional `className`. Keep the `layoutId` shared-element pill + spring
  (`stiffness:400,damping:32`) and the `bg-surface-subtle` track; drop the
  `whileTap` scale (the pill slide is the affordance); mono label option via a
  `mono?: boolean` prop (default false). Roles: `role="tablist"` on the
  container, `role="tab"` + `aria-selected` per option, keyboard arrow support.
- Modify: `apps/web/src/app/(app)/discovery/page.tsx` — replace the `modeToggle`
  JSX (lines ~91-116) with `<SegmentedControl options={[{value:"researchers",
  label:"Researchers"},{value:"papers",label:"Papers"}]} value={mode}
  onChange={(v)=>setMode(v as Mode)} />`. Remove the now-unused `TRANSITION_FAST`
  import if nothing else uses it.
**Dependencies:** none
**Implementation notes:** this is an **extraction**, not a redesign — the
Discovery page's full pass is a later plan. Behaviour and appearance must match
today apart from the removed tap-scale. `layoutId` must be unique if two
controls ever mount together — expose it as an optional prop defaulting to a
stable string.
**Rollback:** `git checkout src/app/(app)/discovery/page.tsx`; delete the new
files.
**Preconditions:** none.
**Verification:**
- Run: `cd apps/web && npx vitest run src/components/ui/SegmentedControl.test.tsx src/app/\(app\)/discovery/page.test.tsx`
- Expect: new test RED before the component exists, GREEN after; the existing
  `discovery/page.test.tsx` stays GREEN (toggle still switches researchers/
  papers).
- Run: `cd apps/web && npx tsc --noEmit`
- Expect: clean (no unused-import error).
**Done when:** both test files pass and Discovery's mode toggle works via the
extracted control.

### Task 5: Rebuild Landing to the contract
**Purpose:** `app/page.tsx` embodies "Confident Modernist" — the reference
screen for every later rollout.
**Files:**
- Modify: `apps/web/src/app/page.tsx` — apply, section by section
  (hero, proof strip, one-idea, outcomes, how-it-works, roles, FAQ, final CTA):
  type scale (`text-display-xl` hero H1, `text-display-m` section heads, body
  `text-[14.5px]`/Body token, `.eyebrow` for the mono uppercase labels that are
  currently ad-hoc `font-mono text-[11px]`); spacing to the 4/8/12/16/24/32/48/
  64/96 scale (replace `pt-10`, `mt-14`, `mt-16`, `gap-3` half-steps and
  friends); add the hairline-rule + mono section-numeral structure on the long
  scroll (`01`–`06` markers, `--text-muted`, `text-[40px]` mono); hero CTA via
  `MagneticCTA` (now signal) + the secondary as `Button variant="text"`; final
  CTA via `Button variant="signal"`; outcome `Card`s use the metric-palette
  accent per card and may switch to `accentSide="left"`; remove any remaining
  `scale` hovers. Keep all copy strings and the `LandingTryDemo` embed.
- Modify: `apps/web/src/app/page.test.tsx` — keep all three `it(...)` behaviour
  assertions; only update a `getByText`/`getByRole` selector if the DOM
  structure around it moved. Do not weaken an assertion to make it pass.
**Dependencies:** 1, 2, 3
**Implementation notes:** `LandingTryDemo`, `Reveal`, `ThemeToggle` are reused
as-is. No new copy, no new sections, no gradient/glass/hero-blob (anti-pattern
list). One `signal` region per viewport — hero CTA and final CTA are in
different viewports, that is allowed.
**Rollback:** `git checkout src/app/page.tsx src/app/page.test.tsx`.
**Preconditions:** Tasks 1-3 merged.
**Verification:**
- Run: `cd apps/web && npx vitest run src/app/page.test.tsx`
- Expect: all three tests green (headline + single CTA; proof/how-it-works/FAQ/
  final-CTA sections; ungated demo renders "Ada Lovelace").
- Run: `cd apps/web && npx eslint src/app/page.tsx && npm run build`
- Expect: clean; build succeeds.
**Done when:** tests + lint + build green and every `DESIGN.md` anti-pattern
checkbox is clear on a source read (the visual proof is Task 6).

### Task 6: Landing visual QA + identity sign-off render
**Purpose:** the rebuilt Landing is rendered and inspected at every breakpoint
and both themes — the "directions to react to", and the accessibility-floor
evidence.
**Files:**
- Create: `apps/web/e2e/landing-visual.spec.ts` — Playwright: `page.goto('/')`;
  `new AxeBuilder({ page }).analyze()` → assert `violations` empty (fail lists
  them); then for each of `{width:1440,height:900}`, `{834,1112}`, `{390,844}`:
  `page.setViewportSize(...)`, `page.screenshot({ path: \`e2e/__screens__/landing-${w}.png\`, fullPage:true })`; then set `data-theme="dark"` on `<html>`
  via `page.emulateMedia`/`addInitScript` or `localStorage.setItem('skolab-theme',
  'dark')` + reload, screenshot `landing-dark.png`.
**Dependencies:** 5
**Implementation notes:** confirm the Playwright config / `test:e2e` wiring and
the dev-server baseURL first (`apps/web/playwright.config.*` — verify it exists;
if not, this task adds a minimal one, which then moves to its own round). `axe`
is `@axe-core/playwright` (already a devDep). Screenshots are artifacts, not
committed baselines (no visual-diff gate in Round 1).
**Rollback:** delete the spec and the screenshots dir.
**Preconditions:** Task 5 merged; dev server runnable (`npm run dev`).
**Verification:**
- Run: `cd apps/web && npx playwright test e2e/landing-visual.spec.ts --project=chromium`
- Expect: passes; `e2e/__screens__/landing-{1440,834,390,dark}.png` exist.
- Then: open each screenshot and check against `DESIGN.md` — token compliance
  (no invented colour/size/spacing), the type scale reads with authority at the
  top, no anti-pattern (gradient/glass/blob, off-grid spacing, scale-pop),
  hairline+numeral structure present, dark mode correct. Record the compact QA
  block:
  ```
  Surface/state: Landing — desktop 1440 / tablet 834 / mobile 390 / dark
  Check: token + anti-pattern + axe (WCAG AA)
  Result: <observed>
  Exception: <none, or the deliberate one + its evidence path>
  ```
**Done when:** axe is clean at all sizes, the screenshots exist, and the QA
block is filled with a pass (or a named, dated exception).

**QA result (2026-09-07):**
```
Surface/state: Landing — desktop 1440 / tablet 834 / mobile 390, light + dark
Check: WCAG AA (axe, wcag2a+wcag2aa) + DESIGN.md token / anti-pattern read
Result: PASS. axe 6/6 green (0 serious/critical). Screenshots at
  apps/web/e2e/__screens__/landing-{desktop,tablet,mobile}-{light,dark}.png.
  Type reads with authority (Inter 700 @ 60px hero, 32px section bodies);
  mono `.eyebrow` + 01–03 numerals + hairline rules present; coral signal CTA
  is the one action colour, one per viewport; outcome cards keep the top
  accent bar (feature cards); spacing on the 96px section grid; no gradient /
  glass / blob / scale-pop. Dark mode: warm-charcoal ground, periwinkle
  primary, coral signal — correct.
Exception: none. Note: the initial spec screenshotted before <Reveal> sections
  scrolled into view (blank mid-page); fixed by scrolling the page to trigger
  every whileInView before the shot + axe. A small left/right dev-overlay
  artefact in the shots is `next dev` chrome, absent from a production build.
```

---

## Verification (end to end)

1. `cd apps/web && node scripts/check-contrast.mjs` → all token pairs PASS.
2. `cd apps/web && npx vitest run` → full unit suite green (new primitive tests
   + unchanged existing tests, incl. `discovery` and `page`).
3. `cd apps/web && npm run build && npx tsc --noEmit && npx eslint .` → clean.
4. `cd apps/web && npx playwright test e2e/landing-visual.spec.ts --project=chromium`
   → axe clean at 1440/834/390 + dark; screenshots written.
5. Manual: view the four screenshots against `DESIGN.md`'s token table and
   anti-pattern list; fill the Task 6 QA block.
6. Post-round: run `refactoring` scoped to `apps/web` against `DESIGN.md`
   (token/state/a11y/anti-pattern sweep) before this branch is delivered.

## Out of scope

- Discovery, auth, home, `paper/[id]`, `author/[id]`, workspace, nexus, horizon
  redesigns — each is its own later plan (`DESIGN.md` Rollout steps 2-5). Round 1
  touches Discovery **only** to consume the extracted `SegmentedControl`.
- Adding any **new** font beyond the Inter + JetBrains Mono pair (e.g. Source
  Sans, a serif). The Space Grotesk → Inter swap itself is **in scope** (Task 1).
- Restyling the `ui/Input` (forms) and the rail/top-bar nav primitives — these
  are restyled alongside the first screen that renders them (auth for `Input`,
  the app shell for nav), so the change is checked against a real render, not in
  isolation. Round 1's Landing uses neither.
- Any backend, Go gateway, Android, or `.claude/`-layer change.
- New copy, new landing sections, new product features or routes.
- A committed visual-regression baseline / screenshot-diff CI gate.
- RSC / Server Component conversion (that is the world-class spec's Phase 2).
- Changing `--motion-*`, the radius scale, or the elevation scale (beyond adding
  `--shadow-signal`).

## Validator status

- `python tools/parallel_groups.py <this file>` → **6 tasks, 4 rounds**: round 1
  `[T1, T3, T4]` (x3), round 2 `[T2]`, round 3 `[T5]`, round 4 `[T6]`. Deps parse.
- `python tools/scope.py --plan <this file>` → `risk: high` (unclassifiable path
  set — see Risk field).
- `python tools/analyze.py --slug design/web-visual-contract` → cannot run: the
  slug matcher does not resolve a branch/slug containing `/`. Structural checks
  it would run (missing sections, unresolved markers, verification commands,
  Modify-target existence) were done by hand in the Stage C5 self-review; no
  `[NEEDS CLARIFICATION]` marker remains (all three resolved above).
