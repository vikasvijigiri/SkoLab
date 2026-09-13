# 0021 - Discovery + Horizon: Fused Researcher Highlights

**Date:** 2026-09-12
**Status:** Accepted — implemented on `feature/frontend-redesign-2026-09` (2026-09-13), pending PR review and merge to `main`

## Context

`decisions/0015` already recorded that merging Discovery and Horizon AI was
raised and explicitly deferred ("Horizon AI is untouched... left open for a
future slice"). This session picked that back up, grounded in a read of the
actual code (`apps/web/src/app/(app)/discovery/page.tsx`,
`.../horizon/page.tsx`, `.../author/[id]/page.tsx`) plus market research:

- Discovery's `ResearcherCard.tsx` already computes real per-researcher
  signals — fit score + why, momentum (rising/steady/cooling) with a
  sparkline, field standing, career stage, open-to-collaboration — but only
  surfaces them as small badges on a browse card.
- `/author/[id]` (`AuthorDetailContent`) is the actual profile page, and is
  dense: stats quad, an 8-axis radar, a citation heatmap, journal advisor,
  AI Gap Finder, publications, similar researchers, all shown at once.
- Horizon AI predicts a field-level breakthrough and never describes an
  individual researcher — it only takes the viewer's own author ID as a
  personalization parameter.
- Competitor research: Google Scholar/Semantic Scholar give raw metrics
  with no synthesis; Scopus/Web of Science/Dimensions are institutional
  dashboards, not built for a quick individual decision; Consensus/Elicit
  synthesize at the paper/field level only, never toward "describe this
  researcher"; ResearchGate's RG Score is criticized specifically for being
  an opaque, gameable single number; none of the reviewed tools offer a
  lightweight way to track candidate collaborators over time or compare two
  researchers side by side, both of which published research on
  collaborator-finding behavior names as real needs.

## Decision

- **`/author/[id]` opens on a new "Highlights" layer** instead of the dense
  dashboard: a short sourced narrative (2-3 sentences), 3-4 hand-picked
  metrics (not the full stat quad + radar), a momentum strip in plain
  English (reusing Discovery's real rising/steady/cooling signal), and a
  **per-person** "what they might work on next" line — Horizon's prediction
  engine, finally pointed at a person, clearly labeled speculative, linking
  out to the full generic Horizon tool for anyone who wants the field-level
  version.
- **Nothing existing is deleted.** The stat quad, 8-axis radar, citation
  heatmap, journal advisor, and AI Gap Finder all remain, demoted to a
  collapsible "Full research dashboard" section below the Highlights layer.
  Horizon's own input/result screens are unchanged in concept.
- **Similar researchers renders as a small relationship graph** (inspired
  by Connected Papers' similarity-graph pattern for papers, applied here to
  people) instead of a flat list, inside the expanded dashboard.
- **New: Track** — a bookmark affordance on both the Discovery card and the
  Highlights page, distinct from "Start a project with X" (which jumps
  straight to CoLab). Fills the "track candidate collaborators over time"
  gap the research names.
- **New: Compare** — select 2 researchers from Discovery's grid and see a
  simple side-by-side (field standing, citation velocity, cross-institution
  co-authors, output timeline). Deliberately kept casual/lightweight,
  unlike Scopus/Web of Science's enterprise-grade author-compare tools.
- **Discovery's Papers mode redesigned around a TL;DR-first layout**: one
  sourced sentence per paper (the same pattern Semantic Scholar ships in
  production, not a novel guess), "highly influential citations" alongside
  the raw count, and a personalized "Fits your field" line. Lower-relevance
  papers are shown visually quieter, not hidden.
- Every honesty rule already established for CoLab's "Quick reference"
  panel applies here too: counts and structure are real and computed, the
  narrative states what it's grounded in, and nothing is presented as a
  score without showing its receipts.

## Alternatives considered

- **Delete Horizon's tab and fold its input form into Discovery.** Rejected
  — Horizon's generic field-level exploration is a distinct, still-useful
  job; the fix is connecting it to individual profiles, not removing it.
- **A gameable single "match score" per researcher** (ResearchGate-style).
  Rejected on the strength of the RG Score criticism found in research —
  opaque scores erode trust; every number shown must be explainable.

## Addendum (2026-09-12): self-audit fixes

A full session self-audit found the Highlights/dashboard page had been
built with the Discovery category rail still attached — a direct
contradiction of this decision's own rule (the rail's pills never apply
outside Home/Discovery, same as Workspace/Profile). Fixed: replaced with
just the "Back to results" link the rail's corner carried. Also added: the
Track button's "already tracking" filled state (previously only the
resting icon was ever shown) and Horizon's real `is_fallback` state,
surfaced as an honest "Estimated — AI unavailable" banner rather than
silently presenting a degraded result as a full prediction.

## Addendum (2026-09-12): second self-audit — a dedicated audit pass

A follow-up audit (independent agent, not a self-check) found the "Full
research dashboard" section — which this decision says keeps the stat
quad, radar, heatmap, Journal Advisor, AI Gap Finder, and publications "all
remain" — had reduced Journal Advisor, AI Gap Finder, and Publications to a
single caption sentence with no actual content, leaving a third of the
promised dashboard undesigned. Fixed with real (not placeholder) content
for all three. Also fixed: Compare had the same category-rail leak the
first audit already caught and fixed on the Highlights page, just missed on
this screen; Discovery's "Start a project with X" button — the real app's
existing core CTA — had been dropped from researcher cards during the
redesign, leaving only the new Track control (an accidental regression, not
a deliberate replacement, since this decision explicitly frames Track as
additive); Horizon's result screen had no way back to the input form; a
Track-state mismatch between Sofia Reyes's Discovery card and her own
Highlights page; an undocumented, dead-end "Message" button; "Connect"
rendering as enabled when the real app always shows it disabled
("Connect — coming soon"); a missing "Open CoLab" actionability link on the
Horizon result; the category rail showing only 4 of the real app's 8
categories; and the Impact Signature pill list showing 5 of 9 real metrics.
All of the above are now fixed in the published canvas.

## Addendum (2026-09-13): backlog closure — loading/failure states and a token-drift fix

The loading and total-failure gaps above are now closed with
`DiscoveryLoading.dc.html` (a skeleton-loading version of Discovery's
People mode) and `HorizonFailed.dc.html` (a genuine no-result failure
state — distinct from `is_fallback`, which still shows a degraded
prediction; this one shows none, with a plain "couldn't generate a
prediction" message and a way back to the input form). Same session also
found and fixed a real token-accuracy bug across this whole canvas: every
accent color was pulled from a superseded palette instead of the values
actually live in `globals.css`'s decision-0013 theme — corrected
everywhere (see `decisions/0019`'s matching addendum for the exact
before/after values and the hand-verified WCAG contrast numbers, which
apply identically here since both canvases share the same token set).
Empty/zero-result states and pagination remain open — lower-priority,
reasonably deferred past this pass.

## Consequences

Not yet implemented in `apps/web`. Reference mockup (7 screens: Discovery
Researchers/Papers/Topics modes, Compare, one researcher profile screen —
Highlights with the full dashboard shown open beneath it, not a separate
duplicate page — Horizon input, Horizon result) is a Claude Design canvas
linked from `HANDOFF.md`. An earlier pass in this canvas showed the
collapsed-dashboard and expanded-dashboard states as two full duplicate
pages; corrected same-day to one page (expanded, since it's a strict
superset) once noticed — the dashboard's collapse/expand is a same-page
accordion, not a distinct screen, unlike CoLab's dock collapse/expand
which changes the whole layout width and earns two mockups in
`decisions/0019`. Implementation touches `AuthorDetailContent`,
`ResearcherCard.tsx` (Track/Compare controls, restored "Start a project"
button), a new Compare route or modal, and `WorkspaceResearchActions`-style
honesty conventions applied to the new narrative/prediction copy (including
the now-fleshed-out Journal Advisor/AI Gap Finder sections) so nothing
states a fabricated confidence.
