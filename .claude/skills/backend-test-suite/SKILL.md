---
name: backend-test-suite
description: Run the same lint/SAST/audit/pytest gates locally that .github/workflows/verify.yml and ci.yml run in CI for services/backend (Python). Use before considering any services/backend change done, or whenever asked to "run the tests", "run the test suite", or "check CI will pass" for the backend. Do NOT use for a whole-repo check spanning apps/web and services/backend-go too (`full-repo-test-suite`), or to verify a fix is live in the docker container (`backend-rebuild-verify`).
---

# Backend test suite (local CI mirror)

This runs the Python backend's CI gates locally, straight against the code
in `services/backend/app` — it does **not** touch the `skolab_python_ai`
docker container and doesn't require rebuilding it (that's a separate
concern, see the `backend-rebuild-verify` skill for verifying a fix is live
in the container). This skill is about whether the code itself is
correct/clean, mirroring `.github/workflows/verify.yml` +
the test step of `ci.yml`.

## One-time setup check

Ruff, bandit, and pip-audit are CI-only tools — they are **not** in
`services/backend/requirements.txt`. Check they're importable before
running; install into the active environment if missing:

```bash
cd services/backend
python -m pip show ruff bandit pip-audit >/dev/null 2>&1 || pip install ruff bandit pip-audit
```

Invoke all three via `python -m <tool>` rather than the bare command name —
on this repo's Windows/Git Bash setup the bare `ruff`/`bandit`/`pip-audit`
aren't reliably on `PATH` even when installed, but `python -m ruff` etc.
always resolves to the same interpreter `pip install` used.

`services/backend/tests/conftest.py` already handles the rest automatically:
it falls back to a local SQLite file if Postgres isn't reachable, sets
`TESTING=True`, and mocks `GROQ_API`. The one thing CI creates that may not
exist locally is a dummy `service-account.json` (only needed so
`GOOGLE_APPLICATION_CREDENTIALS` resolves to *some* file) — **do not
overwrite it if a real one already exists** (this repo's real Firebase
service-account file lives at this exact path per AGENTS.md):

```bash
cd services/backend
[ -f service-account.json ] || echo '{"type": "service_account"}' > service-account.json
```

## Steps (mirrors verify.yml + ci.yml, in this order)

Run each from `services/backend`, and don't skip ahead on a failure — report
it and stop unless the user asked to see the full picture regardless:

1. **Lint:** `python -m ruff check app`
2. **Format check:** `python -m ruff format --check app`
3. **SAST:** `python -m bandit -r app -ll`
4. **Dependency audit:** `python -m pip_audit --local`
5. **Tests:** `python -m pytest tests/`

## Reporting

For each step: pass/fail, and on failure the actual tool output (specific
rule violated, specific test name + assertion/traceback) — not just
"lint failed" or "3 tests failed". If `pytest` fails, check whether it's a
real regression from the change just made vs. a pre-existing failure
unrelated to it (run `git stash` + re-run only if genuinely ambiguous and the
user wants that distinction — otherwise just report what failed and why).

## What this does not cover

- The Go gateway (`services/backend-go`) — it has its own test suite now
  (`internal/middleware`, `internal/circuitbreaker`, `main.go`'s reverse
  proxy) but is a separate skill's concern: `full-repo-test-suite` runs it
  (`go build -o main . && go vet ./... && go test ./...`, see AGENTS.md's "Go
  gateway go/no-go check"). Most Go packages (`auth`, `author`, `db`, `quest`,
  `user`, `websocket`) still have no tests.
- Whether a fix is live in the `skolab_python_ai` docker container — that's
  `backend-rebuild-verify`, a different question (container image freshness,
  not code correctness).

## Routing

- Mandatory validator: this skill's own five steps mirror
  `.github/workflows/verify.yml`; a step reported "pass" without its actual
  tool output is not verified.
- Preceded by: the services/backend change itself.
- Terminal handoff: `code-review`, once every step reports a real result.

## Success

Every one of the five gates (ruff check, ruff format, bandit, pip-audit,
pytest) ran to completion, and pass/fail is backed by the actual tool
output — not "should be fine."
