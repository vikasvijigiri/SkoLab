# 0019 - CoLab Workspace: Consolidated Writing Screen

**Date:** 2026-09-12
**Status:** Accepted (design approved; implementation not yet started)

## Context

CoLab Workspace's Documents tab, Chat tab, Equations tab, and the Share modal
were each a separate destination reached via the left icon rail, in a
`(app)/workspace/[id]` page boxed into a centred 1128px column with empty
gutters on wider screens. A design review (screens mocked as a Claude Design
canvas, approved by the product owner) found this fragmented: switching to
Chat or Equations meant losing your place in the document, and the boxed
column wasted roughly a third of the screen on desktop.

## Decision

- **Chat and Equations move into the Documents tab itself**, as tabs in a
  dockable right-side panel next to the editor (tab strip: Quick reference /
  Chat / Equations). This is the same pattern Overleaf uses for its chat
  panel, and matches where collaborative-editor UX is trending industry-wide
  (dockable, switchable sidebars rather than separate full-screen
  destinations per tool).
- **Share stays a modal**, opened from an icon at the end of the dock's tab
  strip (and from the toolbar) — it's an occasional action, not something to
  keep open while writing, so a modal is the right pattern, not a panel.
- **The left icon rail drops from 5 destinations to 3**: Documents, Tasks &
  Meetings, Members. Chat and Equations no longer need their own rail icon.
- **The dock is collapsible** to a 40px rail (icon to reopen + small status
  dots for "abstract under length" / "unread chat") rather than disappearing
  outright.
- **Documents, Members, and Tasks & Meetings all drop the boxed 1128px
  column** in favour of the full viewport width, so switching tabs doesn't
  resize the screen.
- **Focus mode stays a separate, deliberately more minimal surface** — its
  whole point is fewer surfaces on screen (top bar and category rail
  collapse to a 48px strip; the file rail and dock both disappear), so it is
  not merged into the same layout as the dock-equipped default view.
- **New: a domain-aware journal template picker** in the Documents toolbar
  ("Templates" button) — pick a research domain (Physics/Chemistry/
  Biology/Comp. Sci/General), then a journal within it (e.g. Physical Review
  Letters, Physical Review B, IOP's *New Journal of Physics*, EPL), each
  showing real submission specs (page/word limit, reference style,
  abstract limit) sourced from each publisher's actual author guidelines.
  This extends `WorkspaceResearchActions`'s existing 4 generic templates
  (IMRaD, IEEE, Nature, Systematic review) rather than replacing them.
- **A live, honest "Quick reference" panel** (word count vs. the selected
  journal's limit, abstract character count, reference count, a structure
  checklist) — counts and structure only, explicitly not a fabricated
  compliance score, matching the existing `ResearchTools.tsx` originality
  preflight's own stated rule.
- **A `/` slash-command insert menu** in the editor (equation, figure/table,
  citation search against OpenAlex, section-from-template, and "Ask AI"
  clearly tagged as a paid tier rather than presented as a core feature).

## Alternatives considered

- **Keep Chat/Equations as separate tabs, just visually refresh them.**
  Rejected — it doesn't fix the actual problem (losing your place in the
  document to check a message or an equation), and research on the category
  confirms dockable panels are the trend for exactly this reason.
- **E-commerce-style design language** (Amazon/Flipkart-referenced, initially
  considered per the product owner's request to compare against LinkedIn).
  Rejected for this surface: transactional browse/cart/checkout IA doesn't
  map to a research-collaboration tool. Their card-grid scannability
  discipline was kept for the Home screen's project grid; their visual
  language was not.
- **A single global visual overhaul instead of a scoped one.** Rejected for
  now — scope was deliberately held to CoLab Workspace + Profile per the
  session's starting instruction; the rest of the app is untouched.

## Addendum (2026-09-12): top bar and category rail

- **Top bar and "research category rail" nav icons scaled up** into big
  colored badges (44px nav icons in tinted/solid rounded-square fills, 32px
  icon chips on each category-rail pill) — the previous 14-19px bare-stroke
  icons were unreadable at a glance. Active nav item gets a solid fill
  (white icon on the accent color) instead of a bare tint.
- **The category rail no longer renders on Workspace or Profile pages.**
  Its pills (`For you`/`Papers`/`People`/`Topics`/`Methods`/`Datasets`/
  `Grants`/`Jobs`) are Discovery filters and never highlight on any other
  route — showing 52px of permanently-inert nav on every CoLab/Profile
  screen was clutter, not affordance. It still renders normally on Home/
  Discovery. This changes `AppShell.tsx`: `ResearchCategoryRail` needs to
  become conditional on route rather than unconditionally rendered.
- Accent color values (`--accent-indigo`, `--accent-violet`, `--accent-
  orange`, `--accent-emerald`, `--accent-amber`) were nudged to slightly
  more saturated shades to read clearly inside the new icon badges;
  contrast against white was not re-verified against the `check:contrast`
  script referenced in `DESIGN.md` — do that before implementation.
## Addendum (2026-09-12): self-audit fixes

A full pass across every screen built this session (prompted by the user
asking "see if anything is missing") found and fixed three real gaps here:
the unified dock's Chat and Equations tabs had labels but no designed
content until now (the Chat content matches what was shown during the
"Writing" consolidation prototype; Equations is a new compact version of
the shared LaTeX blackboard sized for the 320px panel, linking out to the
full split-view board); Home had no empty state (no workspaces yet) or
"New Project" form actually open, both now designed; Documents had no
read-only (Viewer role) state, now designed — Templates/Cite/AI disappear
(edit-gated), Delete disappears (owner-gated), Share correctly stays
(not gated in the real component).

- **Superseded, same day:** the top bar's own height/color/icon treatment
  described above (88px, white, badge icons) was replaced by
  `decisions/0020`'s 48px solid-blue bar. The category-rail-removal-from-
  Workspace/Profile decision above is unaffected and still stands.

## Addendum (2026-09-12): second self-audit — a dedicated audit pass

The user asked for a full re-verification of every screen/button across all
four redesign canvases, done via independent audit agents per canvas rather
than a self-check. This canvas's audit found the real multi-document file
list (`DocumentsTab.tsx`'s existing per-document sidebar) had been dropped
entirely during the redesign — an accidental regression, not a deliberate
simplification, since neither this decision nor 0020 ever proposed removing
it. Fixed: a 200px "Files" sidebar (document list + rename/delete affordance
+ "New document") now sits between the icon rail and the editor on every
Documents-family screen. Also fixed: standalone "Cite" and "AI · PRO"
toolbar buttons that duplicated the `/` slash-menu's citation/AI entries
with no designed behavior of their own (removed, since the slash menu is
this decision's sole documented entry point for those actions); an
undocumented "Pending invites / Resend" section on Members with no backing
in the real `ShareModal.tsx` invite flow (removed as unbacked scope); the
Chat dock tab's unread dot not clearing when Chat was active; a missing
dropdown chevron on the collapsed state's Templates button; and Share's
"People with access (7)" count not matching its 3 rendered rows. Two
leftover exploration files from the earlier top-bar A/B/C round
(`TopBarOptions.dc.html`, `topbar-options.html`) were deleted.

## Addendum (2026-09-13): backlog closure — mobile, loading/error, and a token-drift fix

The three items left open above are now closed. First, a real bug was
found while closing the contrast item: every mockup file's accent colors
(`--accent-amber/indigo/emerald/rose/violet/orange`) turned out to be
pulled from the superseded "direction C" palette, not the values actually
live today in `globals.css`'s decision-0013 theme — meaning the mockups
never matched the real app's current colors at all, contrast aside. Fixed
by replacing every occurrence with the real live hex values (amber
`#915907`, indigo `#0a66c2`, emerald `#057642`, rose `#b24020`, violet
`#5b4bb7`, orange `#b24020` — note orange and rose are identical in the
real app's own tokens, not a mistake introduced here). Contrast for these
real values was then computed by hand using the exact WCAG formula from
`apps/web/scripts/check-contrast.mjs`: all six clear both the 3.0:1
(UI/icon) and 4.5:1 (text) thresholds against white/`--surface`, ranging
5.5:1 to 6.7:1 — the contrast item is closed with a verified answer, not
left as "not yet re-checked."

Separately, a route-level verification (`decisions/0024`) found this
canvas's "Home" annotations actually mock the real `/workspace` route (the
project list — its scope filter is a verbatim match to
`/workspace/page.tsx`'s `SCOPES`), not the app's actual `/home` feed,
which is a different, unrelated page. The file names (`HomeEmpty.dc.html`
etc.) are kept as-is to avoid an unnecessary rename, but the canvas
annotations now say "Workspace" and note the mix-up explicitly. The real
`/home` feed is designed separately — see `decisions/0024`.

Also added: a Home loading skeleton (`HomeLoading.dc.html`) and a
Documents save-failure state (`DocumentsError.dc.html`, matching the real
`ErrorBanner.tsx` pattern) for the previously-undesigned loading/error gap.
And three mobile artboards (390×844) answering the "what replaces the
mobile tab strip" question the redesign had left open: `MobileHome.dc.html`,
`MobileDocuments.dc.html` (Files and dock relocate to icon-triggered
bottom sheets at this width), and `MobileDocumentsDockOpen.dc.html`
(the dock as an open bottom sheet with a scrim, since — like the desktop
dock's collapse/expand — this is a real layout-state change, not a
same-page tweak). 18 screens total now.

## Consequences

This is a design decision, not yet implemented in `apps/web`. The approved
mockup (13 screens: Home default/empty/new-project; Documents in its
default/Templates-open/Share-open/dock-collapsed/Chat/Equations/read-only
states; Focus mode; Members; Tasks & Meetings) is the reference for
implementation — see the Claude Design canvas linked from `HANDOFF.md`.
`ChatTab.tsx` and `EquationsTab.tsx` become panels rendered inside the
Documents tab rather than top-level tab components; `WorkspaceDetailContent`'s
`TABS` array shrinks from 5 to 3 entries. `ResearchTools.tsx`'s
`JOURNAL_TEMPLATES` gains journal-specific (not just generic) entries with
real per-journal metadata (limits, reference style) used to drive the Quick
Reference panel's live counts. The Files sidebar added in the second audit
pass implies `DocumentsTab.tsx`'s existing document-switching logic stays
in the redesign, just relocated visually — no new backend needed for it.
