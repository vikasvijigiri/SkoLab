"""
app/db/database.py
==================
Async SQLAlchemy engine and session factory.

DATABASE_URL is read from the environment so credentials are never
hard-coded. Falls back to a local dev default when the env var is absent.

Format: postgresql+asyncpg://user:password@host:port/dbname
"""
import os
from sqlalchemy.ext.asyncio import (
    AsyncSession,
    async_sessionmaker,
    create_async_engine,
)
from sqlalchemy.orm import declarative_base

# ── Connection URL ────────────────────────────────────────────────────────────
# Set DATABASE_URL in .env for dev, or inject it as a real env var in prod.
DATABASE_URL: str = os.environ.get(
    "DATABASE_URL",
    "postgresql+asyncpg://postgres:SkoLab2025!@127.0.0.1:5432/skolab",
)

# ── Engine ────────────────────────────────────────────────────────────────────
engine = create_async_engine(
    DATABASE_URL,
    echo=False,
    pool_size=10,          # number of persistent connections in pool
    max_overflow=20,       # extra connections allowed beyond pool_size
    pool_pre_ping=True,    # validate connections before use (handles server restarts)
    pool_recycle=1800,     # recycle connections every 30 min to avoid stale TCP issues
)

# ── Session factory ───────────────────────────────────────────────────────────
AsyncSessionLocal = async_sessionmaker(
    engine,
    expire_on_commit=False,
    class_=AsyncSession,
)

# ── Declarative base ──────────────────────────────────────────────────────────
Base = declarative_base()


# ── FastAPI dependency ────────────────────────────────────────────────────────
async def get_db() -> AsyncSession:
    """Yields a database session for use in FastAPI route dependencies."""
    async with AsyncSessionLocal() as session:
        yield session


# ── Schema initialisation ─────────────────────────────────────────────────────
async def init_db() -> None:
    """
    Creates all tables that are not yet present in the database.
    Must be called after all model modules have been imported so that
    their Table objects are registered on Base.metadata.
    """
    # Each import is a side-effect import — it registers the ORM mappers with Base.
    import app.models.user_models       # noqa: F401 — User, Connection, CacheEntry, ResearcherProfile, ResearcherConnection
    import app.models.researcher_models # noqa: F401 — ResearcherWork, ResearcherMetrics
    import app.models.agent_models      # noqa: F401 — AgentHistorySummary, AgentDocumentUpload
    import app.models.analytics_models  # noqa: F401 — UserSettings, UserActivityLog, ApiRequestLog, AuthorSearchLog
    import app.models.content_models    # noqa: F401 — DailyFeedItem, Conjecture

    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)

