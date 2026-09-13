# 0022 - Signals: A Unified, Habit-Forming Alerts System

**Date:** 2026-09-12
**Status:** Accepted — implemented on `feature/frontend-redesign-2026-09` (2026-09-13), pending PR review and merge to `main`

## Context

The product owner asked for a from-scratch competitive read — not a features
comparison, a retention-mechanics one: what makes a researcher check a site
repeatedly. Five products were fetched and read directly (not just searched):
[elicit.com](https://elicit.com), [overleaf.com](https://www.overleaf.com),
[consensus.app](https://consensus.app), [researchrabbit.ai](https://www.researchrabbit.ai),
plus search-grounded reads of Google Scholar's citation-alert docs, Litmaps'
"Monitor" feature, ResearchGate's stats/notification system, and documented
criticism of Academia.edu's "someone searched for you" emails. `academia.edu`
itself returned HTTP 403 to a direct fetch; findings on it rest on independent
sources describing its actual behavior, cited below.

Applying Nir Eyal's Hook Model (trigger → action → variable reward →
investment — a named, citable framework, not an invented one) to what was
read:

| Product | Trigger | Investment (switching cost) |
|---|---|---|
| Google Scholar | "Cited by" email, daily-only frequency | Saved alerts, author profile |
| ResearchGate | Weekly stats-report-ready notification, citation match | Accumulated stats history, RG score |
| Academia.edu | "Someone searched for you on Google" — documented as opt-out only *after* account creation | Uploaded papers, profile |
| Litmaps | "Monitor" — alerts on a saved map's new citing/related papers, explicitly pitched as replacing manual re-checking of Google Scholar | The map itself |
| ResearchRabbit | Follow authors/topics; "Follow your curiosity" / "rabbit hole" framing | Collections, citation graph history |
| Elicit | "Alerts... without cluttering your inbox"; a persistent Library "to reuse in future projects" | The Library |
| Overleaf | None — retention is structural (co-authors are mid-edit, you must return), not notification-driven | Document + collaborator history |
| Consensus | No push trigger found; in-session reward only (Consensus Meter) | Collections |

**The gap, stated precisely:** every product supplies exactly one slice of
signal — citations only (Scholar, ResearchGate), followed-people/topics only
(ResearchRabbit, Litmaps), or pure collaboration dependency (Overleaf). None
combines a researcher's own citations, the people/topics they follow, and
their collaboration activity in one place. SkoLab already owns all three
ingredients (Profile has citation data, Discovery now has Track/follow per
`decisions/0021`, CoLab has collaboration) — no other product read has this
combination natively.

**What SkoLab has today, read directly from the code:** `NotificationsBell.tsx`
and `useNotifications.ts` already exist and work, but `ActivityType` in
`lib/types.ts` is only `"paper_published" | "connection_made" | "trending"` —
**no citation-received type exists at all**, despite citation alerts being
the one mechanic every academic-metrics competitor above ships. The hook's
own comment states plainly: *"A dedicated notifications backend (connection
requests to accept, workspace invites, @mentions) is a follow-up — this
gives the bell real content now."* This decision is that follow-up, plus the
citation and Track/follow dimensions the comment didn't yet anticipate.

## Decision

- **Extend, don't replace, the real `NotificationsBell`/`useNotifications`
  system** with new `ActivityType` values: `citation_received`,
  `tracked_researcher_paper`, `tracked_topic_activity`, and the
  mentions/invites the code's own comment already named.
- **Citations to your own papers is the highest-priority addition** — the
  single most universal, longest-proven researcher-retention mechanic found
  (Scholar since its alerts existed, ResearchGate's core loop), and
  currently absent from SkoLab entirely.
- **Track (from `decisions/0021`) now feeds notifications**, closing the
  loop from "bookmark a researcher" to "hear when they publish" —
  completing the ResearchRabbit/Litmaps pattern SkoLab didn't have.
- **A full Notifications page** (grouped by day, filterable by kind) is
  added for when volume exceeds the dropdown's 12-item cap.
- **A "Manage alerts" settings screen** with per-kind cadence (real-time /
  daily digest / weekly digest / off) — modeled on ResearchGate's weekly-
  digest cadence, but every control is visible and changeable without
  creating an account, the explicit, deliberate opposite of Academia.edu's
  documented dark pattern.
- **Every notification must be true and specific.** No vague virality bait
  ("someone is interested in your work"); each row names the real actor,
  the real paper, the real count. This is the same honesty rule already
  applied to CoLab's Quick Reference (`decisions/0019`) and Discovery's
  Highlights (`decisions/0021`) — extended here to a third surface.

## Alternatives considered

- **Copy Academia.edu's ego-bait notification style** since it is,
  documented, effective at driving return visits. Rejected on the strength
  of its own criticism as a dark pattern (opt-out gated behind account
  creation) — effectiveness through deception is not the standard this
  project holds itself to elsewhere.
- **A single combined "Activity" score or health meter** instead of
  itemized alerts. Rejected for the same reason ResearchGate's opaque RG
  Score was already rejected in `decisions/0021` — an unexplainable number
  erodes trust.

## Addendum (2026-09-12): empty-state fix from self-audit

A session self-audit found the notifications inbox had no designed
empty state — a brand-new user with nothing tracked yet would see a blank
panel. Fixed with a dedicated empty state that nudges toward tracking
researchers in Discovery, per this decision's own Hook Model framing:
the "Investment" step needs a prompt, not silence, or the loop this
decision is built around never starts for a new user.

## Addendum (2026-09-12): second self-audit — a dedicated audit pass

A follow-up audit (independent agent, not a self-check) found every screen
in this canvas wrongly showed "Home" as the active top-bar nav item — this
is a bell-icon destination, not a nav tab, and should show no active item
at all, exactly the pattern already established for Profile's
`_topbar_none.html` (`decisions/0023`). Fixed by applying that same
all-inactive pattern here. Also fixed: this decision's own canvas
annotation claimed a plain "paper from a connection" kind was kept
alongside the new tracked-researcher-paper kind, but no screen actually
showed one — reworded the annotation to state plainly that the old kind
was folded into the new one (tracking a connection is one click, so a
separate untracked-connection-paper kind would be redundant), rather than
leaving the annotation and the screens contradicting each other. A
connection notification was also mistagged under the "CoLab" filter on the
full Notifications page; fixed by adding a proper "Connections" filter
chip and retagging it. The full Notifications page was also missing a bulk
"Mark all read" control that the bell dropdown already had — added.

## Addendum (2026-09-13): backlog closure — exact badge count, loading/error states, token-drift fix

The bell badge now shows an exact unread count (matching the real
`NotificationsBell.tsx`'s `{unreadCount > 9 ? "9+" : unreadCount}` pattern)
in a small `--notification`-colored circle, replacing the plain dot, on
every screen where the bell appears with unread items; the empty state
correctly still shows no badge at all. Added `NotificationsLoading.dc.html`
(skeleton dropdown rows) and `NotificationsError.dc.html` (a failed-to-load
state matching the real `ErrorBanner.tsx` pattern) for the loading/error
gap. Also fixed the same token-drift bug found across every canvas this
session: accent colors were pulled from a superseded palette instead of
the real live decision-0013 values — corrected here too (see
`decisions/0019`'s addendum for the exact values and hand-verified
contrast numbers). Mentions/invites sharing one settings row remains open,
lower-priority.

## Addendum (2026-09-13): implemented in `apps/web` and the Go gateway

Implemented on `feature/frontend-redesign-2026-09` (merged from a dedicated
worktree branch). `ActivityType` gained all five new kinds; `useNotifications.ts`
and `NotificationsBell.tsx` render each with real, specific copy (never a
generic placeholder); `/notifications` (grouped by day, the five filter
chips, mark-all-read, empty/loading/error states) and `/notifications/
manage` (per-kind cadence, `users/{uid}/settings/notifications`, direct
client write per decision 0004, no account required to change) both ship.

End-to-end, not just UI-ready:

- **`citation_received`** — `internal/activity/activity.go`'s
  `citationAlert` reuses the same OpenAlex `cited_by_count` already computed
  for `/author/[id]`, compares it against a watermark at
  `users/{uid}/notification_state/state`, and emits one coarse "N new
  citations since you last checked" item when it rises — never claiming to
  know which specific paper is responsible, per this decision's own honesty
  rule. The very first check for a user seeds the watermark without emitting
  anything, so pre-existing citations are never misreported as new.
- **`tracked_researcher_paper`** — `trackedResearcherPapers` reads
  `users/{uid}/tracked_researchers` (Firestore) and surfaces each tracked
  author's recent OpenAlex works. Discovery's Track feature (decisions/0021,
  same merge) now writes that exact collection, so this closes the full
  bookmark-then-notify loop end to end, not just half of it.

Deliberately deferred, UI-ready only (see the code comments in
`useNotifications.ts` and `internal/activity/activity.go` for the exact
reasoning):

- **`tracked_topic_activity`** — no topic-follow feature exists anywhere in
  Discovery to source it from.
- **`mention`** / **`invite`** — CoLab has no `@mention` parser and no
  workspace-invite event stream to source them from.

Both render correctly if an `ActivityItem` of that type ever arrives, but no
backend path produces one today. Building either is real, separate work
(a topic-follow feature; a mention-parser + invite-event emitter) and out of
this decision's scope to fabricate just to make the screen "work."

**Verified**: `go build ./...`, `go vet ./...`, and `go test ./...` for
`services/backend-go` all pass cleanly, including 13 new/existing tests in
`internal/activity` (`TestTrackedResearcherIDs_*`, `TestCitationAlert_*`,
`TestGetActivityFeed_*`) and `internal/firestore`.

## Consequences (superseded by the addendum above once merged)

Reference mockup (4 screens: enriched bell dropdown, full Notifications
page, Manage alerts settings, empty inbox with the track-researchers
nudge) is a Claude Design canvas linked from `HANDOFF.md`.
