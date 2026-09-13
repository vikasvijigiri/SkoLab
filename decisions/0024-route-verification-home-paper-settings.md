# 0024 - Route-Level Verification: Home Feed, Paper Detail, Settings

**Date:** 2026-09-13
**Status:** Accepted — implemented on `feature/frontend-redesign-2026-09` (2026-09-13), pending PR review and merge to `main`

## Context

After two audit-and-fix passes closed every gap within the four redesigned
areas (CoLab, Discovery+Horizon, Signals, Profile), the product owner asked
for one more check: not "is anything missing within what we redesigned,"
but "does every real route in the app have a design at all." Comparing
every `page.tsx` under `apps/web/src/app/(app)/` against the four existing
canvases found three real, user-facing routes with zero design coverage:

- **`/home`** — a substantial, separate page (`home-client.tsx`, 224
  lines): a personalized 3-column feed (AI Daily Brief, a unified
  papers/news/activity/jobs feed, Identity Strength, workspaces shortcut,
  peer suggestions). Confused at first with the CoLab canvas's screen
  *labeled* "Home" — that screen actually mocks the real `/workspace`
  route (its scope filter is a verbatim match to `/workspace/page.tsx`'s
  `SCOPES`), not `/home`. The label was corrected in `decisions/0019`'s
  files; the real `/home` feed had never been touched by any canvas.
- **`/paper/[id]`** — a full paper detail + AI analysis page. Discovery's
  Papers mode (TL;DR cards) had no click-through to it at all — confirmed
  by grep, not just inspection.
- **`/settings`** — appearance/theme, notifications, account & privacy,
  and trust/research-data controls. Its own Notifications section already
  says, verbatim, *"Granular per-type controls... arrive with the
  dedicated notifications service"* — a direct forward-reference to what
  `decisions/0022` (Signals) built this same session, never connected.

(`/nexus` also exists but is deliberately hidden from navigation per its
own code comment — mid-rework, correctly out of scope. Auth/onboarding
routes were excluded as out of the session's scope from the start.)

## Decision

- **Design all three**, reusing the same design system and honesty rules
  established across the other four canvases (light Professional Network
  theme, decision 0013's real token values, decision 0020's top bar,
  no-fabricated-confidence).
- **Home feed**: keep the real 3-column architecture — it's already
  well-scoped, not reinvented. The `IdentityStrengthCard` is redesigned as
  a grounded checklist (what's actually complete/missing), not an opaque
  percentage, matching the same rule already applied to Discovery's
  researcher cards. A cold-start (`profileUnresolved`) state is designed
  for a brand-new user, since most of this page has nothing real to
  personalize against until an OpenAlex profile resolves.
- **Paper detail**: the real page is already well-built (an honest
  confidence badge, a section literally titled "Honest Limitations") — the
  redesign's job is visual consistency with the rest of the app plus
  wiring the missing entry point. Discovery's Papers cards gain a "Read
  full analysis →" link into this page.
- **Settings**: the Notifications section's vague forward-reference is
  replaced with a real "Manage alerts →" link into Signals' cadence
  settings screen, now that the destination exists. Everything else is a
  visual-consistency pass, not a redesign — the page's actual structure
  and controls are sound.

## Alternatives considered

- **Leave these three routes out of scope**, since they weren't named in
  the session's original or expanded scope. Rejected once the product
  owner asked to touch them — leaving a route with a direct, admitted
  forward-reference to an already-built feature (`/settings`) unconnected
  would be the same kind of half-finished state this session has
  repeatedly fixed elsewhere.

## Consequences

Reference mockups: Home feed (2 screens) —
https://claude.ai/code/artifact/25456c65-ecb2-4c3d-b033-76277b72c96b;
Paper detail (3 screens) —
https://claude.ai/code/artifact/07cbf4cc-3ef0-4dab-af58-c8c7ad1b62bf;
Settings (1 screen) —
https://claude.ai/code/artifact/43da870e-a872-46d4-a35b-94ad671bcffb.
Implementation (this same session, `feature/frontend-redesign-2026-09`)
found much of the real functionality behind these three pages already
exists — `home-client.tsx`'s feed architecture, and `paper/[id]/page.tsx`'s
entire intelligence report, TOC, loading, and partial-failure handling are
already faithful to what was mocked — so the real delta is smaller than
the mockups alone suggest: the Discovery→Paper-detail link, the
Settings→Signals link, and IdentityStrengthCard's score-vs-checklist
question (verified against the live component during implementation, not
assumed).
