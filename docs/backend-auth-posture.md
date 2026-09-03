# Backend auth posture

Which `services/backend` (FastAPI) routes require authentication, which are
deliberately public, and what to do when adding a route. Enforced by
`services/backend/tests/api/test_auth_posture.py` — that test fails if this
document is out of date.

## Model

- **Two tiers.** The Go gateway (`services/backend-go`) sits in front of the
  Python backend and does the coarse Firebase auth check. It **fails closed** in
  release when Firebase is unavailable (commit `f057dc5`).
- **The Python backend trusts the gateway for coarse auth** and only re-verifies
  a Firebase ID token itself where it needs the caller's real `uid` — e.g.
  `/agent/chat` keys the agent's chat history to the verified `uid` rather than a
  client-supplied value.
- **Most routes are public by design.** They serve public OpenAlex-derived data
  and hold no per-user state. Making them all require auth would add no security
  and would break anonymous use.

## Current posture

| Class | Routes | Dependency | Why |
| :--- | :--- | :--- | :--- |
| **authed** | `POST /agent/chat` | `Depends(get_verified_user)` → 401 without a valid token | Chat history is stored per verified `uid`. |
| **optional** | `POST /chat_with_author`, `POST /discovery/predict`, `POST /discovery/nexus-chat` | `Depends(get_optional_user)` → `None` when no token | Personalise the result when a token is present; work anonymously otherwise. |
| **public** | every other `APIRoute`, incl. `/health`, `/livez`, `/readyz` | none | Public research data, no per-user state; coarse auth is the gateway's job. |

`/livez` (liveness) and `/readyz` (readiness) are unauthenticated by design — an
orchestrator's probes carry no token, liveness must not depend on auth or the
DB, and readiness only reports dependency health.

### Moved off the Python backend

| Routes | Now served by | Auth |
| :--- | :--- | :--- |
| `GET/POST /api/v1/recommendations/peers`, `/peers/invite`, `/peers/check-registered` | Go gateway — `internal/recommendation` | **Transitional** `VerifyUserOptional` — a valid token sets `user_id`, a missing/invalid one is allowed through; a per-IP rate limit (5 rps) and a 200-identifier cap on `check-registered` bound the enumeration risk. Flips to hard `VerifyUser` once the Android client attaches a token — `decisions/0008`. |

These are pure Postgres CRUD with no AI, so they belong on the Go edge
(`decisions/0002`, `decisions/0008`). Email matching in `peers` /
`check-registered` stays disabled until a deterministic blind-index column
lands — `users.email` is Fernet-encrypted, so equality/`ILIKE` never resolved
in the Python version either.

The 401/403 raised by `get_verified_user` is returned through the app-level
`ErrorResponse` envelope (`app/api/errors.py`), like every other error.

## Adding a route

- **Default to public.** A new route needs no auth dependency unless it reads or
  writes per-user state.
- **Needs the caller's identity?** Add `Depends(get_verified_user)` (hard 401) or
  `Depends(get_optional_user)` (soft, personalise-if-present), then add its
  normalised path to `EXPECTED_AUTHED` / `EXPECTED_OPTIONAL` in
  `tests/api/test_auth_posture.py`.
- **A new public route** trips `test_public_is_the_remainder` only if it somehow
  lands in the wrong set; otherwise it passes silently — so state in the PR
  description, in one line, why it is safe to leave unauthenticated.
- **Never** change an existing route's auth class without a security review — the
  guard test failing is the intended signal to stop and get one.
