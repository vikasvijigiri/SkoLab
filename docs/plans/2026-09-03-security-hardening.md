# Deployment-readiness security hardening (Stream B)

**Slug:** security-hardening
**Worktree:** `../SkoLab-wt/security` · branch `feat/security-hardening` · base `bd4e04d`
**Risk:** HIGH — auth surface + config fail-fast + middleware removal on a service about to go public.

## Context

PR #25 puts the Python backend on a **public Render URL**. The backend
critique's top findings stop being defense-in-depth and become live:

- **Auth is not enforced at the Python layer.** Only `POST /agent/chat` uses
  `get_verified_user`. Every other user-scoped route — author profiles/metrics,
  daily feed, quests, `daily_feed/dismiss` (a write), grants, journal advisor —
  trusts an `author_id` / `user_id` **query param with no verification**. On a
  public URL: IDOR across every user-data surface. `docs/backend-auth-posture.md`
  documents this as intentional ("gateway does coarse auth") — but the Python
  service is directly reachable once deployed, and the router is also mounted at
  the bare prefix (`app/main.py:962`).
- **Shipped default `DATABASE_ENCRYPTION_KEY`** in `config.py:49`, fail-fast
  guarded only for `environment == "production"` — and `APP_ENV` is still unset
  (defaults to `development`), so a Render deploy that forgets it encrypts PII
  under a public key with no error.
- The UA/query-string "WAF" middleware (`main.py:582-613`) blocks `curl`,
  `python-requests`, `playwright` — breaks the repo's own k6 / Playwright / uptime
  tooling and stops nothing real.

## Six fields

**Goal:** Enforce Firebase token verification for user-scoped routes in the
Python app (defense in depth behind the gateway), make a missing/default
encryption key or unset `APP_ENV` fail the boot on Render, and delete the
substring WAF.

**Constraints:**
- **Do not break the gateway path or anonymous public routes.** Public
  OpenAlex-derived routes (search, suggestions, trending) stay public.
- Reuse `get_verified_user` / `get_optional_user` (`app/api/dependencies.py`) —
  no new auth mechanism.
- Keep the auth-posture guard test (`tests/api/test_auth_posture.py`) — update
  its `EXPECTED_*` sets to the new posture in the same commit; the test failing
  is the signal, not a thing to suppress.
- Render sets real env vars; local dev (`APP_ENV` unset) must still boot.
- CI (`APP_ENV` unset, fake keys) must stay green.

**Input:**
- `app/api/dependencies.py` (`get_verified_user`, `get_optional_user`, `get_db`).
- `app/core/config.py:212` (`__post_init__` fail-fast) + `_DEFAULT_DB_ENCRYPTION_KEY`.
- `app/main.py:481-750` (the middleware stack: WAF, device-signature,
  rate-limiter) and `:960-962` (double router mount).
- Endpoint modules with an `author_id` / `user_id` query param:
  `authors.py`, `feed.py`, `discovery_engine.py`, `domains/quest/router.py`,
  `domains/recommendation/router.py` (peers now Go, ignore), `user_memory.py`.
- `tests/api/test_auth_posture.py`, `docs/backend-auth-posture.md`.

**Output:**
- A reusable `require_owner(author_id | user_id)` dependency (in
  `dependencies.py`) that runs `get_verified_user` and asserts
  `token["uid"] == <the id in the request>`, raising 403 on mismatch.
- Every user-scoped GET/POST that takes `author_id` / `user_id` for the
  *requesting* user depends on it; routes where `author_id` is a *lookup
  target* (viewing another researcher's public profile) stay public or use
  `get_optional_user` — decide per route in Task 2, record the table in the
  posture doc.
- `config.py.__post_init__`: raise unless `APP_ENV` is explicitly one of
  `development|staging|production`, AND (in staging/production) a real
  `DATABASE_ENCRYPTION_KEY`. A new `require_env` list checked at boot:
  `DATABASE_URL` present, `APP_BASE_URL` non-default in prod.
- WAF middleware block deleted from `app/main.py`; `main.py` no longer mounts
  `api_router` at the bare prefix (only `/api/v1`).
- `tests/api/test_auth_posture.py` `EXPECTED_*` updated; new tests: 403 on
  uid≠author_id, boot raises on default key + `APP_ENV=production`.
- `docs/backend-auth-posture.md` rewritten to the new posture.

**Done Checks:**
- `cd services/backend && python -m pytest tests/ -q` green; new auth tests pass;
  `test_auth_posture.py` reflects the new sets.
- `APP_ENV=production DATABASE_ENCRYPTION_KEY=<default> python -c "import app.core.config"` → `RuntimeError`.
- `python -c "import app.main"` still succeeds with `APP_ENV` unset (dev).
- `grep -n "python-requests\|bot_keywords\|xss_patterns" app/main.py` → nothing.
- `grep -n "include_router(api_router)$" app/main.py` → only the `/api/v1` line.
- CI green on the branch.

**Out of Scope:**
- The device-signature middleware (separate call — it is theatre but removing it
  needs its own review of what, if anything, relied on it).
- The 220 swallowed `except Exception` blocks, `mypy` vacuity, per-process
  metrics — later.
- Any Go / gateway change (the gateway already fails auth closed in release).
- Moving endpoints to Go (Phases 1–2).

## Tasks

### Task 1: `require_owner` dependency + config fail-fast
- Add `require_owner` to `dependencies.py`; add the env validation to
  `config.py.__post_init__`.
- Tests: `tests/api/test_auth_posture.py` new cases + a `test_config_fail_fast.py`.
- **Verify:** the two `Done Checks` config commands; new tests pass.

### Task 2: Apply `require_owner` per route; classify each
- Walk every route with `author_id` / `user_id`. For each, decide: **owner**
  (`require_owner`), **optional** (`get_optional_user`, personalise-if-present),
  or **public** (lookup of public data). Write the table into the posture doc.
- Update `EXPECTED_AUTHED` / `EXPECTED_OPTIONAL` in the guard test to match.
- **Verify:** `pytest tests/ -q` green; guard test green.

### Task 3: Delete the WAF middleware + the bare-prefix router mount
- Remove the `bot_keywords` / `xss_patterns` blocks from
  `security_guard_middleware`; keep the kill-switch + admin-guard + rate-limit
  parts.
- `app/main.py`: drop `app.include_router(api_router)` (keep the `prefix="/api/v1"` one).
- **Verify:** `pytest tests/ -q` green; a `curl -A python-requests localhost:8000/health` is no longer 403 (test it in `tests/`); route table halves.

### Task 4: Rewrite `docs/backend-auth-posture.md`, `TASK.md` entry

## Merge coordination

Touches `dependencies.py`, `config.py`, `main.py`, and adds a dependency line to
several endpoint files. **Conflict-free with A** (services layer) and **C**
(Android/Go). Its endpoint-file edits will conflict with Phase 1/2 — **land B
before starting Phase 1/2.** Highest priority of the three streams.
