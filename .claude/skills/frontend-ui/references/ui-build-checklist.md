---
name: ui-build-checklist
description: The build method and WCAG AA floor for user-facing UI. Adapted from addyosmani/agent-skills frontend-ui-engineering + accessibility-checklist. Read in frontend-ui Phase 1.
---

# UI build checklist

**Source:** `github.com/addyosmani/agent-skills` — `skills/frontend-ui-engineering/SKILL.md`
and `references/accessibility-checklist.md`, commit `6ca0cd7`, pulled 2026-09-08.
Condensed for this repo; the AI-aesthetic table and the WCAG lists are verbatim.
Local authority is still `DESIGN.md` — where this file and `DESIGN.md` disagree,
`DESIGN.md` wins.

## Component architecture

- Colocate: `Thing/Thing.tsx`, `Thing.test.tsx`, `use-thing.ts`, `types.ts`.
- Composition over configuration — `<Card><CardHeader>…` beats
  `<Card title= headerVariant= bodyPadding= content=…>`.
- Keep components focused: one job each. Split anything over ~200 lines.
- Separate data from presentation: a `…Container` resolves
  loading/error/empty and renders the pure presentational component.

## State management — simplest that works

| Reach for | When |
|---|---|
| `useState` | component-local UI state |
| lifted state | shared between 2–3 siblings |
| context | theme, auth, locale (read-heavy, write-rare) |
| URL (`searchParams`) | filters, pagination, tabs — shareable UI state |
| server-state lib (React Query / SWR) | remote data with caching |
| global store | complex client state shared app-wide |

Avoid prop-drilling deeper than 3 levels.

## Avoid the AI aesthetic

| AI default | Why it is a problem | Production quality |
|---|---|---|
| Purple/indigo everything | "safe" palette; every app looks identical | the project's actual palette |
| Excessive gradients | visual noise, clashes with the design system | flat, or a subtle system gradient |
| Rounded everything (`rounded-2xl`) | ignores the hierarchy of corner radii | consistent radius from the system |
| Generic hero sections | template layout, no tie to the content | content-first layout |
| Lorem ipsum copy | hides length/wrap/overflow problems | realistic placeholder content |
| Oversized padding everywhere | destroys hierarchy, wastes space | one consistent spacing scale |
| Stock card grids | ignores information priority | purpose-driven layout |
| Shadow-heavy design | competes with content, slow on low-end | subtle or none unless specified |

## Spacing, type, colour

- One spacing scale — no invented values (`13px`, `2.3rem` are bugs).
- Respect the type hierarchy: one `<h1>`; never skip heading levels; never use
  a heading style for non-heading content.
- Semantic colour tokens (`text-primary`, `bg-surface`) — not raw hex.
- Contrast ≥ 4.5:1 normal text, ≥ 3:1 large text and UI components.
- Never colour-only for state — pair with icon, text, or shape.

## Accessibility — WCAG 2.1 AA

### Keyboard
- [ ] Every interactive element focusable via Tab
- [ ] Focus order follows visual/logical order
- [ ] Focus visible (`:focus-visible` ring) — never `outline: none` without a replacement
- [ ] Custom widgets: Enter activates, Escape closes; no keyboard trap
- [ ] Skip-to-content link, visible on focus
- [ ] Modals trap focus while open and return it to the trigger on close

### Screen readers
- [ ] Images have `alt` (or `alt=""` if decorative); decorative icons `aria-hidden="true"`
- [ ] Every input has a `<label>` or `aria-label`; icon-only buttons have `aria-label`
- [ ] Links/buttons have descriptive text, not "click here"
- [ ] One `<h1>`; headings do not skip levels
- [ ] Dynamic changes announced (`aria-live="polite"`, `role="status"` / `role="alert"`)
- [ ] Tables use `<th>` with `scope`

### Visual / forms / content
- [ ] Text resizes to 200% without breaking layout
- [ ] Nothing flashes more than 3×/second
- [ ] Every input has a visible label; required state not by colour alone
- [ ] Errors specific, tied to the field, shown by more than colour, and focus moves to the first error on submit
- [ ] Known fields use `autocomplete`; correct `type` / `inputmode`
- [ ] `<html lang>` set; descriptive `<title>`
- [ ] Touch targets ≥ 44×44px
- [ ] Meaningful empty states — never a blank screen

### ARIA live regions

| Value | Behaviour | Use for |
|---|---|---|
| `aria-live="polite"` / `role="status"` | announced at next pause | status, saved confirmations |
| `aria-live="assertive"` / `role="alert"` | announced immediately | errors, time-sensitive alerts |

### Anti-patterns

`div` as button · missing `alt` · colour-only state · autoplaying media ·
custom dropdown with no ARIA (prefer native `<select>`) · removed focus
outline · empty links/buttons · `tabindex > 0`.

## Responsive

Mobile-first. Test at **320 / 768 / 1024 / 1440**.

## Loading and transitions

- Skeletons for content, not spinners; hold width/height to prevent CLS.
- Optimistic updates for perceived speed; roll back on error.
- `aria-busy="true"` + `aria-label` on the loading region.

## Verification (run after building UI)

- [ ] Renders with no console errors
- [ ] Every interactive element keyboard-reachable (Tab through the page)
- [ ] Screen reader conveys content and structure
- [ ] Works at 320 / 768 / 1024 / 1440
- [ ] Loading, error, and empty states all handled
- [ ] Follows `DESIGN.md` spacing, colour, type
- [ ] No axe-core violations (`npm run test:e2e`) and contrast gate green (`npm run check:contrast`)

## Red flags

Components > 200 lines · inline styles or arbitrary pixel values · missing
error/loading/empty states · no keyboard testing · colour as the sole state
indicator · the generic AI look.
