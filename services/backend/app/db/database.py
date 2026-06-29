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
# Set DATABASE_URL in .env (see .env.example).  Never hard-code credentials here.
_raw_db_url = os.environ.get("DATABASE_URL", "")
if not _raw_db_url:
    raise RuntimeError(
        "DATABASE_URL is not set. "
        "Copy backend/.env.example to backend/.env and fill in your credentials."
    )
DATABASE_URL: str = _raw_db_url


from sqlalchemy.pool import NullPool

if os.environ.get("TESTING") == "True":
    connect_args = (
        {} if DATABASE_URL.startswith("sqlite") else {"command_timeout": 30.0}
    )
    engine = create_async_engine(
        DATABASE_URL,
        echo=False,
        poolclass=NullPool,
        connect_args=connect_args,
    )
else:
    engine = create_async_engine(
        DATABASE_URL,
        echo=False,
        pool_size=10,  # number of persistent connections in pool
        max_overflow=20,  # extra connections allowed beyond pool_size
        pool_pre_ping=True,  # validate connections before use (handles server restarts)
        pool_recycle=1800,  # recycle connections every 30 min to avoid stale TCP issues
        connect_args={"command_timeout": 30.0},
    )

import time
import logging
from sqlalchemy import event, text
from app.core.telemetry import tracer

db_logger = logging.getLogger("skolab.db")


@event.listens_for(engine.sync_engine, "before_cursor_execute")
def before_cursor_execute(conn, cursor, statement, parameters, context, executemany):
    context._query_start_time = time.perf_counter()
    # Execute within a trace span
    span_name = (
        f"DB {statement.strip().split()[0].upper()}"
        if statement.strip()
        else "DB Query"
    )
    context._db_span = tracer.start_as_current_span(span_name)
    context._db_span.__enter__()


@event.listens_for(engine.sync_engine, "after_cursor_execute")
def after_cursor_execute(conn, cursor, statement, parameters, context, executemany):
    # Exit db span
    db_span = getattr(context, "_db_span", None)
    if db_span:
        db_span.__exit__(None, None, None)
        setattr(context, "_db_span", None)

    total_time = time.perf_counter() - context._query_start_time
    total_time_ms = total_time * 1000.0
    if total_time_ms > 100.0:  # Alert SRE when query duration exceeds 100ms limits
        db_logger.warning(
            f"Slow SQL Query detected ({total_time_ms:.2f}ms): {statement}",
            extra={
                "latency_ms": int(total_time_ms),
                "statement": statement,
                "query_slow": True,
            },
        )
        # Execute EXPLAIN automatically (avoid infinite recursion by checking statement)
        if not statement.strip().upper().startswith("EXPLAIN"):
            try:
                explain_query = f"EXPLAIN {statement}"
                res = conn.execute(text(explain_query), parameters)
                plan = "\n".join(row[0] for row in res.fetchall())
                db_logger.info(f"EXPLAIN Plan for slow query:\n{plan}")
            except Exception as explain_exc:
                db_logger.warning(
                    f"Failed to execute EXPLAIN automatically: {explain_exc}"
                )


@event.listens_for(engine.sync_engine, "handle_error")
def handle_error(exception_context):
    context = getattr(exception_context, "execution_context", None)
    if context:
        db_span = getattr(context, "_db_span", None)
        if db_span:
            exc = getattr(exception_context, "original_exception", None)
            db_span.__exit__(type(exc) if exc else Exception, exc, None)
            setattr(context, "_db_span", None)


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
    import app.models.user_models  # noqa: F401 — User, Connection, CacheEntry, ResearcherProfile, ResearcherConnection
    import app.models.researcher_models  # noqa: F401 — ResearcherWork, ResearcherMetrics
    import app.models.agent_models  # noqa: F401 — AgentHistorySummary, AgentDocumentUpload
    import app.models.analytics_models  # noqa: F401 — UserSettings, UserActivityLog, ApiRequestLog, AuthorSearchLog
    import app.models.content_models  # noqa: F401 — DailyFeedItem, Conjecture

    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)


import hmac
import hashlib
import json


def generate_record_signature(user_id: str, record_data: list | dict) -> str:
    """
    Generate an HMAC-SHA256 signature for a database record using the database encryption key.
    """
    from app.core.config import settings

    key = str(settings.database_encryption_key).encode("utf-8")
    serialized = json.dumps(record_data, sort_keys=True)
    payload = f"{user_id}:{serialized}"
    return hmac.new(key, payload.encode("utf-8"), hashlib.sha256).hexdigest()


def verify_record_signature(
    user_id: str, record_data: list | dict, signature: str
) -> bool:
    """
    Verify if the provided signature matches the computed HMAC-SHA256 signature.
    """
    expected = generate_record_signature(user_id, record_data)
    return hmac.compare_digest(signature, expected)
