# Backend Auth Posture Lock + PR #6 Loose Ends — Implementation Plan

**Goal:** Lock the backend's auth posture with a set-level guard test, give the OpenAPI drift-guard a way to be armed (generator script + doc), and record the posture in a short doc — all `services/backend`, no route-body changes.

**Source spec:** `docs/specs/2026-09-01-backend-auth-posture-design.md` (approach A — set-level guard)

**Slug:** backend-auth-posture (branch: `feat/backend-auth-posture`, stacked on `feat/backend-api-contracts` / PR #6)

**Risk:** LOW — three additive files + two one-line doc edits in `services/backend`; no route bodies, no `main.py`, no `ci.yml` behaviour, no migration/credential surface.

**Blast radius:** a new failing test if the auth posture ever changes silently (that is the point); `tests/api/test_auth_posture.py`, `services/backend/scripts/`, `docs/backend-auth-posture.md`, one line each in `tests/test_openapi.py` and `README.md`. Nothing at runtime.

**Rollback:** `git revert` the branch's commits, or delete the three new files and revert the two one-line edits — no state to unwind, no contract change to reverse.

**Architecture:** Approach A from the spec. Three independent, small deliverables: (1) a `tests/api/` guard that classifies every `app.routes` entry by its dependency tree and asserts the `authed` / `optional` / `public` sets equal a checked-in expectation; (2) a committed generator script for `api-contracts/openapi.snapshot.json` so PR #6's `test_openapi.py` equality assertion can go live (it currently `skip`s because the file is absent); (3) `docs/backend-auth-posture.md`. No `main.py`, no route bodies, no `ci.yml` behaviour change.

**Tech stack and constraints:**

- `services/backend` only. FastAPI 0.141.1, Python 3.10 (CI pin), Pydantic v2, `asyncio_mode=auto`.
- **Do not change which routes require auth.** This plan *locks* the posture, it does not re-decide it.
- Stacked on unmerged PR #6 — `app/api/errors.py`, `tests/api/conftest.py`, `tests/test_openapi.py` exist only on the base branch. This branch's diff is additive.
- The dev environment has no runnable backend Python (3.14, deps won't build). `test_auth_posture.py` is CI-verified, like every PR #6 test. `app.openapi()` likewise cannot run here — hence the generator script rather than a committed snapshot in this plan.
- Tests run in `ci.yml`'s backend job via `pytest tests/` (picks up `tests/api/**` automatically).

## Auth posture (from `app.api.dependencies` + a grep of route bodies — verify against live `app.routes` in T1)

| Class | Routes | Dependency |
|---|---|---|
| **authed** | `POST /agent/chat` | `Depends(get_verified_user)` |
| **optional** | `POST /chat_with_author`, `POST /discovery/predict`, `POST /discovery/nexus-chat` | `Depends(get_optional_user)` |
| **public** | every other `APIRoute` | none — public OpenAlex data; coarse auth is the Go gateway's fail-closed Firebase check (`f057dc5`) |

`get_optional_user` calls `get_verified_user` as a plain function, not a FastAPI
dependency, so an optional route's `dependant` tree shows only `get_optional_user`
— the two classes never overlap.

## File map

| File | Action | Owns afterward |
|---|---|---|
| `services/backend/tests/api/test_auth_posture.py` | Create | classifies `app.routes` by dependency tree; asserts `authed`/`optional` sets == `EXPECTED_*` constants and `public` == the remainder; a self-check that the classifier detects a known authed route |
| `services/backend/scripts/__init__.py` | Create | package marker (only if `scripts/` is not already importable — check in T2) |
| `services/backend/scripts/gen_openapi_snapshot.py` | Create | writes `api-contracts/openapi.snapshot.json` from `app.openapi()`; run once in any env with backend deps, then commit the JSON |
| `services/backend/tests/test_openapi.py` | Modify | `skip` message + docstring point at `scripts/gen_openapi_snapshot.py` instead of "run pytest" |
| `docs/backend-auth-posture.md` | Create | the gateway-in-front model, the authed/optional route table, an "adding a route" checklist |
| `README.md` | Modify | one line under the docs list pointing at `docs/backend-auth-posture.md` |

## Progress
- [x] Task 1 — `test_auth_posture.py` set-level auth guard  (CI-verified: pytest)
- [x] Task 2 — `gen_openapi_snapshot.py` generator + `test_openapi.py` message fix
- [x] Task 3 — `docs/backend-auth-posture.md` + README pointer

## Constitution gate
- [x] I Evidence — each task names its pytest / ruff / py_compile command and expected result
- [x] II Test first — T1 *is* the test; it carries a self-check (classifier must flag a known authed route) so it cannot pass vacuously
- [x] III Smallest change — no `main.py`, no route bodies, no `ci.yml` behaviour; three additive files + two one-line doc edits
- [x] IV Reversibility — no migrations, no credentials, no route-contract change; every file is new or a comment/doc edit
- [x] V No silent degradation — T1 makes "a new route silently dropped auth" a red build; nothing is loosened
- [x] VI Mechanism — `test_auth_posture.py` (T1) and, once the snapshot is committed, `test_openapi.py`'s equality assertion (T2) are the enforcement
- [x] VII Secrets — none; CI already uses fake keys

## Complexity tracking
- All boxes ticked. No exceptions. The one deferred mechanism (openapi equality assertion) is deferred only until the user runs T2's script once and commits the artifact — a marked manual step, not a skipped check.

## Tasks

### Task 1: `test_auth_posture.py` — set-level auth guard
**Purpose:** a new route that silently omits auth fails the build
**Files:**
- Create: `services/backend/tests/api/test_auth_posture.py`
**Dependencies:** none
**Preconditions:** PR #6's `tests/api/conftest.py` is on the base branch (the `client`/`app` fixtures); `from app.main import app` imports in CI.
**Rollback:** delete the file — it is new and additive.
**Implementation notes:**
- Import `from app.main import app`; `from fastapi.routing import APIRoute`; `from app.api.dependencies import get_verified_user, get_optional_user`.
- Helper `_deps(route: APIRoute) -> set[Callable]`: walk `route.dependant` recursively (`d.call` + recurse `d.dependencies`), collect every `.call`.
- Normalise paths with the same `_norm` (`/api/v1` prefix strip) used in `tests/api/test_contract_guard.py` — copy it, do not import (keep the guards independent).
- Classify each GET/POST/PUT/PATCH/DELETE `APIRoute`:
  `get_verified_user in deps` → authed; elif `get_optional_user in deps` → optional; else public.
- `EXPECTED_AUTHED = {"/agent/chat"}`, `EXPECTED_OPTIONAL = {"/chat_with_author", "/discovery/predict", "/discovery/nexus-chat"}` — **T1's first action is to print the live classification and confirm these three constants against it**; correct the constants in the same commit if the grep was stale.
- `test_authed_set_is_locked`: `authed == EXPECTED_AUTHED`.
- `test_optional_set_is_locked`: `optional == EXPECTED_OPTIONAL`.
- `test_public_is_the_remainder`: `public == all_paths - EXPECTED_AUTHED - EXPECTED_OPTIONAL` (so a brand-new untyped-auth route lands in `public` and, if it shouldn't be, the author must move it to a constant).
- `test_classifier_detects_a_known_authed_route`: build a throwaway `APIRouter` with `@r.get("/_probe", dependencies=[Depends(get_verified_user)])`, mount on a throwaway `FastAPI`, assert `_deps` finds `get_verified_user` — proves the walk isn't returning empty sets (the vacuous-pass guard).
- Failure messages name `docs/backend-auth-posture.md`.
**Verification:**
- Run: `cd services/backend && python -m pytest tests/api/test_auth_posture.py -q`
- Expect: exit 0; 4 tests pass. (Dev env cannot run pytest — **CI is the verification of record**, as for every PR #6 test.)
**Done when:** the four assertions pass in CI and the classifier self-check is green.

### Task 2: `gen_openapi_snapshot.py` + `test_openapi.py` message fix
**Purpose:** a documented, one-command way to arm PR #6's OpenAPI drift-guard
**Files:**
- Create: `services/backend/scripts/gen_openapi_snapshot.py` — `from app.main import app`; `import json`, `pathlib`; write `json.dumps(app.openapi(), indent=2, sort_keys=True) + "\n"` to `<repo>/api-contracts/openapi.snapshot.json`; print the path and byte count. `if __name__ == "__main__":` guard.
- Create: `services/backend/scripts/__init__.py` — only if `python -c "import scripts"` from `services/backend` fails today; check first, skip if `scripts/` already has one or isn't needed.
- Modify: `services/backend/tests/test_openapi.py` — in `test_schema_matches_committed_snapshot`, change the `pytest.skip(...)` string and the assertion's help text from "run `pytest tests/test_openapi.py`" to "run `python scripts/gen_openapi_snapshot.py` and commit `api-contracts/openapi.snapshot.json`". No logic change.
**Dependencies:** none
**Preconditions:** PR #6's `tests/test_openapi.py` is on the base branch (it is the file T2 edits).
**Rollback:** delete `scripts/gen_openapi_snapshot.py` (+ `scripts/__init__.py` if added); revert the `test_openapi.py` string change.
**Implementation notes:** the script and the test's bootstrap branch produce byte-identical output (same `json.dumps` args, trailing newline) so running either then committing satisfies the equality assertion. Do **not** add a `ci.yml` step — that would make CI red until the artifact is committed, and this is a stacked PR.
**Verification:**
- Run: `cd services/backend && python -m py_compile scripts/gen_openapi_snapshot.py && python -m ruff check scripts/gen_openapi_snapshot.py tests/test_openapi.py && python -m ruff format --check scripts/gen_openapi_snapshot.py`
- Expect: all exit 0
**Done when:** the script compiles and lints clean, and `test_openapi.py`'s guidance points at it. (Arming the equality assertion needs the user to run the script once in a backend-deps env and commit the JSON — see the Gate 1 marker.)

### Task 3: `docs/backend-auth-posture.md` + README pointer
**Purpose:** the posture is written down, with a checklist for adding a route
**Files:**
- Create: `docs/backend-auth-posture.md` — sections: **Model** (Go gateway fail-closed Firebase auth in front, `f057dc5`; Python backend trusts the gateway for coarse auth, re-verifies only where it needs the uid); **Current posture** (the authed/optional table from this plan, with the one-line reason each is what it is — `/agent/chat` keys chat history to the real uid); **Adding a route** (default public; needs a uid → `Depends(get_verified_user)` + add the path to `EXPECTED_AUTHED` in `test_auth_posture.py`; a new public route needs a one-line why in the PR).
- Modify: `README.md` — add one bullet/row under the existing project-docs list pointing at `docs/backend-auth-posture.md`.
**Dependencies:** none
**Preconditions:** none — pure documentation.
**Rollback:** delete `docs/backend-auth-posture.md`; revert the one README line.
**Implementation notes:** follow `.claude/rules/markdown-style.md` — every section headed, enumerable content as a list/table, field-shaped data as bold-label bullets. Keep it ~40 lines.
**Verification:**
- Run: `python -c "import pathlib,sys; p=pathlib.Path('docs/backend-auth-posture.md'); t=p.read_text(encoding='utf-8'); sys.exit(0 if ('## Model' in t and '## Adding a route' in t and 'get_verified_user' in t) else 1)"` and `grep -n "backend-auth-posture" README.md`
- Expect: python exits 0; grep prints one line
**Done when:** the doc has all three sections and README links it.

## Verification (end to end)

1. `cd services/backend && python -m pytest tests/api/test_auth_posture.py tests/test_openapi.py -q` → exit 0 (CI).
2. `python -m pytest tests/ -q` → full backend suite still green — no regression (this plan adds only new test files + a script).
3. `python -m ruff check tests/api/test_auth_posture.py scripts/ tests/test_openapi.py && python -m ruff format --check tests/api/test_auth_posture.py scripts/` → exit 0.
4. `grep -n "backend-auth-posture" README.md` → one line.
5. CI: `gh run list --branch feat/backend-auth-posture` → `SkoLab CI Pipeline` green.
6. Manual, post-merge or in a backend venv: `python scripts/gen_openapi_snapshot.py` writes `api-contracts/openapi.snapshot.json`; commit it; re-run test 1 and confirm `test_schema_matches_committed_snapshot` no longer skips.

## Known risks / follow-ups

- **`EXPECTED_*` constants drift from the grep.** T1's first action prints the live classification and corrects the constants in the same commit — the grep is a starting point, `app.routes` is the source of truth.
- **`route.dependant` walk misses a dependency style.** Mitigated by `test_classifier_detects_a_known_authed_route` (the vacuous-pass guard) and by `app.dependency_overrides`-style routes being out of scope (there are none).
- **The OpenAPI equality assertion stays skipped** until the user runs T2's script once and commits the artifact — a marked manual step (Gate 1 marker), not a silently skipped check.
- **Stacked on PR #6.** If PR #6 is rewritten, this branch rebases. PR #6 is green and reviewed — low risk.
- SP-2 (observability) and SP-3 (web Firestore) — their own specs, after this.

## Deviations reconciled during execution

- **Root cause found: FastAPI 0.141 lazy router inclusion.** `app.routes` holds `_IncludedRouter` placeholders, not flat `APIRoute`s (resolved on first request), so any test iterating `app.routes` directly saw only `/`, `/health`, `/metrics` — which is why PR #6's `test_contract_guard` passed **vacuously**. Fixed properly: `tests/api/_route_walk.py` recurses the placeholder tree (`original_router.routes` + `include_context.prefix`); `test_auth_posture` and `test_contract_guard` use it and both carry a `test_route_table_is_populated` backstop (`> 20`). No conftest hack. Also restored `tests/api/__init__.py` (needed for the relative import and to avoid `test_integrations` / `test_support` basename collisions with `tests/`).
- **Hardened `tests/api/test_contract_guard.py` too** (PR #6, in scope as a "PR #6 loose end"). It used a module-level `from app.main import app` and, when `test_auth_posture.py` imported `app.api.dependencies` first, the app was captured with only 3 routes (`/`, `/health`, `/metrics`) — making both guards pass vacuously. Both now take the session `app` fixture (resolved after full init) and carry a `test_route_table_is_populated` backstop (`len > 20`).
- **Fixed a path bug in PR #6's `test_openapi.py`** (T2, beyond the planned string-only edit). `_SNAPSHOT` used `Path(__file__).parents[2]` = `services/`, so the drift-guard read/wrote `services/api-contracts/openapi.snapshot.json` — not the real `api-contracts/` at repo root. Changed to `parents[3]` in both `test_openapi.py` and the new `gen_openapi_snapshot.py`. A guard pointing at the wrong path is not "armed", which is T2's whole goal.
- **`scripts/__init__.py` not created** (plan File map row / Task 2). `scripts/gen_openapi_snapshot.py` is run directly (`python scripts/...`), never imported, and `scripts/` had no tracked files — a namespace dir is fine. Skipped per the plan's own "only if needed" condition.
- **Task 1 verification is CI-only.** No runnable backend Python here (Py3.14); `py_compile` + `ruff` pass locally, `pytest tests/api/test_auth_posture.py` runs in CI — same posture as every PR #6 test.

## Resolved at Gate 1

- **openapi.snapshot.json generation** -> committed script `services/backend/scripts/gen_openapi_snapshot.py` (Task 2); the user runs `pip install -r services/backend/requirements-dev.txt && python services/backend/scripts/gen_openapi_snapshot.py` once in a backend env and commits the JSON. `test_openapi.py`'s equality assertion arms itself once the file exists. No CI-commit job.

## Approved

Gate 1 passed 2026-09-02. Marker resolved with the recommended option. Proceed to implementation on `feat/backend-auth-posture` (stacked on PR #6).
