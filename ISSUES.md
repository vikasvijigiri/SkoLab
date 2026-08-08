# Issues

<!-- Append-only, newest entry at the TOP, never rewrite old ones -- same discipline
as LOG.md. One entry per incident (the whole diagnose/fix sequence), written by the
error-recovery skill once a bounded recovery loop reaches a terminal state.
Format: ## YYYY-MM-DD HH:MM -- <short symptom title>, fields per knowledge-manager's
ISSUES.md spec. Not preloaded at SessionStart -- consulted on demand. -->

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
