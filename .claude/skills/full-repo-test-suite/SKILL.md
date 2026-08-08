---
name: full-repo-test-suite
description: Run linting, type checks, and test suites across all SkoLab components (Next.js web app TypeScript check, Go gateway build/vet/tests, and Python backend ruff/pytest). Use whenever asked to "run all tests", "test everything", or perform a full codebase verification. Do NOT use for a single-component check — use `backend-test-suite` for the fuller Python gate (SAST + audit included) or run the Go/web commands directly when only one side changed.
---

# Full Repository Test Suite

This skill runs local verification gates across all 3 major components in SkoLab: `apps/web` (Next.js), `services/backend-go` (Go Gateway), and `services/backend` (Python AI backend).

## 1. Web Frontend (`apps/web`)

Runs the TypeScript typecheck gate:

```bash
cd apps/web
npx tsc --noEmit
```

*Must pass cleanly with 0 type errors.*

## 2. Go Gateway (`services/backend-go`)

Runs Go build, vet static analysis, and unit test suite:

```bash
cd services/backend-go
go build -o main . && go vet ./... && go test ./...
```

*Covers middleware (CORS, rate limiter), circuit breaker, and reverse proxy.*

## 3. Python AI Backend (`services/backend`)

Runs Python linting and test suite:

```bash
cd services/backend
python -m ruff check app
python -m pytest tests/
```

## Reporting

Report pass/fail status per component. For any component with failures, report the exact error output or traceback.

## Routing

- Mandatory validator: the three commands above; a component reported "pass"
  without its actual command output is not verified.
- Terminal handoff: `code-review`, once every component reports a real
  result.

## Success

All three components ran (not skipped), and pass/fail per component is
backed by real output — 0 TypeScript errors, Go build/vet/test clean, ruff
and pytest clean.
