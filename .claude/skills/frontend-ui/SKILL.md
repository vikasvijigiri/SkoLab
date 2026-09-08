---
name: frontend-ui
description: Build and audit production-grade, accessible, distinctive user-facing UI. Covers component structure, states, the WCAG AA floor, motion, and killing the generic AI look. Triggers include "build the UI", "make this component production-quality", "check accessibility", "audit the design", "review my UI", "why does this look AI-generated". Use this whenever a user-facing surface is built or polished. Do NOT use for code architecture or data-fetching (engineering-standards), the token contract, or the review gate (code-review).
effort: high
model: sonnet
disable-model-invocation: false
allowed-tools: Read Grep Glob Bash
---

# Frontend UI

Build interfaces that read as the work of a design-aware engineer at a top
company — not as AI output. Real design-system adherence, a met accessibility
floor, thoughtful interaction, and no generic "AI aesthetic".

This is a **build-and-audit capability, not a lifecycle stage**. It grounds
`implementation` when the task is a user-facing surface, and runs as an audit
pass any time a screen needs to reach production quality.

## Sources this skill adapts

| Source | Upstream | Verified | Applies to |
|---|---|---|---|
| `references/ui-build-checklist.md` | `github.com/addyosmani/agent-skills` `skills/frontend-ui-engineering` + `references/accessibility-checklist.md` — commit `6ca0cd7` | 2026-09-08 | Next 16 · React 19 · Tailwind v4 |
| `references/web-interface-guidelines.md` | `github.com/vercel-labs/web-interface-guidelines` `command.md` | 2026-09-08 | framework-agnostic web |

**Next review trigger:** a major Next.js or Tailwind release, or an upstream
change to either source. Re-pull, diff, and re-verify against the checklist's
own verification list before trusting a stale copy.

## Phase 1 — SCAN

1. Read `DESIGN.md` (root) end to end — it is the authority for tokens, type
   scale, spacing, components, motion, and the accessibility floor. This skill
   never overrides it; it implements it.
2. Read both files in `references/`. `ui-build-checklist.md` is the build
   method and the WCAG floor; `web-interface-guidelines.md` is the line-level
   rule set to audit against.
3. Grep the target surface for what it already establishes: the primitive
   components (`components/ui/*`), the `cn()`/variant pattern, the semantic
   token utilities (surface/text/border colours, the eyebrow and data type
   roles), the test convention (`*.test.tsx`), and the e2e axe specs.
4. List every state the surface must handle: default, loading, empty, error,
   permission-denied, success, destructive. A surface missing one of these is
   incomplete, not "done pending polish".

## Phase 2 — BUILD or FIX

Work the checklist, not an impression of "good code":

1. **Structure** — composition over configuration; a component over ~200 lines
   or with a growing boolean-prop list has a boundary problem. Separate data
   containers from presentation. Colocate component, styles, and test.
2. **The AI-aesthetic table** in `ui-build-checklist.md` — no purple by
   default, no gradient noise, no maximum-rounding everywhere, no shadow
   stacks, no stock card grids, no oversized uniform padding. Use the
   project's actual tokens.
3. **Accessibility floor** — every interactive element keyboard-reachable with
   a visible focus ring; icon-only controls carry an accessible label; one
   page-level heading, no skipped levels; async changes announced with an
   aria-live region; state never conveyed by colour alone; contrast ≥ 4.5:1
   text / 3:1 UI.
4. **Motion** — animate transform and opacity only, never "transition: all";
   honour the reduced-motion preference; animations interruptible; no
   scale-pop on hover (the generic-AI tell — `DESIGN.md` bans it).
5. **Responsive** — mobile-first; verify at 320 / 768 / 1024 / 1440.
6. **Content** — real placeholder text, not lorem ipsum; truncate or clamp
   long strings with a min-width-0 flex child; every empty state has an icon,
   one line of what, one line of the next move, and the action control where
   one exists.

## Phase 3 — VERIFY

Run, and read the result back — do not trust exit 0:

1. `cd apps/web && npm run test` — component tests green.
2. `cd apps/web && npm run check:contrast` — every token pair clears AA on
   both themes.
3. `cd apps/web && npm run test:e2e` — `@axe-core/playwright` WCAG AA clean on
   every public route, light **and** dark.
4. Keyboard walk: Tab through the surface; focus visible and in logical order;
   no trap; Escape closes overlays; focus returns to the trigger.
5. Tick the checklist's own **Verification** list in `ui-build-checklist.md`
   and the **Anti-patterns** list in `web-interface-guidelines.md`. Name any
   item deliberately not met and why.

## Routing

- **Consulted or run as an audit, not entered as a stage.** `implementation`
  reads this before building a user-facing surface; anyone can run Phase 3 as
  a standalone audit before delivery.
- **`engineering-standards`** owns code-level frontend architecture, state
  ownership, data-fetching, rendering strategy, and bundle governance — the
  boundary is *how the component is wired* (there) vs *how it looks, feels,
  and reads to a user, and whether it clears the a11y floor* (here). Its
  `references/frontend-performance-rules.md` owns the React/Next performance
  rule set; this skill points at it rather than restating it.
- **`DESIGN.md` (owned by `architecture`)** is the visual contract itself — a
  new token, a changed type scale, a motion rule. This skill only consumes
  that contract. A proposed change to it is new work: it goes through
  `task-analysis`, which dispatches `architecture` per workflow.md §Entry —
  never edited from here.
- **`code-review`** owns the merge gate. This skill's Phase 3 is a build-time
  audit, not the sign-off.
- Handoff, only when the consultation implies more than a read or an audit:
  new work with no plan → `task-analysis`; a named UI failure → `debugging`.

## Success

`DESIGN.md` and both `references/` files were read before any markup changed;
the surface handles every required state; the AI-aesthetic table has no
violations; Phase 3's checks were run and quoted; and every unmet
checklist item is named with a reason rather than left silent.
