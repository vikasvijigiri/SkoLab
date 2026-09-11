# HANDOFF

> Current-state snapshot, not history. Overwritten in place at the end of
> every session. For history, see `LOG.md`; for why a decision was made, see
> `decisions/`.

**Last updated:** 2026-09-11

## Where the repository is

`main` is at `65dcec8` (PR #176 merged — Firestore rules + Discovery→CoLab
match bridge + Google sign-in loading UX). PR #177 closes out everything
that was still pending after #176, plus three pre-existing `checks.yml` bugs
found while watching its CI (all three actually root-caused and fixed, not
worked around). Full detail in `LOG.md`'s 2026-09-11 entries.

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
- **The third `checks.yml` failure was root-caused, not left as a
  follow-up.** `tests/test_industry_academic.py::
  test_get_tieups_cache_miss_success` was first suspected to be an
  order-dependent flake (passed standalone and in a full local Windows
  run, failed on Linux CI); asked to dig deeper rather than accept that,
  and found the real cause: neither of `checks.yml`'s Python test steps
  ever set `GROQ_API`/`OPENROUTER_API_KEY` (unlike `ci.yml`, which does),
  so `is_llm_working()` (`app/core/config.py`) returns `False`, and
  `IndustryAcademicService.get_tieups` silently takes its non-LLM fallback
  path — `get_fallback_tieups()` — instead of the one the test actually
  mocks. That fallback literally returns a title of
  `f"Industrialization of {domain}"`, which is exactly the unexplained
  string the test kept failing on. Reproduced the exact CI failure locally
  (moved the repo's own `services/backend/.env` — which has a real
  `GROQ_API` key, explaining why it always passed here — aside and
  unset both vars) and confirmed the fix removes it: full local suite
  re-run with `.env` absent and the fix's placeholder values set, 216
  passed / 4 skipped / 0 failed, same count as before, so nothing else
  regressed now that `is_llm_working()` is reliably `True` in CI.
- **The `ThemeToggle` fix and the LLM-grounding fix were originally lost to
  an accidental `git reset --hard`** mid-session (moving a stray commit off
  `main` onto a feature branch, done carelessly) and had to be rewritten
  from scratch using this conversation's own prior tool output — nothing
  was actually lost, but it's a reminder to commit before any branch
  surgery, not after.
