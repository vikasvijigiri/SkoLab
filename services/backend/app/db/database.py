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
from app.core.config import settings

# asyncpg keeps a per-connection prepared-statement cache. Behind the Supabase
# transaction pooler (pgBouncer in `transaction` mode) a connection is handed to
# a different client between statements, so a cached prepared statement resolves
# against the wrong session and errors ("prepared statement ... does not exist").
# Disabling the cache is the documented requirement for pgBouncer txn pooling.
_is_asyncpg = "asyncpg" in DATABASE_URL
_pg_connect_args: dict = {"command_timeout": 30.0}
if _is_asyncpg:
    _pg_connect_args["statement_cache_size"] = 0

if os.environ.get("TESTING") == "True":
    connect_args = {} if DATABASE_URL.startswith("sqlite") else _pg_connect_args
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
        # Sized for the Supabase free transaction pooler; see Settings. The
        # effective server-connection count is workers * (pool_size + overflow).
        pool_size=settings.db_pool_size,
        max_overflow=settings.db_max_overflow,
        pool_timeout=settings.db_pool_timeout_seconds,  # fail fast, don't queue forever
        pool_pre_ping=True,  # validate connections before use (handles server restarts)
        pool_recycle=1800,  # recycle connections every 30 min to avoid stale TCP issues
        connect_args=_pg_connect_args,
    )

import time
import logging
from sqlalchemy import event
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
        # Auto-EXPLAIN plain read queries only. `EXPLAIN CREATE TABLE ...` /
        # `EXPLAIN CREATE INDEX ...` / `EXPLAIN ALTER ...` are syntax errors in
        # PostgreSQL, and a failed statement issued from inside this handler
        # aborts the caller's transaction ("current transaction is aborted") —
        # which is exactly what broke schema bootstrap against a real Postgres
        # in CI, since first-run DDL on a cold container can exceed 100 ms.
        head = statement.lstrip()[:6].upper()
        if head.startswith(("SELECT", "WITH")) and not executemany:
            try:
                # `parameters` here is in the DBAPI driver's own native form
                # (e.g. asyncpg's positional $1/$2 style) exactly as captured
                # off the wire — not the named-bind dict/list-of-dicts shape
                # SQLAlchemy's `text()` + `Connection.execute()` expect. That
                # mismatch is what "Failed to execute EXPLAIN automatically:
                # List argument must consist only of dictionaries" actually
                # was: a client-side parameter-shape validation error, raised
                # before ever reaching Postgres — every single "slow query"
                # log line carried a broken EXPLAIN attempt instead of a
                # real plan. `cursor` is the raw DBAPI cursor already used to
                # run the original statement, so re-running through it keeps
                # the exact same parameter convention that already worked.
                cursor.execute(f"EXPLAIN {statement}", parameters)
                plan = "\n".join(row[0] for row in cursor.fetchall())
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
    """Yields a database session for use in FastAPI route dependencies.

    Rolls back on a propagating error so the underlying connection is never
    returned to the pool inside an aborted transaction — the next checkout of
    that connection would otherwise fail every query with SQLAlchemy's
    "Could not locate column in row" (an aborted-txn result has no row
    description). See the 2026-09 Sentry cluster on /industry_opportunities.
    """
    async with AsyncSessionLocal() as session:
        try:
            yield session
        except Exception:
            await session.rollback()
            raise


async def execute_with_row_retry(db: AsyncSession, stmt):
    """Execute `stmt`, retrying once (after a rollback) on NoSuchColumnError.

    Leading theory for the SKOLAB-BACKEND-7/-8/-9 Sentry cluster (2026-09):
    cross-referencing SKOLAB-BACKEND-9's last occurrence (2026-09-11
    17:59:44.996Z) against Supabase's `supavisor_logs` showed the app's
    Postgres backend connection was authenticated (`DbHandler: Backend
    authenticated`, 17:59:44.964834) in the same second as the failing
    query — i.e. it was the first query issued on a just-established
    connection, which lines up with Render's free-tier container waking
    from an idle sleep (the event's `server_name` tag carries a
    "-hibernate-" segment, Render's free-tier sleep/wake marker) and
    immediately serving a real request through Supabase's Supavisor
    pooler (transaction mode) before that connection has fully settled --
    the query's SQL is fine, but the row Postgres/asyncpg hands back for
    it doesn't match the shape SQLAlchemy compiled. Sentry only has the
    failures, not confirmation a same-request retry clears it, but a
    rollback + one re-execute is the standard, low-risk mitigation for
    this class of pooler cold-connection race and costs nothing when the
    query would have succeeded anyway. Not a general-purpose retry helper
    -- scoped to the first ORM SELECT of a request that can land right
    after a cold start.
    """
    from sqlalchemy.exc import NoSuchColumnError

    try:
        return await db.execute(stmt)
    except NoSuchColumnError:
        await db.rollback()
        return await db.execute(stmt)


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

    if not settings.run_schema_create_all:
        print(
            "[init_db] Skipping runtime create_all/ALTER "
            "(run_schema_create_all=False). Schema is owned by Alembic — "
            "ensure `alembic upgrade head` ran as a release step.",
            flush=True,
        )
        return

    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
        from sqlalchemy import text

        for col_name, col_type in [
            ("username", "VARCHAR(100) UNIQUE"),
            ("author_name", "VARCHAR(255)"),
            ("phone", "VARCHAR(50)"),
            ("research_focus", "TEXT"),
        ]:
            try:
                await conn.execute(
                    text(
                        f"ALTER TABLE users ADD COLUMN IF NOT EXISTS {col_name} {col_type};"
                    )
                )
            except Exception as e:
                print(
                    f"[init_db] Note: could not alter table for column {col_name}: {e}",
                    flush=True,
                )

        for col_name, col_type in [
            ("skills", "JSON"),
            ("tools", "JSON"),
        ]:
            try:
                await conn.execute(
                    text(
                        f"ALTER TABLE researcher_metrics ADD COLUMN IF NOT EXISTS {col_name} {col_type};"
                    )
                )
            except Exception as e:
                print(
                    f"[init_db] Note: could not alter table for column {col_name}: {e}",
                    flush=True,
                )

        # ── pgvector similarity tables (Postgres only) ───────────────────────
        # SQLite (the offline-dev / fast-tier fallback) has no `vector` type,
        # so this whole block is skipped there and the similarity engine
        # simply has no store to read — its endpoints degrade to the OpenAlex
        # fallback path. Mirrors alembic revision b2c3d4e5f6a7 for the
        # Postgres deployments where `run_schema_create_all` is False.
        if conn.dialect.name == "postgresql":
            for ddl in (
                "CREATE EXTENSION IF NOT EXISTS vector",
                """
                CREATE TABLE IF NOT EXISTS work_embeddings (
                    work_id           text PRIMARY KEY,
                    embedding         vector(384) NOT NULL,
                    title             text,
                    concepts          text[],
                    referenced_works  text[],
                    publication_year  integer,
                    updated_at        timestamptz NOT NULL DEFAULT now()
                )
                """,
                """
                CREATE TABLE IF NOT EXISTS author_embeddings (
                    author_id     text PRIMARY KEY,
                    embedding     vector(384) NOT NULL,
                    concepts      text[],
                    coauthor_ids  text[],
                    institution   text,
                    works_count   integer,
                    h_index       integer,
                    updated_at    timestamptz NOT NULL DEFAULT now()
                )
                """,
                "CREATE INDEX IF NOT EXISTS ix_work_embeddings_updated_at "
                "ON work_embeddings (updated_at)",
                "CREATE INDEX IF NOT EXISTS ix_author_embeddings_updated_at "
                "ON author_embeddings (updated_at)",
            ):
                try:
                    await conn.execute(text(ddl))
                except Exception as e:
                    print(
                        f"[init_db] Note: pgvector similarity DDL skipped: {e}",
                        flush=True,
                    )


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
