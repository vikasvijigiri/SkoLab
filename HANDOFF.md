# HANDOFF

> Current-state snapshot, not history. Overwritten in place at the end of
> every session. For history, see `LOG.md`; for why a decision was made, see
> `decisions/`.

**Last updated:** 2026-09-11

## Where the repository is

`main` is at `65dcec8` (PR #176 merged — Firestore rules + Discovery→CoLab
match bridge + Google sign-in loading UX). PR #177 closes out everything
that was still pending after #176, plus two pre-existing `checks.yml` bugs
found while watching its CI. Full detail in `LOG.md`'s 2026-09-11 entries.

- **`checks.yml` fixed for real** — `pytest.ini` needed `pythonpath = .`
  (`tests/` has no `__init__.py`, so bare `pytest`'s rootdir sys.path
  insertion never added `services/backend` itself — reproduced locally,
  confirmed fixed: 214 tests collect, 216 pass). The `slow` job's Postgres
  service was plain `postgres:16` (no pgvector extension), which poisoned
  the whole `ci_bootstrap_db.py` transaction and silently created zero
  tables while still exiting 0 — switched to `pgvector/pgvector:pg16`.
- **`ThemeToggle` hydration mismatch fixed** — same root cause and fix
  pattern as the Google-redirect flag in #176 (`useState(localStorage-read)`
  in a lazy initializer mismatches SSR; fixed with `useSyncExternalStore`).
  Live-verified in the browser: 0 console errors across theme values that
  previously triggered it.
- **LLM output validation added for Horizon + Gap Finder** — the static
  audit from #176 found both grounded in real data but with no structural
  validation of what the LLM returned. Horizon's JSON now goes through a
  stricter internal Pydantic model; Gap Finder's markdown now checks for
  the three sections its prompt demands. 6 new tests, mocked LLM (no API
  key needed).
- **Both remaining "couldn't verify in this environment" items are now
  actually verified**, not just written: installed a portable JDK 21 and
  Go 1.25 (zip extraction, no admin rights — `choco` failed on permissions,
  `winget` hung on install, both non-viable in this environment) and ran
  `npm run test:rules` (20/20 against the real Firestore emulator — also
  fixed a real bug in the test's own `seedProject` helper) and
  `go vet && go build && go test ./...` for the Go gateway (all clean, all
  pass) for the first time this session.

## What needs a decision / attention

- **`firestore.rules` is still not deployed** — the one thing that
  genuinely cannot be done from here. Needs `npx firebase login` with the
  owner's own Google account, then `npx firebase deploy --only
  firestore:rules` (or paste the file into Firebase Console → Firestore
  Database → Rules directly). Production is still running on whatever
  rules existed before PR #176 until this happens.
- **A third pre-existing, unrelated flaky test, found while verifying
  PR #177's own CI**: `tests/test_industry_academic.py::
  test_get_tieups_cache_miss_success` fails on `checks.yml`'s Linux runner
  but passes standalone and in a full local Windows run. It asserts on an
  LLM-mocked title and gets back a *different*, real-sounding title
  ("Industrialization of Quantum Computing") that doesn't appear anywhere
  in its own mocks or the codebase — points at either the module-level
  `LLM_LIMIT_EXCEEDED` global in `llm_service.py` or a cache/singleton in
  `industry_academic_service.py` leaking state across tests in a way this
  test's `@patch` decorators don't fully cover, likely order-dependent
  (Linux vs. Windows test-collection order). Not touched this session —
  `industry_academic_service.py` and its tests are unrelated to both #176
  and #177's actual content, and this is a second, deeper investigation
  into the same class of "was always broken, only started running once
  the `pythonpath` fix let checks.yml's tests execute at all" issue.
  Confirmed with the owner: merge PR #177 anyway rather than block on it;
  this stays open as its own follow-up.
- **The `ThemeToggle` fix and the LLM-grounding fix were originally lost to
  an accidental `git reset --hard`** mid-session (moving a stray commit off
  `main` onto a feature branch, done carelessly) and had to be rewritten
  from scratch using this conversation's own prior tool output — nothing
  was actually lost, but it's a reminder to commit before any branch
  surgery, not after.
