# 0008. `/recommendations/peers*` move to the Go gateway

**Date:** 2026-09-03
**Status:** Accepted

## Context

The owner set a hard rule: `services/backend` (Python) is for LLM and
model-inference services only; every other concern moves to the Go gateway or a
managed service. This sharpens `decisions/0002` — the gateway already fronts
Python, but a large non-LLM surface stayed in FastAPI.

The three CoLab peer-autocomplete endpoints
(`GET /api/v1/recommendations/peers`, `POST .../peers/invite`,
`POST .../peers/check-registered`) are pure Postgres CRUD with no AI. A backend
critique also flagged `check-registered` as an **unauthenticated membership
oracle**: no auth, no rate limit, no batch cap — a caller could enumerate which
emails/phones belong to registered users, in bulk.

Two facts constrained the fix:

- **`users.email` is Fernet-encrypted at rest** with a random IV
  (`app/db/encrypted_type.py`), so `User.email.in_(...)` / `.ilike(...)` never
  matched anything. Email matching in these endpoints was already dead; only
  `display_name` / `username` / `phone` ever resolved.
- **Installed Android builds send no `Authorization` header** — no
  `getIdToken` / `Bearer` anywhere in `apps/android-app`. A hard
  `auth.VerifyUser()` on these routes would break every shipped app.

## Decision

- **Port the three handlers to Go** (`internal/recommendation`, native `pgx`),
  wired in `main.go` above the `NoRoute` proxy so Python never serves them. The
  Python `domains/recommendation/router.py`, `schemas.py`, `service.py` and
  `schemas/recommendation_extra.py` are deleted. `engine.py` (numpy
  `cosine_similarity` / `mmr_diversify`, used by the embedding path) **stays** —
  it is model-inference infrastructure.
- **Transitional auth**: `auth.VerifyUserOptional()` — a valid token sets
  `user_id`, a missing or invalid token passes through. Paired with a per-IP
  rate limit (5 rps, burst 5) and a **200-identifier cap** on
  `check-registered`, which bounds the enumeration risk immediately without
  breaking tokenless clients.
- **Faithful behaviour**: match on plaintext columns only. Email matching stays
  disabled — same as the Python version in practice.

## Alternatives considered

- **Hard `VerifyUser()` now.** Correct end state, but breaks every installed
  Android build until they ship a token-attaching release. Rejected for this
  phase; it is the Phase 0b follow-up, telemetry-gated on tokenless traffic
  falling off.
- **Keep the endpoints in Python, add a `get_verified_user` dependency.** Fixes
  the auth hole but leaves non-LLM CRUD in the Python service, against the rule.
- **Decrypt every candidate email in Go to restore email matching.** A
  security-sensitive Fernet reimplementation and a full-table scan. Rejected in
  favour of a blind index.
- **Blind-index column now.** The right way to do equality lookups on an
  encrypted column (deterministic keyed HMAC in its own column + key). Needs an
  Alembic migration, a backfill that decrypts every row, and a new secret —
  its own change with its own review, not bundled into this move.

## Consequences

- The enumeration oracle is bounded today (rate limit + batch cap) and closed
  fully in Phase 0b (hard auth).
- `check-registered` returns phone matches only until the blind index lands;
  `registered_emails` is always empty. Tracked as a follow-up.
- Web `logPeerInvite` now sends the Firebase token (optional today, ready for
  0b). Android is unchanged and keeps working via the optional path.
- One more slice of Python is gone; `services/backend` moves toward LLM-only.
- See `docs/research/2026-09-03-python-llm-only-boundary.md` for the pattern
  grounding and `docs/plans/2026-09-03-python-llm-only-phase0.md` for the full
  migration roadmap.
