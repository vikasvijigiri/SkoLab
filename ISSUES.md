# Issues

<!-- Append-only, newest entry at the TOP, never rewrite old ones -- same discipline
as LOG.md. One entry per incident (the whole diagnose/fix sequence), written by the
error-recovery skill once a bounded recovery loop reaches a terminal state.
Format: ## YYYY-MM-DD HH:MM -- <short symptom title>, fields per knowledge-manager's
ISSUES.md spec. Not preloaded at SessionStart -- consulted on demand. -->

## 2026-08-08 23:10 — test_quests_service leaderboard tests fail with "No leaderboard data available"

- **Phase/Context**: Found while verifying the similar-authors fix; investigated as an
  independent failure once confirmed pre-existing.
- **Symptom**: `test_get_leaderboard_firestore` and `test_get_leaderboard_postgres_fallback`
  both fail with `ValueError: No leaderboard data available for field '<X>' from Firestore
  or local database.` raised at `quest/service.py:379`.
- **Diagnosis**: The `ValueError` is the *last* line of `get_leaderboard`, reached only
  after both the Firestore and PostgreSQL branches fail — and each branch swallows its
  real error into a `print`, so the message names neither cause. Re-running under
  `pytest -s` surfaced them: both branches fail on
  `1 validation error for LeaderboardEntry`.
  Single shared root cause: **`LeaderboardEntry.id` is a required `str`
  (`quest/schemas.py:13`) and neither test fixture supplies one.**
  - Firestore test: the mock docs define `to_dict()` but never set `.id`, so
    `d.get("openalex_id") or doc.id` yields a `MagicMock`, not a `str`.
  - Postgres test: the `ResearcherMetrics` rows are built without `openalex_id`, so
    `id=r.openalex_id` is `None`.
  **The production code is correct** — `openalex_id` is the primary key on
  `ResearcherMetrics` (`researcher_models.py:99`) so it is never null for a real row,
  and a real Firestore document always has a str `.id`. Both fixtures constructed rows
  that cannot occur in production. Confirmed pre-existing and unrelated to the
  uncommitted feature diff: `git diff HEAD` is empty for both
  `tests/test_quests_service.py` and `app/domains/quest/schemas.py`, and the quest
  service diff touches only quest initialisation, not `get_leaderboard`.
- **Attempts**:
  - 1. Suspected the uncommitted `quest/service.py` diff → **wrong**; that diff changes
    only the quest-init `focus` logic. Proven by `git diff` on the file.
  - 2. Suspected my own `derive_similar_authors_from_works` hardening → **wrong**;
    stashed it and the two tests failed identically.
  - 3. Read the `ValueError` at face value as "no data" → misleading; it is a terminal
    fallthrough, not the actual error. Re-ran with `-s` to recover the swallowed
    validation errors, which named the real cause immediately.
- **Fix**: Test-side only, no production change. Set a real str `.id` on both mock
  Firestore docs (and an `openalex_id` in one document body, to cover both branches of
  the `d.get("openalex_id") or doc.id` fallback), and pass `openalex_id` when
  constructing both `ResearcherMetrics` rows. Added `id` assertions to all three cases,
  which the fixtures had been silently skipping; mutation-tested one to confirm it
  fails when wrong.
- **Status**: `Resolved`

## 2026-08-08 22:45 — derive_similar_authors_from_works raises on malformed authorship shapes

- **Phase/Context**: Code review of the uncommitted working tree (author-profile /
  similar-researchers work). Reported as a *blocking* review finding, then
  re-investigated here.
- **Symptom**: None observed in production. The claim under investigation was that
  `insts[0].get("display_name")` at `openalex_service.py:130` could raise
  `AttributeError`, propagate through the unguarded `_find_similar_researchers` call
  at `pipeline_services.py:1233`, and be converted to a 500 for the whole
  `/daily_feed` request by `feed.py:33`'s outer `except Exception`.
- **Diagnosis**: The **mechanism is real but the original severity was wrong.**
  Two failing shapes were reproduced directly (`institutions: ["MIT"]` → `AttributeError:
  'str' object has no attribute 'get'`; `authorships: [None]` → `AttributeError: 'NoneType'
  object has no attribute 'get'`). But no *reachable* trigger was established:
  - The arXiv path, which was the prime suspect (its authorships are built as
    `{"author": {"display_name": ...}}` at `pipeline_services.py:890-896`, carrying no
    `id` and no `institutions`), is **safe** — those entries are skipped at the
    `if not aid` guard. Proven by test, not by reading.
  - The only other sources are OpenAlex `search_works` / `fetch_related_works`, whose
    schema guarantees `authorships[]` and `institutions[]` are objects.
  Real structural finding: `pipeline_services.py:1233` is the **only** one of three call
  sites that is both unwrapped by try/except *and* fed a heterogeneous pool (arXiv +
  OpenAlex + related-works). The other two (`authors.py:151`, `feed.py:385`) pass pure
  `search_works()` output and sit inside try blocks. The helper also had **zero test
  coverage** (`grep` over `tests/` returned nothing).
- **Attempts**:
  - 1. Hypothesised arXiv-shaped authorships were the live trigger → **wrong**;
    reproduced them and got `[]`, not an exception. Recorded as a passing regression
    test so the next person does not re-run this.
  - 2. Reproduced the two genuinely-raising shapes in isolation → confirmed the
    mechanism, but as a robustness gap, not a live production bug.
  - 3. Hardened the helper at the source and re-ran → all 10 tests pass.
- **Fix**: `isinstance` shape checks in `derive_similar_authors_from_works`
  (`openalex_service.py:114-144`) for the work, the authorship, the author, and the
  first institution — a malformed entry now skips that entry and continues instead of
  aborting the derivation. No call-site try/except was added: the helper is now total
  with respect to shape, so wrapping it would be redundant. New offline suite
  `tests/test_similar_authors.py` (10 cases) covers the happy path, exclusion, dedup,
  the limit, the arXiv shape, and five malformed shapes.
- **Status**: `Resolved`

## 2026-08-08 22:45 — test_predict_discovery fails on stale mock signature

- **Phase/Context**: Found incidentally while verifying the fix above did not break
  anything else. Independent root cause, pre-existing in the working tree.
- **Symptom**: `tests/test_discovery.py::test_predict_discovery` fails:
  `AssertionError: expected call not found. Expected: predict_next_big_thing(field=...,
  focus_area=None) Actual: predict_next_big_thing(field=..., focus_area=None, author_id=None)`
- **Diagnosis**: Not caused by the hardening fix — the uncommitted diff added a new
  `author_id` field to `PredictRequest` and threaded it into
  `prediction_service.predict_next_big_thing` (`discovery_engine.py:19,36-38`), but the
  test's `assert_called_once_with` still asserted the old two-argument signature. The
  production signature change is intentional; the test was simply not updated with it.
- **Attempts**:
  - 1. Checked whether the hardening fix caused it → no; the fix touches only
    `derive_similar_authors_from_works`, which this endpoint never calls.
  - 2. Updated the assertion to the intended signature → passes.
- **Fix**: Added `author_id=None` to the expected call in `tests/test_discovery.py:49-51`,
  with a comment noting why the argument is present and why it is `None` for this payload.
- **Status**: `Resolved`

---

## 2026-08-18 — test_threat_modeling errors on Python 3.14 (local only, CI green)

- **Phase/Context**: Found while running the full backend suite before merging
  `fix/similar-authors-shape-hardening` to `main`.
- **Symptom**: `pytest services/backend/tests/` → `85 passed, 6 errors`. All six
  are the same teardown error in `tests/test_threat_modeling.py`:
  `RuntimeError: There is no current event loop in thread 'MainThread'`
  (`C:\Python314\Lib\asyncio\events.py:715`).
- **Diagnosis**: `tests/test_threat_modeling.py:34` calls
  `asyncio.get_event_loop().run_until_complete(engine.dispose())`. Python 3.14
  removed the implicit loop creation `get_event_loop()` used to do outside a
  running loop, so it raises instead of returning a fresh loop. CI pins
  `python-version: "3.10"` (`.github/workflows/ci.yml:39`), where the old
  behaviour still holds — which is why CI is green and only the local
  Python 3.14 run errors. Not caused by this branch: `git diff main...HEAD --
  services/backend/tests/test_threat_modeling.py` is empty.
- **Fix**: Not applied — pre-existing, environment-version-specific, and outside
  the merge's scope. The repair is `asyncio.run(engine.dispose())`, or an
  explicit `new_event_loop()`/`close()` pair, in that teardown.
- **Status**: `Open` — will become a real CI failure the moment the pinned
  Python moves past 3.12.

---

## 2026-08-18 — three CI jobs red on `main` since 2026-07-15

- **Phase/Context**: Surfaced by PR #2. Not caused by it — all three fail
  identically on `main` at `4e917c8` and on every run back to `26d37e2`
  (2026-07-15). Merged anyway, with the owner's explicit go-ahead, because the
  branch strictly improves the situation rather than worsening it.
- **Symptom / Diagnosis**, one per job:
  1. `SkoLab CI Pipeline / build-and-test` — exit 127,
     `pytest: command not found`. `.github/workflows/ci.yml:66` runs
     `pytest tests/` inside a venv built only from
     `services/backend/requirements.txt`, which does not list `pytest`
     (`grep -c '^pytest' → 0`). The suite has therefore never actually run in
     this job. Fix: install `pytest` (plus whatever the suite imports —
     `pytest-asyncio`) in that step, or add it to a dev-requirements file.
  2. `CI Verification / Android Build & Lint Verification` —
     `:app:lintAnalyzeDevDebug` and `:app:lintAnalyzeDevDebugUnitTest` fail with
     `OutOfMemoryError` inside `AndroidLintWorkAction`, thrown while
     `ComposableStateFlowValueDetector` and `ThreadDetector` load classes. A
     runner-heap problem, not a lint finding. Fix: raise
     `org.gradle.jvmargs` / the lint worker heap in `gradle.properties`.
  3. `CI Verification / Python Linting & Test Gating` —
     `ruff format --check services/backend/app` (`verify.yml:37`) reports
     unformatted files. **This is progress**: on `main` the same job dies one
     step earlier, at `ruff check` with `Found 3 errors` (`verify.yml:33`).
     `c7aa5f9` cleared those, so the job now advances to the formatter. Fix:
     run `ruff format services/backend/app` — a large formatting-only diff,
     deliberately not bundled into PR #2.
- **Status**: `Open` — all three. The `checks` workflow this branch adds is
  green (`lint · typecheck · test`, `build · audit · e2e · smoke`, `conclusion`).
