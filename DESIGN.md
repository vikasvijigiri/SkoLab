# DESIGN.md — SkoLab (web)

**Status:** proposed — direction chosen, 3 `[NEEDS CLARIFICATION]` markers for Gate 1
**Owns:** the web surface only (`apps/web`). The Android client is a separate,
deliberately flat surface and is out of scope here.
**Supersedes:** the unwritten contract that lived only as inline rationale in
`apps/web/src/app/globals.css`. Those tokens are the starting point, not the
ceiling — this document is now the authority.

---

## What this is

SkoLab is the impact layer for research: bibliometric signatures (eight axes of
real standing, not just an h-index), click-only discovery of the papers and
people closest to a problem, and a live collaboration workspace for a lab. It
serves PhD students, principal investigators, and lab groups who read dense data
on screen for hours.

**The single feeling the web surface must produce:** *authoritative instrument*.
You are looking at the whole field at once, through a tool that is precise,
fast, and confident about what it is showing you. Precision stays; timidity
goes.

**Platform:** web (Next.js 16 App Router, Tailwind v4, framer-motion). See
`.claude/skills/architecture/references/platform-guidance.md` for the web
conventions this contract draws on.

**Grounding:** no Figma file is linked to this repository and the connected
Figma account is view-only, so there is nothing to ground tokens against via
`mcp__figma__*`. Tokens below are derived from the existing, well-calibrated
`globals.css` system plus the external references in
`.claude/rules/ui-ux-resources.md`. Any linked Figma file supersedes this.

---

## Direction

### The problem with the current surface

The current "quiet precision" system is well-built — real elevation, a synced
dark mode, WCAG-luminance-checked contrast, a coherent three-font stack. It is
not slop. Its weakness is **recessiveness**, not taste:

- One indigo, used sparingly; the ten accent colors are hidden inside charts.
- Body text sits at 13px almost everywhere; interior page titles at 22px.
- Section labels are 11–12px grey. Nothing on an interior screen has authority.
- Spacing drifts off-grid constantly (`py-1.5`, `gap-2.5`, `mt-0.5`).
- `scale(1.025)` hover on buttons and cards — the generic-AI tell.

The result reads as careful but unmemorable. A researcher benchmarking a hire
should feel they are using an instrument, not a note-taking app.

### Approaches considered

| Direction | Idea | Why it loses |
|---|---|---|
| **A — Editorial / Journal** | Serif display face (Fraunces / Newsreader), generous measure, hairline rules as structure, one ink + one deep accent (claret). Authority through typography, not color. Nature / Economist / Stripe Press. | The scholarly-print references are seductive but a high-contrast serif fights SkoLab's actual job — dense tabular data, radar charts, leaderboards, taxonomy drilldown. Editorial spacing and data density pull in opposite directions. Reads "publication," not "instrument." |
| **B — Instrument / Terminal** | Dark-first, high-density, mono promoted to structure, one vivid accent that pops on dark (`#7c6cff`) plus a live-green. Bloomberg / Vercel dashboard / observability tooling. | The strongest "bolder" candidate, but dark-first trades away the current system's real asset: a calm light ground for hours of reading. It is also the largest migration risk across 10+ screens and can read cold or gamer-y for an academic audience. Kept as the fallback if SkoLab repositions as a power-user terminal. |
| **C — Confident Modernist** ← chosen | Keep the light, readable ground and the token architecture. Inject identity through **scale, saturation, and decisive use of the color system that already exists**: a real type scale with authority at the top, a more electric brand hue plus one warm signal color for action, the metric palette promoted to brand-level data-viz, a visible hairline-and-numeral structural grid, and disciplined geometry. | — |

### Why C wins

- **It keeps the asset.** SkoLab's users read for hours; the calm light ground
  is a feature. B discards it; A buries it under contrast.
- **The fix matches the diagnosis.** The current surface is timid, not
  mis-coloured. Bolder is reached through committed scale and confident use of a
  palette that is *already defined and already accessibility-checked* — not
  through a new palette or a dark inversion.
- **Lowest migration risk.** C builds on `globals.css` rather than replacing it,
  so the dark-mode sync and the WCAG work survive. Rollout across 10+ screens is
  a type-scale and spacing-rhythm pass, not a rebuild.
- **What would change the choice:** a product decision to reposition SkoLab as a
  pro data terminal for power users (→ B), or to lean on scholarly-prestige
  brand equity (→ A). Both are positioning calls, not taste calls — see marker 1.
- **What C gives up:** the safety of "nothing really changes." Every screen's
  type scale, spacing rhythm, and hover behaviour is touched, and the data-viz
  palette needs a real consistency pass.

`[NEEDS CLARIFICATION: Brand positioning drives the final call between C (calm
scholarly instrument, this contract), B (dark-first pro terminal), and A
(editorial/scholarly-prestige). C is scoped here and recommended; confirm the
positioning or name the pivot.]`

`[NEEDS CLARIFICATION: Are there existing SkoLab brand assets — a founder-chosen
brand colour, a logo/wordmark, a marketing-site palette? None were found in the
repo. If the indigo in globals.css is provisional, the exact --primary and
--accent-signal hues below are proposals for the user to ratify, not decisions.]`

---

## Voice

How the product talks — UI copy, errors, empty states, marketing. The existing
copy is already strong ("Click a field — not a search box.") — this codifies it.

- **Tone:** precise, confident, no hype. Short declarative sentences. A dry wit
  is allowed in the FAQ and empty states, nowhere else.
- **Person:** second person ("you"). "We" only for SkoLab-the-company, and only
  in the FAQ and legal copy.
- **Numbers are always concrete and sourced:** "~2.4M profiles", "240M+ papers",
  "every axis traces back to a source." Never a vague "millions."
- **Do:** `Couldn't load right now.` + a retry control. `Nothing here yet for
  this topic` + the next move. `Pick a field to see its top researchers.`
- **Don't:** `Oops! Something went wrong 😅`. No emoji in product copy. No
  exclamation stacks. No "power up your research."
- **Reference voice:** Linear's changelog; Stripe's docs. Dry, exact, respects
  the reader's time.

Copy is part of the design. Every state below ships with real copy, not a
placeholder: loading, empty, error, permission-denied, success, destructive.

---

## Color tokens

The neutral architecture from `globals.css` is kept as-is (it is well-calibrated
and contrast-checked). The changes are: a more electric `--primary`, a new warm
`--accent-signal` for primary action and "live" state, and the promotion of the
existing metric palette to a documented brand-level data-viz set.

Raw values live on bare custom properties, swapped per colour scheme; `@theme
inline` maps them into Tailwind's `--color-*` namespace so semantic utilities
never need a `dark:` prefix. Keep the three blocks (`:root`,
`@media (prefers-color-scheme: dark)`, `:root[data-theme=...]`) in sync.

### Core semantic tokens

| Token | Light | Dark | Use |
|---|---|---|---|
| `--page-bg` | `#f7f7f5` | `#1a1b18` | Page ground (unchanged) |
| `--surface` | `#ffffff` | `#232420` | Cards, panels (unchanged) |
| `--surface-subtle` | `#eeeeec` | `#2b2c26` | Insets, skeletons, toggle track (unchanged) |
| `--text-primary` | `#1b1d23` | `#e9e5da` | Headings, primary body (unchanged) |
| `--text-secondary` | `#565863` | `#b1ac9f` | Supporting body (unchanged) |
| `--text-muted` | `#5f616d` | `#9c978a` | Captions, mono eyebrows (unchanged) |
| `--border-color` | `#e5e5e2` | `#38392f` | Hairlines, dividers, card borders (unchanged) |
| `--primary` | `#4f3fe0` *(was `#4a52cf`)* | `#a79bff` *(was `#9aa0ea`)* | Brand: links, active nav, secondary CTA outline, "influence" metric, focus of attention |
| `--primary-dark` | `#4234c4` | `#8f80f5` | Primary hover / pressed |
| `--primary-deeper` | `#362aa0` | `#7a6bec` | Primary on large filled blocks needing AA body contrast |
| `--accent-signal` | `#e0512f` | `#ff7d5c` | **Primary CTA fill, "live"/new badges, the one thing on a screen that must be clicked.** Never more than one signal region per viewport. |
| `--accent-signal-dark` | `#c8431f` | `#ff6a44` | Signal hover / pressed |
| `--text-on-primary` | `#ffffff` | `#1a1b18` | Text on `--primary` / `--accent-signal` fills |
| `--success` | `#047857` | `#34d399` | Confirmation, positive delta (alias of `--accent-emerald`) |
| `--warning` | `#b45309` | `#f0a83a` | Caution (alias of `--accent-amber`) |
| `--danger` | `#be123c` | `#f9899b` | Destructive, errors (alias of `--accent-rose`); `--notification #dc2626` stays for the unread dot |
| `--ring` | `color-mix(in srgb, var(--accent-signal) 30%, transparent)` | same formula | Focus ring — now derived from `--accent-signal` so focus is unmistakable and never collides with the indigo brand |

### Data-viz palette (promoted from the metric tokens — now brand-level)

One categorical set, used for every chart, metric pill, radar axis, and legend.
Do not invent a chart colour outside this list. Order is the assignment order.

| Metric role | Token | Light | Dark |
|---|---|---|---|
| Influence | `--metric-influence` | `#4f3fe0` (`--primary`) | `#a79bff` |
| Disruption | `--metric-disruption` | `#c2410c` (`--accent-orange`) | `#fb923c` |
| Novelty | `--metric-novelty` | `#0e7490` (`--accent-cyan`) | `#22d3ee` |
| Future impact | `--metric-future-impact` | `#6d4bd0` (`--accent-violet`) | `#c4b5fd` |
| Creativity | `--metric-creativity` | `#be185d` (`--accent-pink`) | `#f472b6` |
| Complexity | `--metric-complexity` | `#3b5bd9` (`--accent-indigo`) | `#7fa8f5` |
| Open science | `--metric-open-science` | `#047857` (`--accent-emerald`) | `#34d399` |
| Collaboration | `--metric-collab` | `#0f766e` (`--accent-teal`) | `#2dd4bf` |
| Consistency | `--metric-consistency` | `#b45309` (`--accent-amber`) | `#f0a83a` |
| Policy | `--metric-policy` | `#64748b` | `#94a3b8` |

### Rules

- **No token, no colour.** A new colour needs a dated decision in `decisions/`.
- `--accent-signal` and `--primary` never both fill a call-to-action in the same
  viewport. Signal = the primary action; primary = navigation and brand.
- Every text token clears WCAG AA (≥4.5:1) against its **actual** surface; UI
  elements clear 3:1. The proposed `--primary` and `--accent-signal` hues must be
  verified against this on both themes before implementation lands — see
  Accessibility floor.

---

## Type

Three families, all already loaded via `next/font` — no fourth. The change is
**scale and role**, not the stack.

- **Display / headings:** Space Grotesk, weight 700, tracking `-0.02em`. Used at
  the top of every page and for marketing. Interior pages currently under-set
  their `<h1>` — that ends.
- **Body:** Inter, 400/500/600. Default body rises from 13px to **14.5px**.
  Keep the `font-feature-settings: "cv05","cv08","cv11","calt"` tuning.
- **Mono:** JetBrains Mono, weight 500 — **promoted to a structural role.** All
  numeric data, all axis and metric values, all uppercase section eyebrows, all
  timestamps and IDs. This is the single biggest "instrument" lever and it costs
  nothing new.

### Scale (the agent does not pick sizes)

| Role | Size / line-height | Family | Where |
|---|---|---|---|
| Display XL | 60 / 1.05 | Space Grotesk 700 | Landing hero only |
| Display L | 44 / 1.1 | Space Grotesk 700 | Marketing section heads |
| Display M | 32 / 1.15 | Space Grotesk 700 | App page `<h1>` (Discovery, Home, Profile…) |
| H2 | 22 / 1.25 | Space Grotesk 700 | Section headings |
| H3 | 17 / 1.3 | Space Grotesk 600 | Card titles, sub-sections |
| Body L | 16 / 1.6 | Inter 400 | Long-form (paper abstract, FAQ) |
| Body | 14.5 / 1.6 | Inter 400 | Default UI body |
| Body S | 13 / 1.5 | Inter 400 | Dense rows, secondary detail |
| Caption | 12 / 1.4 | Inter 500 | Timestamps, helper text |
| Eyebrow | 11.5 / 1.4, `0.08em`, uppercase | JetBrains Mono 500 | Section labels, `--text-muted` |
| Data | 13–15 / 1 tnum | JetBrains Mono 500 | Every metric value, h-index, citation count |
| Section numeral | 40 / 1 | JetBrains Mono 500, `--text-muted` | Long-page section markers (`01`, `02`…) |

Minimum for sentence and paragraph copy: 13px. Labels and eyebrows may go to
11.5px only when uppercase and tracked (`0.08em`); nothing smaller carries
content of any kind.

---

## Layout and spacing

- **Spacing scale:** `4 / 8 / 12 / 16 / 24 / 32 / 48 / 64 / 96` — and nothing
  between. The current half-step habit (`py-1.5`, `gap-2.5`, `mt-0.5`) is an
  anti-pattern here; commit to the grid.
- **Max content width:** prose 680px · app content 1200px · full-bleed data
  (leaderboards, workspace tables) 1400px.
- **Grid:** 12 columns / 24px gutter on desktop; 4 columns / 16px gutter on
  mobile. Breakpoints follow Tailwind defaults (`sm 640 · md 768 · lg 1024 · xl
  1280`).
- **Radius — use contrast deliberately, no in-between values:**
  - `999px` (pill): segmented toggles, facet chips, avatars, filter tags.
  - `--radius-md 14px`: cards, panels, modals, dropdowns.
  - `--radius-xs 6px`: dense data rows, table cells, inputs, code.
- **Elevation:** the `globals.css` shadow scale is kept
  (`--shadow-xs / -card / -card-hover / -elevated`). Add `--shadow-signal`
  (`0 6px 20px color-mix(in srgb, var(--accent-signal) 28%, transparent)`) for
  the hero CTA only.
- **Structural device (new):** a consistent 1px hairline-rule system —
  `border-y border-border` on major sections, mono section numerals as markers
  on long pages. The landing "proof strip" already does this once; make it the
  backbone everywhere.

---

## Components

Deltas from the current implementation. Anything not listed keeps its current
behaviour.

### Buttons ([apps/web/src/components/ui/Button.tsx](apps/web/src/components/ui/Button.tsx))

- **Variants:** `signal` (new default for the primary action — `--accent-signal`
  fill, `--text-on-primary`, h-12, `--radius-md`, weight 600) · `primary`
  (indigo fill, for brand/nav actions) · `outlined` (indigo border) · `ghost` ·
  `text`.
- **Size `lg`:** h-14 / 15px — marketing and hero only.
- **Hover:** background shift to the `-dark` token + a 1px `translateY(-1px)`
  lift. **Remove `whileHover={{ scale: 1.025 }}` and `whileTap` scale** — the
  scale-pop is the generic-AI tell and it goes.
- **States:** default · hover · focus-visible (3px `--ring`, offset 2) · pressed
  (`-dark` token, no lift) · disabled (`--surface-subtle` fill, `--text-muted`,
  no shadow) · loading (spinner, label hidden, width held).
- Only one `signal` button per viewport.

### Cards ([apps/web/src/components/ui/Card.tsx](apps/web/src/components/ui/Card.tsx))

- **Padding:** standardise to 20px (`p-5`). No more `p-4`/`p-6` mix.
- **Accent bar:** on **data** cards (author result, paper result, metric) the
  accent moves to a **3px left bar** — reads as a ledger row / instrument. The
  2px **top** bar is reserved for marketing / feature cards.
- **Hover (interactive cards):** `translateY(-2px)` + `--shadow-card-hover`.
  Keep — no scale.
- **States:** static · interactive (hover + pressed `translateY(0) scale(.99)`
  is retained here as a press affordance) · loading (skeleton at the same
  radius) · empty.

### Navigation ([apps/web/src/components/layout/](apps/web/src/components/layout/))

- Rail / top bar keep their structure. Active item: `--primary` text + a 2px
  `--primary` left indicator (rail) or underline (top bar) — not a filled pill.
- `RailShell` rail label uses the mono Eyebrow token.

### Segmented control / chips

- The Discovery sliding-pill toggle
  ([discovery/page.tsx:91](apps/web/src/app/(app)/discovery/page.tsx#L91)) is the
  canonical segmented control — extract it as `ui/SegmentedControl`.
- Facet chips (field / subfield / topic) use **mono** labels; plain-text chips
  stay Inter.

### Forms ([apps/web/src/components/ui/Input.tsx](apps/web/src/components/ui/Input.tsx))

- Bordered field on `--surface-input` (kept). Min height 44px.
- **Label:** mono Eyebrow token, above the field.
- **Focus:** 3px `--ring` (signal-derived) — focus is never the same colour as
  the brand.
- **Validation:** on blur, and on submit. Error message below the field in
  `--danger`, with an icon (never colour alone), and the field keeps focus
  reachable.

### Data display (new first-class category)

Covers `MetricPill`, `StatTile`, `RadarChart`, `CitationBarChart`, leaderboard
rows.

- Numerals in the mono Data token, tabular figures.
- Colour only from the data-viz palette above, assigned in list order.
- Every instance ships a **loading skeleton** and a **no-data** state
  ("No citation data for this author yet.").
- A value is never conveyed by colour alone — always paired with a label, a
  shape, or the number itself.

### Dialogs / toasts / errors

- Modal: `--radius-md`, `--shadow-elevated`, `--page-bg` scrim at 60%, Escape
  closes, focus trapped, focus returns to the trigger.
- Error surface: the existing `ErrorBanner` with its retry affordance is the
  standard. No silent `.catch(() => {})`.
- Empty state must contain: an icon, one line of what, one line of why / the
  next move, and (where one exists) the action control.

---

## Motion

The `globals.css` motion tokens are good and are kept:
`--motion-fast 150ms · --motion-normal 300ms · --motion-slow 400ms`, with
`--ease-standard / -exit / -enter`.

- **Default transition:** `--motion-fast` `--ease-standard` for hover/press
  colour and transform; `--motion-normal` for entrances and layout.
- **Signature entrance:** content reveals with a 12px rise + fade over
  `--motion-normal`, staggered 40ms per item. Standardise the existing `Reveal`
  component to this and use it consistently.
- **Numbers** may count up once on first mount (`AnimatedCounter`), capped at
  600ms.
- **What must not animate:** data values on re-render, anything on scroll
  (no parallax), decorative loops, the segmented-control pill on initial paint.
- **Reduced motion:** the existing `@media (prefers-reduced-motion: reduce)`
  block (animations/transitions to `0.01ms`) stays; entrances become instant,
  count-ups render final value.

---

## Accessibility floor

Non-negotiable. A miss is release-blocking unless a documented exception names
the rule, the reason, the owner, and the expiry.

- [ ] Contrast meets WCAG AA against the **actual** background — including the
      new `--primary` (`#4f3fe0` / `#a79bff`) and `--accent-signal`
      (`#e0512f` / `#ff7d5c`): body text ≥4.5:1, large text and UI ≥3:1, on both
      themes. **Verify with the luminance formula before the tokens land**; if a
      proposed hue misses, darken/lighten it within the same hue family and
      record the final value.
- [ ] Every interactive element is keyboard-reachable with a visible 3px focus
      ring; focus never removed, focus order matches visual order.
- [ ] Icons that carry meaning have an accessible name; informative images have
      meaningful `alt`; decorative ones are `aria-hidden`.
- [ ] Nothing conveys information by colour alone — charts, deltas, statuses all
      carry a label, shape, or value.
- [ ] `prefers-reduced-motion` respected (block already present).
- [ ] Headings nest correctly (one `<h1>` per page at Display M), landmarks
      present, form labels associated, errors linked via `aria-describedby`.
- [ ] Touch targets ≥44px; body text ≥13px.

---

## Anti-patterns — never ship these

Output is checked against this list.

- [ ] Generic AI gradient-on-everything; glassmorphism; a purple-blue hero blob.
- [ ] `scale()` hover/press on buttons or cards (replace with colour + 1px lift).
- [ ] Spacing off the 4px grid — the `py-1.5` / `gap-2.5` / `mt-0.5` habit.
- [ ] Colours outside the token tables (including chart colours).
- [ ] A fourth font family, or a serif display swap without a decision record.
- [ ] `--primary` and `--accent-signal` both fighting for the primary-action
      slot in one viewport.
- [ ] Decorative motion on data; count-ups that re-fire on every render.
- [ ] Body text below 13px; interior page titles below Display M (32px).
- [ ] Centred walls of text — the landing "one idea" block caps at two lines.
- [ ] Emoji standing in for iconography or for real copy.

---

## Rollout

The contract applies to the whole web surface, but lands screen by screen so the
identity is proven on something real before it is spread.

1. **Reference screen — Landing (`app/page.tsx`).** First impression, no data
   dependencies, fastest visual-iteration loop. Build it to this contract, then
   render it at desktop / tablet / mobile / dark and compare against the token
   and anti-pattern lists. This render *is* the "three directions to react to" —
   a real screen beats a mockup, and it doubles as the identity sign-off.
2. **Discovery (`app/(app)/discovery/page.tsx`).** The product's soul — click-only
   navigation, taxonomy drilldown, result cards, leaderboard. Proves the
   contract on a data-dense interior screen.
3. **Auth (`login`, `signup`, `onboarding`).** Small, self-contained, shares the
   landing's identity.
4. **Core app (`home`, `paper/[id]`, `author/[id]`).** The read-heavy screens;
   exercises the data-viz palette and the type scale hardest.
5. **Workspace / nexus / horizon.** The deep feature surfaces; last because they
   are the most complex and benefit from the patterns settling first.

`[NEEDS CLARIFICATION: Reference-screen order — Landing first (recommended:
no data deps, fastest loop, identity showcase) or Discovery first (the product's
core click-only UX, but slower to iterate)? Everything after step 1 follows in
the order above regardless.]`

---

## How to use

Check every generated surface against the token tables, the type scale, the
spacing scale, the accessibility floor, and the anti-pattern list. If something
this contract does not cover comes up, add a dated decision in `decisions/` and
update this file — do not let the agent answer it silently. `refactoring` checks
the implemented surface against this document after each rollout step.
