# 0012. Web visual identity: pivot to dark-first "Instrument" (direction B)

**Date:** 2026-09-08
**Status:** Accepted
**Supersedes:** the direction-C decision embedded in `DESIGN.md` (2026-09-07)
and its round-1 rollout plan `docs/plans/2026-09-07-web-visual-identity-round1.md`.

## Context

`DESIGN.md` adopted direction **C — "Confident Modernist"** on 2026-09-07: keep
the light ground and the token architecture, inject identity through scale,
saturation, and decisive use of the existing colour system. That contract was
fully rolled out across the web surface (commit `f5fe5d2`) — tokens, primitives,
and every screen, with a clean Playwright axe WCAG AA pass in light and dark.

The direction-C contract itself named the one condition under which it should be
abandoned:

> "a product decision to reposition SkoLab as a pro data terminal for power
> users (→ B) … Both are positioning calls, not taste calls."

On 2026-09-08 the product owner made exactly that call: SkoLab is repositioned
as a **power-user research instrument**, with an explicit brief —
"world-class, FAANG-level, futuristic, modern, ultra-fast". The owner chose
direction **B — "Instrument / Terminal"** (dark-first, high-density, monospace
promoted to structure, one vivid accent on dark) over maxing out C or a hybrid.

## Decision

- Adopt direction **B** as the web visual identity. `DESIGN.md` is rewritten to
  B and is the authority.
- **Dark is the base theme** (`:root`); light is retained as a first-class,
  fully-supported override (`prefers-color-scheme: light` + `[data-theme="light"]`).
  Light is not dropped — it is no longer the default.
- **Keep** from direction C: the token *architecture* (bare custom properties →
  `@theme inline` → `--color-*`), the 3-block theme sync, the WCAG luminance
  discipline and `scripts/check-contrast.mjs` gate, the Inter + JetBrains Mono
  two-family stack, and the "no `scale()` hover" rule.
- **Change:** the token *values* (dark-pitched palette, indigo-violet `--primary`
  that pops on dark, warm `--accent-signal` for the single primary action, a new
  `--accent-live` green for status), the default density (one notch tighter),
  the radius scale (crisper), motion (faster, mechanical, native View
  Transitions for routes), and elevation (border + light, not drop shadow, on
  the dark ground).

## Why

### The deciding fact is positioning, not taste

A power-user instrument read against reference data for hours converges on a
dark ground with bright data — every terminal, DAW, and observability console
does, for an ergonomic reason. Direction C was correct for a general-audience
product; the product is no longer positioned that way.

### The brief points at B

"Futuristic, FAANG-level, ultra-fast" reads as B without gimmicks: density,
monospace structure, a decisive accent, mechanical motion. A is retro; C is the
safe general-audience choice.

### The cost is real and is accepted

Every screen's tokens, the primitive components, the theme default, and the
WCAG pass are touched again — a second migration two days after the first. The
owner accepted this explicitly. The architecture survives the pivot (the dark
theme already existed and was contrast-checked), so the work is a
values-and-default change plus per-component density, not a rebuild.

## Rollback

- `git checkout f5fe5d2 -- DESIGN.md apps/web/src/app/globals.css` restores the
  direction-C contract and tokens.
- Phase C of the rollout plan is staged (tokens → primitives → shell → features
  → performance), so each step is independently revertible without unwinding the
  whole pivot.
- The `frontend-ui` skill and the performance reference (Phase A, commit
  `7844a28`) are independent of the identity choice and stay regardless.

## Consequences

- `refactoring` checks each rolled-out surface against the new `DESIGN.md`.
- Any future re-target to a general (non-power-user) audience is a new
  positioning decision recorded here, not a screen-by-screen drift back to C.
