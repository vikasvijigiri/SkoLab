# 0020 - Global Top Bar: Compact Solid Brand-Blue

**Date:** 2026-09-12
**Status:** Accepted (design approved; implementation not yet started)

## Context

The web app's top bar (`TopBar.tsx`) went through three design passes in one
session, all as a Claude Design canvas mockup, before landing on a final
choice:

1. Original: 72px, white background, 14-19px bare-stroke icons — found
   illegible when reviewed at a glance ("i cant see icons").
2. Interim: 88px, white background, 44px icons in colored tint/solid
   badges — corrected legibility but overshot on size ("big big" was the
   ask; in hindsight, too big). Recorded as an addendum to
   `decisions/0019`, which is CoLab-scoped and not the right home for a
   change affecting every screen in the app.
3. Final (this decision): three options (restrained gray, compact colored
   chips, solid brand-blue) were mocked side by side and researched against
   real industry norms — 48-56px is the median primary-header height for
   SaaS products (64px only for consumer-leaning products) — before the
   product owner picked one.

## Decision

- **Height: 48px** (bottom of the real-world 48-56px norm — smaller than
  both prior passes).
- **Background: solid `--primary` blue**, not white. Logo mark becomes a
  white square on blue; wordmark, nav icons/labels, bell, and avatar ring
  all render in white/white-tinted rather than the app's usual text-primary
  black. This is a deliberate, larger visual-identity swing than decision
  0013's "white bordered panels" framing — the product owner chose it
  knowingly, after the trade-off was flagged.
- Nav items: 66x40px, icon 18px + 10px label stacked, inactive at 72%
  white opacity, active at full white with a white 2px underline bar.
- Applied identically across **every screen**, not just Home/Discovery:
  CoLab Workspace (all Documents states, Members, Tasks & Meetings),
  Discovery (all three modes + Compare), the researcher Highlights/
  dashboard pages, and both Horizon screens. Focus mode is the one
  deliberate exception — it already collapses all top chrome to its own
  minimal 48px strip and was not given this bar at all.
- The "research category rail" (`For you`/`Papers`/`People`/etc.) is
  unaffected by this decision — it keeps its own white background and
  icon-chip treatment from `decisions/0019`'s addendum, and still only
  renders on Home/Discovery per that same addendum.

## Consequences

Supersedes the 88px/badge-icon top bar recorded as an addendum to
`decisions/0019` — that addendum's *removal of the category rail from
Workspace/Profile* still stands, only the top bar's own height/color/icon
sizing is superseded here. `TopBar.tsx` needs new height, background, and
icon-treatment props/classes; `--text-on-primary` (already `#ffffff` in the
light theme) is the correct token for the bar's foreground rather than a
new one. Not yet implemented in `apps/web` — reference mockups are the two
Claude Design canvases linked from `HANDOFF.md`.
