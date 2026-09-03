import os
import sys
from pathlib import Path
from dotenv import load_dotenv

# Load .env first to respect local developer environment db configurations
backend_root = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
load_dotenv(os.path.join(backend_root, ".env"))

# Neutralise a developer's local SENTRY_DSN *before anything imports
# app.core.config* — `Settings` is a frozen dataclass that snapshots
# `SENTRY_DSN` at construction, and `app.db.database` (imported a few lines
# below in the SQLite-fallback branch) constructs it. If a real DSN is left in
# scope, `app.main`'s import-time `init_observability()` activates the global
# Sentry client and `test_observability.py`'s "inert without DSN" assertion —
# green in CI, which has no .env — fails locally. A test that needs Sentry
# active sets the DSN itself.
os.environ["SENTRY_DSN"] = ""

db_url = os.environ.get(
    "DATABASE_URL", "postgresql+asyncpg://postgres:postgres@localhost:5432/skolab"
)


def check_postgres_connection(url: str) -> bool:
    if "postgresql" not in url and "postgres" not in url:
        return False
    import asyncio
    import asyncpg

    async def try_connect():
        try:
            # Swap asyncpg driver name to clean postgres URL format
            clean_url = url.replace("postgresql+asyncpg://", "postgresql://")
            conn = await asyncpg.connect(clean_url, timeout=1.0)
            await conn.close()
            return True
        except Exception:
            return False

    try:
        return asyncio.run(try_connect())
    except Exception:
        return False


# Swapping out DATABASE_URL if Postgres is unavailable
if not check_postgres_connection(db_url):
    temp_db_path = os.path.abspath(os.path.join(backend_root, "test_temp.db"))
    if os.path.exists(temp_db_path):
        try:
            os.remove(temp_db_path)
        except Exception:
            pass
    print(
        f"\n[conftest] PostgreSQL offline. Gracefully falling back to shared SQLite: {temp_db_path}"
    )
    os.environ["DATABASE_URL"] = f"sqlite+aiosqlite:///{temp_db_path}"

    # Initialize SQLite database tables immediately
    os.environ["TESTING"] = "True"
    from app.db.database import init_db
    import asyncio

    try:
        asyncio.run(init_db())
        print("[conftest] SQLite database tables initialized successfully.")
    except Exception as exc:
        print(f"[conftest] SQLite database tables initialization failed: {exc}")

    import pytest

    @pytest.fixture(scope="session", autouse=True)
    def cleanup_temp_db():
        yield
        if os.path.exists(temp_db_path):
            try:
                import gc

                gc.collect()
                os.remove(temp_db_path)
            except Exception:
                pass
else:
    os.environ["DATABASE_URL"] = db_url

os.environ["TESTING"] = "True"
os.environ["GROQ_API"] = "mock_groq_key"
os.environ["GOOGLE_APPLICATION_CREDENTIALS"] = "service-account.json"

# Inject backend root to sys.path
if backend_root not in sys.path:
    sys.path.insert(0, backend_root)
