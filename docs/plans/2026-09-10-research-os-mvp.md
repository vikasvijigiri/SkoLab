# Research OS MVP

## Product contract

SkoLab's first connected product loop is:

`Discovery signal → CoLab project → Horizon opportunity → evidence-aware next action`

The MVP keeps the existing OpenAlex, React Query and Firestore contracts. It
does not introduce a second backend or duplicate persistence layer.

## Delivered

- Home now exposes a Research Command Center with actionable links and local,
  device-scoped completion state.
- Discovery can download a lightweight field brief and hand the selected topic
  directly to Horizon.
- Horizon presents evidence and experiment checkpoints and links results into
  CoLab.
- CoLab now explains its role as a research project control room and links the
  discovery/opportunity loop.
- Profile introduces the research identity/capability direction and ORCID
  entry point.
- Settings includes private-work and source-backed-answer controls, persisted
  locally until institutional policy storage is implemented.
- Nexus remains available by direct route but is intentionally hidden from
  primary navigation while it is reshaped into an evidence workspace.

## Boundaries

The trust toggles are UX preferences, not yet server-enforced data policy. A
future backend release must enforce workspace visibility, model-data policy,
audit events and export/deletion guarantees before making institutional claims.

## Verification evidence

- `npx tsc --noEmit` — passed.
- `npm run lint` — passed.
- `npm test -- --run` — 30 files / 110 tests passed.
- `npm run build` — passed; all app routes generated successfully.
