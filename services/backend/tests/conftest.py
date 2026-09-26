import os
import sys
from pathlib import Path
from dotenv import load_dotenv

# Load .env first to respect local developer environment db configurations
backend_root = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
load_dotenv(os.path.join(backend_root, ".env"))

# Neutralise a developer's local SENTRY_DSN *before anything imports
# app.core.config* — `Settings` is a frozen dataclass that snapshots
# `SENTRY_DSN` at construction, and `app.db.database` (imported below)
# constructs it. If a real DSN is left in scope, `app.main`'s import-time
# `init_observability()` activates the global Sentry client and
# `test_observability.py`'s "inert without DSN" assertion — green in CI,
# which has no .env — fails locally. A test that needs Sentry active sets
# the DSN itself.
os.environ["SENTRY_DSN"] = ""

# Internal gateway credentials are production-only state. A developer's local
# token must not alter tests whose contract intentionally covers the tokenless
# development path; tests that exercise authenticated internal calls set an
# explicit value with monkeypatch instead.
os.environ["INTERNAL_API_TOKEN"] = ""

# Postgres only — no SQLite fallback (2026-09-26, "Supabase only" pass).
# Locally this is whatever DATABASE_URL points at in services/backend/.env
# (Supabase); in CI it's the ephemeral Postgres container ci.yml/checks.yml
# spin up per run. Either way it must be reachable, or every DB-backed test
# fails loudly here instead of silently running against a different engine.
db_url = os.environ.get("DATABASE_URL", "")
if not db_url:
    raise RuntimeError(
        "DATABASE_URL is not set. Copy .env.example (repo root) to "
        "services/backend/.env and point it at your Postgres database."
    )
os.environ["DATABASE_URL"] = db_url

os.environ["TESTING"] = "True"
os.environ["GROQ_API"] = "mock_groq_key"
os.environ["GOOGLE_APPLICATION_CREDENTIALS"] = "service-account.json"

# Inject backend root to sys.path
if backend_root not in sys.path:
    sys.path.insert(0, backend_root)
