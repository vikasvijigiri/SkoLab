# DESIGN.md - SkoLab (web)

> Current direction (2026-09-09): Professional Network. The supplied
> LinkedIn-style reference supersedes the dark-first default: SkoLab now uses a
> bright neutral canvas, white bordered panels, professional blue actions,
> compact utility navigation, and Inter-led typography. This adapts the visual
> language only; SkoLab keeps its own branding and content. Dark mode remains an
> explicit user choice. See decision 0013.

**Status:** adopted — **direction B (Instrument)**; positioning pivot confirmed
by the product owner 2026-09-08. Supersedes direction C ("Confident Modernist",
adopted 2026-09-07). **Rollout: in progress (2026-09-08)** — tokens + primitives
migrated to a dark-first base; feature surfaces follow the Rollout order below.
Plan: `docs/plans/2026-09-08-instrument-frontend.md`. Decision:
`decisions/2026-09-08-instrument-visual-identity.md`.
**Owns:** the web surface only (`apps/web`). The Android client is a separate,
deliberately flat surface and is out of scope here.
**Supersedes:** direction C and the round-1 rollout
(`docs/plans/2026-09-07-web-visual-identity-round1.md`). The direction-C token
names and architecture are kept — the values, the default theme, and the density
change.
**Builds on:** `.claude/skills/architecture/references/design-contract.md`
(procedure) and `.claude/skills/frontend-ui/` (build + audit against this file).

---

## What this is

SkoLab is the impact layer for research: bibliometric signatures (eight axes of
real standing, not just an h-index), click-only discovery of the papers and
people closest to a problem, and a live collaboration workspace for a lab. It
serves PhD students, principal investigators, and lab groups who read dense data
on screen for hours.

**The single feeling the web surface must produce:** *authoritative instrument*.
You are looking at the whole field at once, through a tool that is precise,
fast, and confident about what it is showing you — the way a trading terminal,
an observability console, or a professional audio workstation feels. Dark by
default because the data is the light: numbers, charts, and structure sit on a
near-black ground and the eye goes straight to them, for hours, without strain.

**Platform:** web (Next.js 16 App Router, Tailwind v4, framer-motion). See
`.claude/skills/architecture/references/platform-guidance.md` (Web section) for
the conventions this contract draws on.

**Grounding:** no Figma file is linked to this repository and the connected
Figma account is view-only, so there is nothing to ground tokens against via
`mcp__figma__*`. Tokens below are derived from the direction-C system in
`apps/web/src/app/globals.css` (kept: the token architecture, the 3-block theme
sync, the WCAG luminance discipline) re-pitched for a dark base, plus the
external references in `.claude/rules/ui-ux-resources.md`. Any linked Figma file
supersedes this.

---

## Direction

### Why the pivot

Direction C ("Confident Modernist") was well-built and shipped cleanly. It was
not slop. The product owner has made a **positioning call**, not a taste call:
SkoLab is repositioning as a **pro data instrument for power users** — the exact
trigger the direction-C contract itself named as the reason to switch to B
("a product decision to reposition SkoLab as a pro data terminal for power
users → B"). "Futuristic, FAANG-level, ultra-fast" is the brief; a dark-native
instrument is the answer.

### Approaches considered (unchanged from round 1; the ✓ moved)

| Direction | Idea | Verdict |
|---|---|---|
| **A — Editorial / Journal** | Serif display face, generous measure, hairline rules, one ink + one deep accent. Nature / Economist / Stripe Press. | Rejected round 1. A high-contrast serif fights dense tabular data, radar charts, leaderboards. Reads "publication", not "instrument". |
| **B — Instrument / Terminal** ← **chosen 2026-09-08** | Dark-first, high-density, monospace promoted to structure, one vivid accent that pops on dark (`--primary` indigo-violet) plus a live-green status and a warm signal for the single primary action. Bloomberg / Vercel dashboard / Linear dark / observability tooling. | **Adopted.** The positioning changed: SkoLab is now a power-user instrument. Dark is the right ground for hours of data reading; the migration cost (10+ screens, a re-run WCAG pass) is accepted and recorded in the decision. |
| **C — Confident Modernist** | Light ground kept, identity through scale + saturation + the existing colour system. LinkedIn / Stripe / Ramp. | **Superseded 2026-09-08.** Correct for a general-audience product; the product is no longer positioned that way. |

### Why B wins now

- **The positioning is the deciding fact.** A power-user research instrument is
  read for hours against reference data; a calm dark ground with bright data is
  the ergonomic default for that job (every terminal, DAW, and observability
  console converges on it for a reason).
- **The brief is explicit:** futuristic, FAANG-level, ultra-fast. B is the
  direction that reads that way without gimmicks — density, monospace structure,
  a decisive accent, mechanical motion. A is retro; C is safe.
- **The architecture survives the pivot.** `globals.css` already carries a full,
  contrast-checked dark theme and a 3-block sync. B makes that the base instead
  of the alternate — a values-and-default change, not a rebuild.
- **What B gives up:** the light ground as the primary experience. It is
  retained as a first-class, fully-supported theme (`[data-theme="light"]` +
  `prefers-color-scheme: light`) — dark is the default, not the only option.
- **What would change the choice back:** a product decision to re-target a
  general (non-power-user) audience. That is a positioning call for the owner,
  recorded as a decision — not a screen-by-screen drift.

---

## Voice

Unchanged from direction C — the copy was already right.

- **Tone:** precise, confident, no hype. Short declarative sentences. A dry wit
  is allowed in the FAQ and empty states, nowhere else.
- **Person:** second person ("you"). "We" only for SkoLab-the-company, in the
  FAQ and legal copy.
- **Numbers are concrete and sourced:** "~2.4M profiles", "240M+ papers",
  "every axis traces back to a source." Never a vague "millions".
- **Do:** `Couldn't load right now.` + a retry control. `Nothing here yet for
  this topic` + the next move. `Pick a field to see its top researchers.`
- **Don't:** `Oops! Something went wrong 😅`. No emoji in product copy. No
  exclamation stacks. No "power up your research".
- **Reference voice:** Linear's changelog; Stripe's docs; a good CLI's `--help`.
- Every state ships real copy: loading, empty, error, permission-denied,
  success, destructive. Loading strings end with `…`.

---

## Colour tokens

Dark is the base (`:root`). Light is an override, kept complete. Raw values live
on bare custom properties, swapped per scheme; `@theme inline` maps them into
Tailwind's `--color-*` namespace so semantic utilities never need a `dark:`
prefix. **Three blocks stay in sync:** `:root` (dark), `@media
(prefers-color-scheme: light)`, and `:root[data-theme="light"]` /
`:root[data-theme="dark"]`.

> **Contrast is the gate, not the guess.** `apps/web/scripts/check-contrast.mjs`
> (`npm run check:contrast`) verifies every pair below on both themes as the
> first task of the rollout. Where a hue misses AA it is nudged within its own
> family and the final hex recorded here. Targets: body text ≥ 4.5:1, large text
> and UI components ≥ 3:1, against the **actual** surface.

### Core semantic tokens

| Token | Dark (base) | Light (override) | Use |
|---|---|---|---|
| `--page-bg` | `#0b0d10` | `#f7f7f5` | Page ground. Near-black with a faint cool cast — "deep slate", not "void". |
| `--surface` | `#14171b` | `#ffffff` | Cards, panels — a real lift off the ground. |
| `--surface-subtle` | `#1c2026` | `#eeeeec` | Insets, skeletons, toggle track, input fill (dark). |
| `--surface-raised` | `#20242b` | `#ffffff` | Popovers, dropdowns, modals — one step above `--surface`. |
| `--border-color` | `#282d35` | `#e5e5e2` | Hairlines, dividers, the structural grid, resting card border. |
| `--border-strong` | `#3a414c` | `#d1d1cc` | Emphasised dividers, hovered card border. |
| `--text-primary` | `#e6e9ee` | `#1b1d23` | Headings, primary body. Warm-neutral off-white — never `#fff`. |
| `--text-secondary` | `#aab1bd` | `#565863` | Supporting body. |
| `--text-muted` | `#8b93a0` | `#5f616d` | Captions, mono eyebrows, table sub-labels. |
| `--primary` | `#8f88ff` | `#4b3fd6` | Brand: links, active nav, "influence" metric, focus of attention. Indigo-violet that pops on dark. |
| `--primary-hover` | `#a49dff` | `#3f34c2` | Link/nav hover. |
| `--primary-muted` | `color-mix(in srgb, var(--primary) 16%, transparent)` | same formula | Selected row, active tab background, match highlight. |
| `--accent-signal` | `#ff6b4a` | `#c9401f` | **The one primary action per viewport** — primary CTA fill, and nothing else. Warm against the cool primary. |
| `--accent-signal-hover` | `#ff8163` | `#af3819` | Signal hover / pressed. |
| `--accent-live` | `#37dd87` | `#0f7a48` | "Live", new, streaming, presence, positive delta. Status only — never a CTA. |
| `--text-on-primary` | `#0b0d10` | `#ffffff` | Label text on `--primary` and `--accent-signal` **fills**. On dark the fills are bright, so their label is the dark ground — bright key, dark legend, the instrument look. On light it is white. |
| `--success` | `#37dd87` | `#0f7a48` | Confirmation, positive delta (alias of `--accent-live`). |
| `--warning` | `#f5b544` | `#b45309` | Caution. |
| `--danger` | `#ff6470` | `#be123c` | Destructive, errors. `--notification` `#ff6470` / `#dc2626` for the unread dot. |
| `--surface-input` | `#1c2026` | `#ffffff` | Form field fill. |
| `--border-input` | `#39414d` | `#d6d6d2` | Form field border. |
| `--ring` | `color-mix(in srgb, var(--accent-signal) 55%, transparent)` | `color-mix(in srgb, var(--accent-signal) 30%, transparent)` | Focus ring — signal-derived so focus is never mistaken for a selected/active (primary) state. |

### Data-viz palette (categorical — brand-level, dark-tuned)

One set for every chart, metric pill, radar axis, legend, and sparkline. Do not
invent a chart colour. Order is the assignment order.

| Metric role | Token | Dark | Light |
|---|---|---|---|
| Influence | `--metric-influence` | `#8f88ff` (`--primary`) | `#4b3fd6` |
| Disruption | `--metric-disruption` | `#ff8a5c` | `#c2410c` |
| Novelty | `--metric-novelty` | `#3fd0e0` | `#0e7490` |
| Future impact | `--metric-future-impact` | `#c4a7ff` | `#6d4bd0` |
| Creativity | `--metric-creativity` | `#ff7ab8` | `#be185d` |
| Complexity | `--metric-complexity` | `#6ea8ff` | `#3b5bd9` |
| Open science | `--metric-open-science` | `#4fd08a` | `#047857` |
| Collaboration | `--metric-collab` | `#3ec9b8` | `#0f766e` |
| Consistency | `--metric-consistency` | `#f5c451` | `#b45309` |
| Policy | `--metric-policy` | `#98a2b3` | `#64748b` |

### Elevation

On a near-black ground a drop shadow barely reads — **elevation is border +
light, not shadow**.

| Token | Dark | Light | Use |
|---|---|---|---|
| `--shadow-xs` | `inset 0 1px 0 rgba(255,255,255,0.03)` | `0 1px 2px rgba(18,20,31,0.05)` | Resting data rows — a top hairline of light. |
| `--shadow-card` | `0 0 0 1px var(--border-color), inset 0 1px 0 rgba(255,255,255,0.03)` | `0 1px 2px rgba(18,20,31,0.05), 0 2px 6px rgba(18,20,31,0.05)` | Card at rest. |
| `--shadow-card-hover` | `0 0 0 1px var(--border-strong), 0 4px 16px rgba(0,0,0,0.5)` | `0 4px 12px rgba(18,20,31,0.08), 0 10px 22px rgba(18,20,31,0.08)` | Interactive card, hover. |
| `--shadow-elevated` | `0 0 0 1px var(--border-strong), 0 16px 40px rgba(0,0,0,0.6)` | `0 8px 20px rgba(18,20,31,0.10), 0 20px 44px rgba(18,20,31,0.12)` | Modals, popovers. |
| `--shadow-signal` | `0 0 0 1px var(--accent-signal), 0 0 24px color-mix(in srgb, var(--accent-signal) 30%, transparent)` | `0 6px 20px color-mix(in srgb, var(--accent-signal) 28%, transparent)` | Hero CTA only. On dark it is a glow, not a drop. |

### Rules

- **No token, no colour.** A new colour needs a dated `decisions/` entry.
- `--accent-signal` and `--primary` never both fill a call-to-action in one
  viewport. Signal = the primary action; primary = navigation and brand.
- `--accent-live` is status only.
- Every text token clears WCAG AA against its **actual** surface; UI elements
  clear 3:1. `check-contrast.mjs` is the gate on both themes.
- `color-scheme: dark` is set on `<html>` (fixes native scrollbars, form
  controls, `<select>`); `<meta name="theme-color">` tracks `--page-bg`.

---

## Type

**Two families.** Inter for everything a person reads as prose; **JetBrains Mono,
promoted to a structural role** — this is the identity move. Both already loaded
via `next/font`.

- **Prose + headings:** Inter, 400 / 500 / 600 / 700. Headings 600–700 with
  `-0.02em` tracking at the top of the scale; body 400. Keep
  `font-feature-settings: "cv05","cv08","cv11","calt"`.
- **Mono — structural, not decorative:** JetBrains Mono 500, `tabular-nums`.
  Every one of: numeric data value, metric label, axis label, section eyebrow,
  timestamp, ID / DOI / ORCID, table column header, nav and rail label, tab
  label, breadcrumb, filter chip, key-command hint, code. If it is a label on
  data or a piece of interface structure, it is mono.
- **Density:** the base scale drops one notch from direction C. Default UI body
  is **14px**; dense rows **12.5px**; the floor for sentence copy stays 13px
  (long-form only). Eyebrows 11px uppercase, tracked `0.09em`.

### Scale (the agent does not pick sizes)

| Role | Size / line-height | Family / weight | Where |
|---|---|---|---|
| Display XL | 58 / 1.05, `-0.02em` | Inter 700 | Landing hero only |
| Display L | 40 / 1.1, `-0.02em` | Inter 700 | Marketing section heads |
| Display M | 30 / 1.15, `-0.015em` | Inter 700 | App page `<h1>` (Discovery, Home, Profile…) |
| H2 | 20 / 1.25 | Inter 600 | Section headings |
| H3 | 16 / 1.3 | Inter 600 | Card titles, sub-sections |
| Body L | 15.5 / 1.6 | Inter 400 | Long-form (paper abstract, FAQ) |
| Body | 14 / 1.55 | Inter 400 | Default UI body |
| Body S | 12.5 / 1.5 | Inter 400 | Dense rows, secondary detail |
| Caption | 11.5 / 1.4 | Inter 500 | Timestamps in prose context, helper text |
| Eyebrow | 11 / 1.4, `0.09em`, uppercase | JetBrains Mono 500, `--text-muted` | Section labels, rail/nav labels |
| Data | 12.5–15 / 1, `tabular-nums` | JetBrains Mono 500 | Every metric value, h-index, citation count |
| Section numeral | 36 / 1 | JetBrains Mono 500, `--text-muted` | Long-page section markers (`01`, `02`…) |

---

## Layout and spacing

- **Spacing scale:** `4 / 8 / 12 / 16 / 24 / 32 / 48 / 64 / 96` — and nothing
  between. The half-step habit (`py-1.5`, `gap-2.5`, `mt-0.5`) is an
  anti-pattern; commit to the grid.
- **Density is up.** Default card padding `16px` (`p-4`); data cards `12–16px`.
  Row heights: comfortable `44px`, dense `36px`, table `32px`. A leaderboard or
  taxonomy list uses dense; a table uses table height.
- **Max content width:** prose 680px · app content 1200px · full-bleed data
  (leaderboards, workspace tables) 1440px.
- **Grid:** 12 columns / 24px gutter desktop; 4 / 16px mobile. Tailwind
  breakpoints (`sm 640 · md 768 · lg 1024 · xl 1280`).
- **Radius — crisper, no in-between values:**
  - `999px` (pill): segmented toggles, facet chips, avatars, filter tags.
  - `--radius-sm 8px`: cards, panels, modals, dropdowns.
  - `--radius-xs 4px`: dense data rows, table cells, inputs, code, badges.
- **Structural device — mandatory, not optional:** a 1px hairline-rule system.
  Every major section is bounded by `border-y border-border`; long pages carry
  mono section numerals (`01`, `02`…) at the left margin as markers. The
  ledger/console look comes from visible structure, not from cards floating in
  space.
- **Elevation:** border + light per the token table. A card is a bordered
  region on dark, not a shadowed slab.

---

## Components

Deltas from the direction-C implementation. Anything not listed keeps its
current behaviour; everything listed re-pitches for the dark base.

### Buttons (`apps/web/src/components/ui/Button.tsx`)

- **Variants:** `signal` (the one primary action — `--accent-signal` fill,
  `--text-on-primary` = dark label, h-12, weight 600) · `primary` (`--primary`
  fill, dark label, for brand/nav actions) · `outlined` (1px `--primary` border,
  `--primary` text, transparent) · `ghost` (transparent, `--text-secondary`,
  hover `--surface-subtle`) · `text` (`--primary`, inline).
- **Size `lg`:** h-14 / 15px — marketing / hero only.
- **Hover:** background → the `-hover` token + a 1px `translateY(-1px)` lift.
  **No `scale()` on hover or press** — the generic-AI tell, banned.
- **States:** default · hover · focus-visible (2px `--ring`, offset 2) · pressed
  (`-hover` token, no lift) · disabled (`--surface-subtle` fill, `--text-muted`,
  no lift) · loading (spinner, label hidden, width held).
- Only one `signal` button per viewport.

### Cards (`apps/web/src/components/ui/Card.tsx`)

- **Fill `--surface`, always a 1px `--border-color`** (on dark the border *is*
  the card edge). Padding `16px` standard, `12–16px` for data cards.
- **Accent bar:** data cards (author result, paper result, metric) get a **3px
  left bar** in the relevant palette colour — a ledger row. Marketing / feature
  cards get a 2px **top** bar.
- **Hover (interactive):** border → `--border-strong`, `translateY(-1px)`,
  `--shadow-card-hover`. No scale.
- **States:** static · interactive (hover + pressed `translateY(0)`) · loading
  (skeleton at the same radius on `--surface-subtle`) · empty.

### Navigation (`apps/web/src/components/layout/`)

- Rail / top bar keep their structure. Active item: `--primary` text + a 2px
  `--primary` left indicator (rail) or underline (top bar) — never a filled
  pill.
- All rail, nav, and tab **labels are mono Eyebrow**.
- Top bar sits on `--page-bg` with a `border-b border-border`; no shadow.

### Segmented control / chips (`apps/web/src/components/ui/SegmentedControl.tsx`)

- The sliding-pill toggle is canonical. Track `--surface-subtle`, thumb
  `--surface` with a 1px border; active label `--text-primary`, inactive
  `--text-muted`. Labels mono.
- Facet chips (field / subfield / topic) use mono labels; plain-text chips stay
  Inter.

### Forms (`apps/web/src/components/ui/Input.tsx`)

- Field on `--surface-input`, 1px `--border-input`, `--radius-xs`, min height
  40px.
- **Label:** mono Eyebrow, above the field.
- **Focus:** 2px `--ring` (signal-derived) — never the same colour as the brand.
- **Validation:** on blur and on submit. Error below the field in `--danger`
  with an icon (never colour alone); the field stays focus-reachable; focus
  moves to the first error on submit.

### Data display (first-class category)

`MetricPill`, `StatTile`, `RadarChart`, `CitationBarChart`, leaderboard rows.

- Numerals in mono Data, `tabular-nums`.
- Colour only from the data-viz palette, assigned in list order.
- Every instance ships a loading skeleton and a no-data state ("No citation data
  for this author yet.").
- A value is never conveyed by colour alone — always with a label, a shape, or
  the number.

### Dialogs / toasts / errors

- Modal: `--surface-raised`, 1px `--border-strong`, `--radius-sm`,
  `--shadow-elevated`; backdrop `--page-bg` at 72%; Escape closes; focus
  trapped; focus returns to the trigger.
- Error surface: the existing `ErrorBanner` with its retry affordance is the
  standard. No silent `.catch(() => {})`.
- Empty state: an icon, one line of what, one line of why / the next move, and
  the action control where one exists.

---

## Motion

Faster and more mechanical than direction C — an instrument responds
immediately.

- **Tokens:** `--motion-fast 120ms · --motion-normal 200ms · --motion-slow
  320ms`. Ease: `--ease-standard cubic-bezier(0.2, 0, 0, 1)` (snappy),
  `--ease-exit cubic-bezier(0.4, 0, 1, 1)`, `--ease-enter cubic-bezier(0, 0,
  0.2, 1)`.
- **Default transition:** `--motion-fast` `--ease-standard` for hover/press
  colour and transform; `--motion-normal` for entrances and layout.
- **Signature entrance:** content reveals with an **8px** rise + fade over
  `--motion-normal`, staggered **32ms** per item. `Reveal` is standardised to
  this.
- **Route changes:** use the native **View Transitions API** (no dependency) for
  a 120ms cross-fade between app routes where supported; plain swap otherwise.
- **Numbers** may count up once on first mount (`AnimatedCounter`), capped at
  **500ms**.
- **What must not animate:** data values on re-render, anything on scroll (no
  parallax), decorative loops, the segmented-control pill on initial paint.
- **Reduced motion:** the `@media (prefers-reduced-motion: reduce)` block
  (animations/transitions → `0.01ms`) stays; entrances become instant, count-ups
  render the final value, View Transitions are skipped.

---

## Accessibility floor

Non-negotiable. A miss is release-blocking unless a documented exception names
the rule, the reason, the owner, and the expiry.

- [ ] Contrast meets WCAG AA against the **actual** background on **both**
      themes — verified by `npm run check:contrast` as rollout task 1. Body text
      ≥ 4.5:1; large text and UI ≥ 3:1. Any hue that misses is nudged within its
      family and the final hex recorded in the token table.
- [ ] Every interactive element is keyboard-reachable with a visible 2px focus
      ring; focus is never removed; focus order matches visual order.
- [ ] Icons that carry meaning have an accessible name; informative images have
      meaningful `alt`; decorative ones are `aria-hidden`.
- [ ] Nothing conveys information by colour alone — charts, deltas, statuses all
      carry a label, shape, or value.
- [ ] `prefers-reduced-motion` respected (block present).
- [ ] Headings nest correctly (one `<h1>` per page at Display M), landmarks
      present, form labels associated, errors linked via `aria-describedby`.
- [ ] Touch targets ≥ 44px; body text ≥ 13px for sentences.
- [ ] `color-scheme: dark` on `<html>` so native controls, scrollbars, and
      `<select>` render correctly on the dark base.

---

## Anti-patterns — never ship these

Output is checked against this list and against
`.claude/skills/frontend-ui/references/web-interface-guidelines.md`.

- [ ] Generic AI gradient-on-everything; glassmorphism; a purple-blue hero blob.
- [ ] `scale()` hover/press on buttons or cards (colour + 1px lift instead).
- [ ] Spacing off the 4px grid.
- [ ] Colours outside the token tables (including chart colours).
- [ ] A third font family, or a display face reintroduced without a decision.
- [ ] `--primary` and `--accent-signal` both in the primary-action slot in one
      viewport; `--accent-live` used as a CTA.
- [ ] Drop-shadow slabs on the dark base instead of border + light elevation.
- [ ] Decorative motion on data; count-ups that re-fire on every render.
- [ ] Body text below 13px for sentences; interior page titles below Display M.
- [ ] Centred walls of text — the landing "one idea" block caps at two lines.
- [ ] Emoji standing in for iconography or for real copy.
- [ ] A light-only assumption (hardcoded `#fff`, `text-black`, a shadow tuned
      for a white ground) — every surface works on both themes.

---

## Rollout

The contract applies to the whole web surface but lands in dependency order so
the identity is proven before it spreads. Tracked in
`docs/plans/2026-09-08-instrument-frontend.md` (Phase C).

1. **Tokens — `globals.css`.** Dark values onto bare `:root`; light becomes
   `@media (prefers-color-scheme: light)` + `[data-theme="light"]`;
   `[data-theme="dark"]` echoes the base. `color-scheme: dark` on `<html>`.
   Run `npm run check:contrast` — it is the gate, not a formality.
2. **Primitives — `components/ui/*`.** Button, Card, Input, Badge, Modal,
   SegmentedControl, ThemeToggle (default → dark), ErrorBanner, and the motion
   helpers, to the values above. Component tests updated where they assert a
   class or colour.
3. **Shell — `components/layout/*`, `app/(app)/layout.tsx`, `MotionProvider`,
   `app/layout.tsx`.** Top bar, rail, profile menu, notifications.
4. **Feature surfaces**, in this order: Landing → Discovery → Auth (login /
   signup / onboarding) → core app (Home, `paper/[id]`, `author/[id]`) →
   Workspace / Nexus / Horizon.
5. **Performance pass** — apply
   `.claude/skills/engineering-standards/references/frontend-performance-rules.md`
   (waterfalls, bundle, re-render, `content-visibility`, list virtualization).
6. **Full gate** — `npm run test` / `tsc` / `lint` / `build` /
   `check:contrast` / `test:e2e` (axe WCAG AA on every public route, light and
   dark) all green.

---

## How to use

Check every generated or modified surface against the token tables, the type
scale, the spacing scale, the accessibility floor, and the anti-pattern list.
Build and audit UI through `.claude/skills/frontend-ui/`. If something this
contract does not cover comes up, add a dated decision in `decisions/` and
update this file — do not let the agent answer it silently. `refactoring` checks
the implemented surface against this document after each rollout step.
