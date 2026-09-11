# 0018. Firestore security rules, not a REST layer, close the CoLab/Profile access-control gap

**Date:** 2026-09-11
**Status:** Accepted

## Context

A market-research pass (see the report cited in `LOG.md`'s 2026-09-11 entry)
flagged that CoLab Workspace and Profile — direct-Firestore by design
(`decisions/0004`) — had **no `firestore.rules` file anywhere in the repo**.
Access control, if any, lived only in whatever was pasted into the Firebase
console ad hoc, unversioned and unreviewed. The owner's first framing of the
fix was "build a REST backend for CoLab," which is exactly the option
`decisions/0004` already considered and rejected (a REST layer in front of
Firestore is "a laggier proxy... with no real benefit" — it would cost the
realtime listeners that are the whole point of using Firestore here).

The two concerns were conflated: *lack of a backend* and *lack of enforced
access control* are not the same problem, and only the second one is real.

## Decision

Write and version-control `firestore.rules` instead of a REST API. Keep
`decisions/0004`'s architecture (direct client↔Firestore, realtime listeners)
fully intact. Enforce, in rules:

- `researchers/{uid}` — any signed-in user may read (profile directory,
  invite-by-email lookup); only the owner may write their own document.
- `collabs_groups/{projectId}` — read/write gated on membership and role
  (owner / editor / reviewer / viewer), matching the Overleaf-style model
  already in `apps/web/src/lib/firebase/workspace.ts`. Only the owner may
  change membership or delete the project. Chat messages are append-only.
  Presence docs are self-writable only.

Firestore's rules language can't search an array of objects (`members`) for
a nested `role` field, so `apps/web/src/lib/firebase/workspace.ts` now
derives and persists two additional flat uid arrays — `editorUids`,
`commenterUids` — alongside the existing `memberUids`, recomputed from
`members` on every membership/role write (`deriveRoleArrays`). Projects
written before this change lack the two new fields; rules fall back to
"any member may edit/comment" for those (today's de facto behavior) rather
than locking out existing collaborators.

## Alternatives considered

- **REST API in front of Firestore** (the owner's first framing) — rejected
  again, for the same reason `decisions/0004` rejected it the first time.
- **Leave rules as whatever's in the console** — rejected: unversioned,
  unreviewable, and the actual gap the research surfaced.
- **A second "roles" subcollection instead of derived arrays on the project
  doc** — more normalized, but adds a `get()` per role check inside rules
  that are already doing one `get()` per subcollection request; the flat
  arrays match the pattern the code already used for `memberUids` and cost
  one extra field to keep in sync, not a new read.

## Consequences

`firestore.rules` (repo root) is now the enforced access-control source of
truth, plus `firebase.json` / `.firebaserc` so it can be deployed with
`npx firebase deploy --only firestore:rules`, and
`tests/firestore/rules.test.ts` (`npm run test:rules`) exercises it against
the Firestore emulator. **Not deployed as part of this change** — deploying
means `firebase login` (an owner-only step; the agent that wrote this had no
Firebase CLI credentials) and then the deploy command; until that happens,
production Firestore is still running on whatever rules existed before this
file. The emulator test suite also could not be run in the environment that
wrote it (`java` is not on that machine's PATH; the Firestore emulator
requires a JVM) — it's verified to fail on the Java check specifically, not
on rules syntax or test logic, but has not been green anywhere yet. Both are
the concrete next steps, not optional cleanup.
