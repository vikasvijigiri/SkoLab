# Backend auth posture

Which `services/backend` (FastAPI) routes require authentication, which are
deliberately public, and what to do when adding a route. Enforced by
`services/backend/tests/api/test_auth_posture.py` — that test fails if this
document is out of date.

## Model

- **Two tiers, but the Python layer no longer trusts the edge blindly.** The Go
  gateway (`services/backend-go`) still does the coarse Firebase auth check and
  **fails closed** in release (commit `f057dc5`). As of the 2026-09-03
  deployment hardening the Python service is also directly reachable on a public
  Render URL, so every user-scoped route re-verifies the Firebase ID token
  itself — defense in depth, not a bet that the gateway is the only ingress.
- **Three classes.** Each route resolves, by its dependency tree, to exactly one
  of `authed` / `optional` / `public`. `authed` splits into two shapes:
  - `get_verified_user` — any valid token (e.g. `/agent/chat`, which keys chat
    history to the verified `uid`).
  - `require_owner("<param>")` — a valid token **and** `token["uid"]` equals the
    `user_id` / `author_id` the request carries (path or query). A mismatch is
    an IDOR attempt → **403**. Missing/invalid token → **401**. No identifier in
    the request → **400**. Defined in `app/api/dependencies.py`; pulls in
    `get_verified_user`, so the guard test classifies these routes as `authed`.
- **Most routes are public by design.** They serve public OpenAlex-derived data
  keyed by an *OpenAlex* author id (`A5023888391`, not a Firebase uid) and hold
  no per-user private state. They must keep working for anonymous callers and
  when one researcher views another's profile, so `require_owner` (a uid-equality
  check) would be both wrong and meaningless for them.

## When a route is `require_owner` vs `public`

| Question | `require_owner` | `public` |
| :--- | :--- | :--- |
| Whose data does the `author_id` / `user_id` name? | the **caller's own** (Firebase uid) | **any researcher** (OpenAlex id) — a lookup target |
| Does it read/write per-user **private** state? | yes (memory profile, quest records) | no (public publication record, trending feed) |
| Must it work for an anonymous caller? | no | yes |

If you cannot answer these from the code, leave the route **public** and raise
the question in review — do not guess it into `authed`.

## Current posture

### authed — `Depends(get_verified_user)` (any valid token → 401 without one)

| Route | Why |
| :--- | :--- |
| `POST /agent/chat` | Chat history is stored per verified `uid` (server-side, not a client param). |

### authed — `Depends(require_owner("user_id"))` (token uid must equal `user_id` → 403 on mismatch)

| Route | Why |
| :--- | :--- |
| `GET /industry_academic_tieups` | Reads the caller's **private** semantic-memory profile (`UserMemoryService`); `user_id` is documented as the Firebase UID. |
| `GET /users/quests` | Creates/returns the caller's **private** quest records in Postgres, keyed by `User.id` (the Firebase uid). |

> `POST /daily_feed/dismiss` was here; it moved to the Go gateway in Phase 2 —
> see "Moved off the Python backend" below. Same owner check, ported faithfully.

### optional — `Depends(get_optional_user)` (personalise if a token is present; work anonymously)

| Route | Why |
| :--- | :--- |
| `POST /chat_with_author` | `author_id` is the researcher being discussed (a lookup target); a present token keys history to the real `uid`. |
| `POST /discovery/predict` | `author_id` (body) shapes the prediction for the logged-in user when supplied; still answers anonymously. |
| `POST /discovery/nexus-chat` | Personalises when a token is present; no identifier required. |

### public — no auth dependency

| Route(s) | Note |
| :--- | :--- |
| `GET /`, `GET /ai_status`, `GET /status` | System metadata. `/` is served by `main.py`'s own `AppInfoResponse` handler (see "single mount" below). |
| `GET /health`, `GET /livez`, `GET /readyz` | Infra probes. Liveness/readiness carry no token by design. `GET /metrics` and the local-subnet / SRE-token admin gate in `security_guard_middleware` were removed — the Go gateway owns request metrics (`docs/plans/2026-09-04-retire-python-infra.md`). `GET /ai_status` is now plain public system metadata (row above). |
| `GET /search_author`, `GET /refresh_author` | Resolve **any** researcher's public OpenAlex profile. |
| `GET /author_metrics`, `GET /network_collaborators`, `GET /collaborator_synergy`, `GET /citation_heatmap` | Metrics computed from **any** researcher's public publication record; `author_id` is an OpenAlex id. |
| `GET /match_grants`, `GET /journal_advisor` | Recommendations derived from a researcher's public works; used when viewing arbitrary profiles, not just one's own. |
| `GET /semantic_trending` | Trending papers in a researcher's field; reads only public OpenAlex concepts. |
| `GET /daily_feed`, `GET /daily_conjecture`, `GET /assistant_professor_roadmap`, `GET /industry_opportunities` | Feed/roadmap keyed by an OpenAlex `author_id`; explicitly support anonymous / `default_feed`. (`POST /daily_feed/dismiss` moved to the Go gateway — see "Moved off the Python backend".) |
| `GET /leaderboard/{field}` | Public quest leaderboard. |
| `POST /agent/upload_document` | Stateless document extraction; no identity involved. |
| `GET /summarize_work`, `GET /analyze_paper`, `GET /presentation_outline` | Public paper intelligence. |

> `GET /support/metrics` and the `GET/POST /zotero/*` stubs moved to the Go
> gateway in Phase 2 — see "Moved off the Python backend".

### Open items (tracked, not closed here)

- **`/zotero/*`** — moved to the Go gateway as still-unimplemented stubs
  (`internal/feed/feed.go`). When Zotero linking becomes real it should be
  owner-scoped in Go (`auth.VerifyUser()` + a uid check).

> `POST /daily_feed/dismiss` was in this list; it is now owner-scoped and, as of
> Phase 2, served by the Go gateway (`internal/feed`). The web client
> (`dismissDailyFeedItem`) already sends the Firebase token and targets the same
> gateway path, so it needed no change. A signed-in user who has not linked an
> OpenAlex profile gets 403 on dismiss until they link — acceptable, the feed
> still renders.

### Moved off the Python backend

| Routes | Now served by | Auth |
| :--- | :--- | :--- |
| `GET/POST /api/v1/recommendations/peers`, `/peers/invite`, `/peers/check-registered` | Go gateway — `internal/recommendation` | **Hard `auth.VerifyUser()`** — 401 without a valid Firebase token. The Android client attaches one as of #27 (`network/AuthInterceptor.kt`); the web client via `apiRequest({ idToken })`. A per-IP rate limit (5 rps) + a 200-identifier cap on `check-registered` still bound abuse from an authenticated caller. `decisions/0008`. |
| `POST /api/v1/daily_feed/dismiss` | Go gateway — `internal/feed` | `auth.VerifyUser()` (**401** without a token) **and** the handler requires `users.openalex_id` for the verified uid to equal the body `author_id` — **403** on mismatch or an unlinked account. Same check as the old Python route, ported verbatim (Phase 2, `docs/plans/2026-09-04-phase2-feed-to-go.md`). |
| `GET /api/v1/support/metrics`, `GET /api/v1/integrations/zotero/auth`, `GET /api/v1/integrations/zotero/callback`, `POST /api/v1/integrations/zotero/sync` | Go gateway — `internal/feed` | **Public.** `support/metrics` is a static counter dict; the `zotero/*` routes are OAuth stubs with no per-user state (unchanged from Python). |

Email matching in `peers` / `check-registered` uses the `users.email_bidx`
blind-index column (`users.email` is Fernet-encrypted, so equality/`ILIKE` never
resolved in the Python version either).

The 401/403 raised by these dependencies is returned through the app-level
`ErrorResponse` envelope (`app/api/errors.py`), like every other error.

**Phase 1 (`authors.py` → Go), 2026-09-04 — assessed, nothing moved.** All 8
live author routes (`/refresh_author`, `/search_author`, `/author_metrics`,
`/network_collaborators`, `/collaborator_synergy`, `/citation_heatmap`,
`/match_grants`, `/journal_advisor`) stay in Python: each calls an LLM or an
embedding service, depends on a Firestore cache tier the gateway lacks, or is a
large multi-source port. No route changed auth class — all remain `public`
(public OpenAlex-derived data keyed by an OpenAlex id). See
`decisions/0009-phase1-authors-assessment.md` and
`docs/plans/2026-09-04-phase1-authors-to-go.md`.

## Single router mount

`app/main.py` mounts the aggregate router **only** at `prefix="/api/v1"`. The
former bare-prefix `app.include_router(api_router)` mount was removed in the
2026-09-03 hardening: on a public URL it doubled every route's attack surface and
broke the "one canonical path per endpoint" assumption this posture relies on.
Clients must use `/api/v1`. (The Python kill-switch path-fragment match in
`security_guard_middleware` also depends on it; the per-process rate limiter
that used the same fragments was retired — the Go gateway rate-limits per IP.)

## Boot-time configuration fail-fast

`Settings.__post_init__` (`app/core/config.py`) stops the process when the deploy
environment is misconfigured — a public Render deploy must not silently run as
"development" or encrypt PII under the shipped default key:

- `APP_ENV` unset ⇒ `development` (local dev + CI boot normally). `APP_ENV` set
  to anything **outside** `{development, staging, production}` ⇒ `RuntimeError`.
- `APP_ENV` in `{staging, production}` **and** `DATABASE_ENCRYPTION_KEY` unset or
  still the shipped default ⇒ `RuntimeError`.

## Adding a route

- **Default to public.** A new route needs no auth dependency unless it reads or
  writes the *requesting* user's private state.
- **Acts as the caller and takes their Firebase uid as a param?** Add
  `Depends(require_owner("<param>"))`, then add its normalised path to
  `EXPECTED_AUTHED` in `tests/api/test_auth_posture.py`.
- **Needs the caller's identity but not a uid-equality check?** Use
  `Depends(get_verified_user)` (hard 401) or `Depends(get_optional_user)` (soft,
  personalise-if-present) and update `EXPECTED_AUTHED` / `EXPECTED_OPTIONAL`.
- **Looks up another researcher's public OpenAlex data?** Leave it public and say
  in the PR, in one line, why it is safe to leave unauthenticated.
- **Never** change an existing route's auth class without a security review — the
  guard test failing is the intended signal to stop and get one.
