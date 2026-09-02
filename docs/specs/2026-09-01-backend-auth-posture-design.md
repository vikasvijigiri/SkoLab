# Backend auth posture lock + PR #6 loose ends (design)

**Date:** 2026-09-01
**Status:** proposed — for `task-analysis` to scope, Gate 1 to approve
**Base:** `main` after PR #6 merges (`feat/backend-api-contracts` @ `3317f03`, CI green)

---

## 0. Decomposition of the remaining backlog

The "all in one" request spans three independent subsystems. Each gets its own
spec → plan → PR. This spec is **SP-1** (the smallest — PR #6 leftovers). SP-2
and SP-3 are sketched here only for ordering; they are not designed yet.

| # | Sub-project | Area | Size | Depends on |
|---|---|---|---|---|
| **SP-1** | **Auth posture lock + openapi snapshot** (this spec) | `services/backend` | S | PR #6 merged |
| SP-2 | Backend observability — Sentry (inert until DSN) + `/livez`÷`/readyz` split | `services/backend` | M | PR #6 merged |
| SP-3 | Web Firestore-realtime — `useFirestoreDoc`/`useFirestoreCollection` + god-component split of `profile`, `workspace`, `workspace/[id]`, `home` (Firestore half) | `apps/web` | L | none (independent) |

**Order:** SP-1 → SP-2 → SP-3. SP-1/SP-2 are both backend and near-independent
(SP-1 touches no route bodies; SP-2 touches `main.py` health routes only);
SP-1 first because it arms a guard PR #6 left disarmed. SP-3 is a separate
codebase area and the largest — last.

---

## 1. The problem (SP-1)

Two loose ends from the PR #6 API-contracts pass:

| Thing | Current state | Evidence |
|---|---|---|
| **Auth posture is undocumented and unguarded** | Exactly **one** route requires auth (`POST /agent/chat` → `Depends(get_verified_user)`). Three use `get_optional_user` (`/chat_with_author`, `/discovery/predict`, `/discovery/nexus-chat`). Every other FastAPI route is unauthenticated **by design** (public OpenAlex data; real enforcement is the Go gateway's fail-closed Firebase check, commit `f057dc5`). Nothing asserts this set — a new route added without auth is invisible. | `grep get_verified_user services/backend/app` → 1 hit in a route body |
| **OpenAPI drift-guard is disarmed** | PR #6 added `services/backend/tests/test_openapi.py`, which `skip`s itself and writes `api-contracts/openapi.snapshot.json` on first run. The snapshot was never committed (no local Python env to generate `app.openapi()`), so the "schema equals snapshot" assertion never runs. | `test_openapi.py::test_schema_matches_committed_snapshot` → `pytest.skip` in CI (`132 passed, 1 skipped`) |

Neither is a bug today. Both are **latent regressions**: an auth control that
can be dropped silently, and a contract-drift guard that is green because it
isn't checking anything.

## 2. Constraints

- **Do not change which routes require auth.** This spec *locks* the current
  posture, it does not re-decide it. Changing enforcement is a separate,
  security-reviewed change.
- The 401/403 from `get_verified_user` already flows through PR #6's
  `ErrorResponse` envelope (`HTTPException` → `_http_handler`); `test_threat_modeling.py`
  already asserts 401 on `/agent/chat` for missing/expired/invalid tokens.
  This spec adds the *set-level* guard, not per-route 401 tests.
- `app.openapi()` cannot run in the dev environment (Python 3.14, deps won't
  build). The snapshot must be generated somewhere that can.
- Python 3.10 CI pin, Pydantic v2, `asyncio_mode=auto`, tests run in `ci.yml`.

## 3. Approaches considered

### A — Guard test + committed snapshot + a short posture doc (chosen)

- `tests/api/test_auth_posture.py`: iterate `app.routes`, classify each route by
  whether its dependency tree contains `get_verified_user` / `get_optional_user`
  / neither, and assert the three sets **equal a checked-in expected mapping**.
  A new route shifts a set → test fails → the author must consciously add it to
  the mapping (and justify a public one in review).
- Generate `api-contracts/openapi.snapshot.json` **in CI** via a one-shot
  workflow step (or a committed `make`-style target run once by the user), then
  commit it so `test_openapi.py`'s equality assertion goes live.
- `docs/backend-auth-posture.md` (short): the gateway-in-front model, the 1
  authed + 3 optional routes, why the rest are public, how to add an authed
  route.

**Wins:** the posture becomes a test, not a comment; the drift-guard actually
guards; ~120 LOC + a doc; zero route-body changes.
**Loses:** the expected-mapping is hand-maintained (but that's the point — it
forces a decision); snapshot generation needs a CI round-trip.

### B — Decorator/registry-based auth declaration

Introduce `@public` / `@authed` markers on every route and a startup assertion
that every route carries exactly one.

**Wins:** intent is explicit at each route.
**Loses:** touches all ~30 route definitions for a property that's currently
uniform; churn out of proportion to the risk; re-litigates B's "should this be
public" at every route in one PR.

### C — Do nothing / fold into SP-2

Leave the posture undocumented; commit the snapshot as a throwaway step in SP-2.

**Loses:** the auth-set regression stays invisible; SP-2's scope blurs.

## 4. Chosen — A, and why

- **Over B:** the posture is uniform (1 authed route), so a per-route
  declaration system is machinery for a problem that doesn't exist yet. A's
  single expected-mapping captures the same intent in one file and fails just
  as loudly on drift.
- **Over C:** the snapshot is 5 minutes of CI plumbing and it's the difference
  between `test_openapi.py` guarding the contract and rubber-stamping it. Worth
  its own PR line, not a silent rider on SP-2.

**What would change the choice:** if the auth posture is about to become
non-uniform (SP-2's readiness endpoints, or a future user-data API), B's
per-route declaration earns its keep — revisit then.

**What A gives up:** the expected-mapping must be updated by hand when a route
is legitimately added; a lazy update ("just add it to the public set") is
possible. Mitigated by: the diff to that file is a review focal point, and the
doc says public routes need a one-line justification.

## 5. Design

### 5.1 Auth-posture guard

- `services/backend/tests/api/test_auth_posture.py`:
  - Walk `app.routes` (APIRoute, GET/POST/…). For each, inspect
    `route.dependant` recursively for a call to `get_verified_user` /
    `get_optional_user`.
  - Build `{authed: set[path], optional: set[path], public: set[path]}`.
  - Assert each equals a module-level `EXPECTED_AUTHED` / `EXPECTED_OPTIONAL`
    constant; `public` = everything else and is asserted to be
    `all_paths - authed - optional` (so it can't silently gain a route without
    one of the first two sets also being wrong, *and* a brand-new public route
    trips a separate `EXPECTED_PUBLIC_COUNT` / snapshot check).
  - Failure message points at `docs/backend-auth-posture.md`.
- `EXPECTED_AUTHED = {"/agent/chat"}`,
  `EXPECTED_OPTIONAL = {"/chat_with_author", "/discovery/predict", "/discovery/nexus-chat"}`
  (verify against the live `app.routes` during implementation — these are from a
  grep, not a running app).

### 5.2 OpenAPI snapshot

- Add a step to `ci.yml`'s backend job (after deps install, before `pytest`):
  `python -c "from app.main import app, json; open('../../api-contracts/openapi.snapshot.json','w').write(json.dumps(app.openapi(), indent=2, sort_keys=True)+'\n')"`
  **guarded to only run when the file is absent** — i.e. it bootstraps once.
  Then a human commits the file the CI run produced (it's an artifact / shows
  in the job log), OR:
- [NEEDS CLARIFICATION: snapshot generation — (a) CI bootstraps it once and the
  user commits the artifact from the run, (b) add a committed
  `services/backend/scripts/gen_openapi_snapshot.py` the user runs in any env
  with backend deps and commits, or (c) a CI job that commits the snapshot back
  to the branch on first run. (b) is simplest and self-documenting; (a) needs a
  manual artifact copy; (c) needs a bot token. Recommend (b).]
- Once the file exists, `test_openapi.py::test_schema_matches_committed_snapshot`
  stops skipping and enforces equality — no code change needed there.

### 5.3 Doc

- `docs/backend-auth-posture.md` — ~30 lines:
  - The two-tier model: Go gateway (fail-closed Firebase auth, `f057dc5`) in
    front of the Python backend; the Python backend trusts the gateway for
    coarse auth and only re-verifies where it needs the uid (`/agent/chat`
    keys history to the real uid).
  - Table: the 1 authed + 3 optional routes and why each is what it is.
  - "Adding a route" checklist: default public; if it needs a uid, add
    `get_verified_user` and put the path in `EXPECTED_AUTHED`; a new public
    route needs a one-line why in the PR.

### 5.4 Tests

- `test_auth_posture.py` (5.1) — the guard itself; also a red-green check
  (temporarily add a dummy authed route in a fixture, see the set assertion
  fail).
- No new per-route 401 tests — `test_threat_modeling.py` already covers
  `/agent/chat`.

## 6. Out of scope

- Changing any route's auth requirement (separate security-reviewed change).
- SP-2 (observability) and SP-3 (web Firestore) — their own specs.
- Rate-limiting, the device-signature middleware, CORS — untouched.
- The Go gateway.

## 7. Open markers (batched at Gate 1)

1. §5.2 — how the `openapi.snapshot.json` gets generated given no local Python
   (options a/b/c in the marker; recommend b: a committed generator script the
   user runs once).
