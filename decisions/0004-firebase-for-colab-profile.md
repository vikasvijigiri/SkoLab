# 0004. Firebase/Firestore direct-write for CoLab and Profile, no REST layer

**Date:** 2026-06-29
**Status:** Accepted

## Context

CoLab Workspace (projects, chat, shared equations/manuscript, tasks,
meetings) and Profile are inherently collaborative, frequently-updated,
realtime-shaped state. The Android app already needed Firebase Auth; adding
Firestore for this feature set reuses that same project rather than
standing up a second identity/data system.

## Decision

Android and web both read and write CoLab/Profile data **directly against
Firestore**, with no REST API in between on the Go/Python backend for these
features. Everything else (search, enrichment, recommendations, metrics)
goes through the Go gateway / Python backend as normal.

## Alternatives considered

- **REST API in front of Firestore for these features too**, for
  consistency with the rest of the backend surface. Rejected: it would just
  be a thin, laggier proxy in front of Firestore's own realtime sync, adding
  a hop with no real benefit — the whole value of Firestore here is
  client-side realtime listeners.
- **A different backend (e.g. a Postgres-backed WebSocket layer) for
  realtime CoLab state.** Considered, but would duplicate what Firebase
  Realtime/Firestore already does well, and would mean building and
  maintaining a second auth system alongside Firebase Auth.

## Consequences

Two data-access patterns exist in the codebase side by side: REST-via-
gateway for most things, direct-Firestore for CoLab/Profile — an agent
working on either area needs to know which pattern applies before assuming
"add an endpoint" is the right move. In exchange, Android and web stay in
sync on CoLab/Profile data automatically, with no custom sync logic to
maintain.

**Known gap (see `HANDOFF.md`):** the web app's Firebase Web app is not yet
registered in the `skolab-vvi` Firebase project, so this whole feature set is
currently non-functional on web until that's done — it fails with an
explicit "not configured" error rather than silently.
