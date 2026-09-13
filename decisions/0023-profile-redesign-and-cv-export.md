# 0023 - Profile Redesign and CV Export

**Date:** 2026-09-12
**Status:** Accepted (design approved; implementation not yet started)

## Context

Profile was named in this session's original scope alongside CoLab
Workspace ("the COLAB screen and its related screens and profile screens")
but had gone completely undesigned through eight other screens' worth of
work — a self-audit near the end of the session (prompted by the user
asking to check for anything missing) caught the gap before it went
unnoticed further. Separately, mid-session the user asked for a CV/resume
generation and sharing feature.

Reading the real `apps/web/src/app/(app)/profile/page.tsx` surfaced one
concrete quality issue worth fixing while redesigning: the "Research
identity & capability graph" card promises a graph ("what you can
contribute... where collaboration is welcome") and renders none — just a
paragraph and an external ORCID link. This is the same "half-finished
feature" pattern flagged earlier in this session's initial CoLab audit.

## Decision

- **The vague capability-graph card is replaced with a real one**: a
  co-author relationship graph reusing the exact SVG node-graph pattern
  built for Discovery's researcher profiles (`decisions/0021`) — an honest,
  deliverable feature instead of a promise with nothing behind it.
- **No nav item is active on Profile.** Profile is reached from the account
  menu, not the top bar (`BAR_NAV` in the real `nav.ts` already filters
  Profile out) — the mockup's top bar correctly shows no highlighted item.
- **A citation-count strip links out to Signals** (`decisions/0022`),
  connecting Profile to the new alerts system rather than duplicating
  citation display logic.
- **New: CV export and sharing.** A "Create CV" action generates a single
  clean page from the same data already on the profile (name, status,
  research focus, about, metrics, top publications) — nothing invented,
  everything sourced from fields the profile already holds.
- **Sharing is click-first, matching SkoLab's own no-typing-first
  philosophy**: "Send to a connection" is a one-click list of existing
  connections; a free-text email/phone field is the explicit fallback for
  reaching someone not yet on SkoLab, not the default path. Copy-link and
  PDF download need no typing at all.
- The generated CV explicitly states *"Anyone with the link sees this same
  page — whoever opens it doesn't need a SkoLab account"* — no ambiguity
  about what sharing actually does.

## Alternatives considered

- **Leave the capability-graph card as copy-only, just soften the wording.**
  Rejected — the point of the earlier audit finding was that promising a
  feature without shipping it reads as unfinished regardless of how the
  copy is worded; building the honest version was already possible by
  reusing Discovery's existing graph component.
- **Free-text email/phone as the primary share flow.** Rejected in favor of
  a connections list first — SkoLab already models connections, and typing
  an address should be the exception path, not the default one, consistent
  with the product's stated click-only-where-possible direction.

## Addendum (2026-09-12): second self-audit — a dedicated audit pass

A follow-up audit (independent agent, not a self-check) caught an ironic
repeat of the exact problem this decision set out to fix: the new
relationship-graph card had its own "View full graph" link pointing
nowhere, promising a feature with nothing behind it, on the card that
exists specifically to stop doing that. Fixed by removing the dead link
and, separately, enriching the graph itself to actually match the Discovery
pattern it claims to reuse — real co-author names (not just initials), a
peer-to-peer edge (not a pure hub-and-spoke layout), and a caption stating
nodes are clickable. The audit also found two real interaction gaps the
first pass missed: the OpenAlex "Change" re-link control had no designed
flow at all (now shows an inline candidate-picker panel with Select/Cancel),
and "Delete account" had no confirmation step for a destructive, irreversible
action (now shows an inline "This can't be undone" / Confirm / Cancel
state). Smaller fixes: "Create CV" was accidentally missing from the
sidebar in edit mode; the CV's copy-link button didn't say what URL it
copies; 3 of 8 real academic-status chips were missing from the edit form;
and the danger-zone color used `--accent-rose` instead of the app's actual
`--notification` semantic token.

## Addendum (2026-09-13): backlog closure — loading state, save-error banner, token-drift fix

Added `ProfileLoading.dc.html` (skeleton sidebar and cards, no active nav
item, matching `_topbar_none.html`) and a save-error banner on
`ProfileEdit.dc.html` (matching the real `ErrorBanner.tsx` pattern:
"Couldn't save your changes — try again."). Also fixed the same
token-drift bug found across every canvas this session: accent colors
were pulled from a superseded palette instead of the real live
decision-0013 values — corrected here too (see `decisions/0019`'s
addendum for the exact values and hand-verified contrast numbers). No
empty-state for the CV share panel's connections list remains open,
lower-priority.

## Consequences

Not yet implemented in `apps/web`. Reference mockup (3 screens: Profile
view, Profile edit, CV export/share) is a Claude Design canvas linked from
`HANDOFF.md`. Implementation touches `apps/web/src/app/(app)/profile/
page.tsx` (replacing the capability-graph card, adding the Create CV
entry point, the OpenAlex re-link candidate picker, and a two-step delete
confirmation), needs a new CV-generation route/component sourcing from
`useMyProfile`'s existing data, and a share flow that lists the viewer's
real connections before falling back to a free-text recipient field.
