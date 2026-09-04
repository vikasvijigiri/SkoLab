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
## Phase 0b — blind index (landed)

`users.email_bidx`: `HMAC-SHA256(EMAIL_BLIND_INDEX_KEY, email.strip().lower())`,
hex, indexed. Written by the `User.validate_user_email` validator; existing
rows filled by `services/backend/scripts/backfill_email_bidx.py`. The Go
gateway computes the same HMAC (`internal/recommendation/emailBlindIndex`) and
matches on `email_bidx = ANY($1)`, so `check-registered` returns
`registered_emails` again without either service decrypting anything.
Cross-language parity is pinned by shared test vectors on both sides.

- **`EMAIL_BLIND_INDEX_KEY` is separate from `DATABASE_ENCRYPTION_KEY`** —
  equality-lookup index vs at-rest confidentiality, different rotation story.
  Empty ⇒ email matching is silently skipped, no error.
- **Release steps (owner runs, not an agent):** set `EMAIL_BLIND_INDEX_KEY` in
  both services' environments, `alembic upgrade head`, then
  `python scripts/backfill_email_bidx.py`.

## Phase 0b — hard-auth flip (landed)

The recommendations group in `main.go` now uses `auth.VerifyUser()` — 401
without a valid Firebase token. Both clients attach one: Android via
`network/AuthInterceptor.kt` (#27), web via `apiRequest({ idToken })`. The
per-IP rate limit and the 200-identifier cap on `check-registered` remain as
abuse limits on authenticated callers. `VerifyUserOptional` stays in
`internal/auth/firebase.go` as the pattern for any future transitional
cutover, but nothing uses it now.

> **Old app builds** that predate #27 lose peer-autocomplete / invite-logging
> (they get 401); the rest of the app is unaffected. Acceptable — the feature
> degrades, it does not crash. This PR carried an explicit merge hold until an
> Android release with #27's interceptor was confirmed out; the owner
> (2026-09-04) directed merging ahead of that confirmation, accepting the
> tradeoff for any pre-#27 install still in the field. `VerifyUserOptional`
> remains available to reopen a transitional window if that turns out to
> matter in practice.

## Consequences

- The enumeration oracle is closed for tokenless callers (401) and bounded for
  authenticated ones (rate limit + 200-id cap).
- `check-registered` resolves emails again once the migration + backfill run;
  until then `registered_emails` is empty (no error).
- Web `logPeerInvite` / `dismissDailyFeedItem` send the Firebase token; Android
  attaches it via the OkHttp interceptor (#27).
- One more slice of Python is gone; `services/backend` moves toward LLM-only.
- See `docs/research/2026-09-03-python-llm-only-boundary.md` for the pattern
  grounding and `docs/plans/2026-09-03-python-llm-only-phase0.md` for the full
  migration roadmap.
