# Tough Error Log (`error.md`)

Append-only record of complex or tough runtime, test, or build errors encountered, diagnosed, and resolved in this repository.

---

## 2026-07-29 17:32 — Async Pytest Suite Failures (`async def` unsupported)

- **Phase/Context:** Backend test suite execution (`services/backend/tests/`).
- **Symptom:** 27 test failures reporting `async def functions are not natively supported. You need to install a suitable plugin for your async framework (e.g., pytest-asyncio)`.
- **Diagnosis:** `pytest-asyncio` dependency was missing from the local environment site-packages, causing pytest to fail on async test coroutines.
- **Attempts:**
  - 1. Executed `python -m pytest tests/` → 27 failures due to missing async test runner.
  - 2. Installed `pytest-asyncio` (`pip install pytest-asyncio`) → plugin registered, 72 async tests passed cleanly.
- **Fix:** Installed `pytest-asyncio` package in local Python environment; added setup guard to test skills.
- **Status:** `Resolved`

---
