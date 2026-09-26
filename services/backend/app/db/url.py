"""Database URL normalization shared by the async application and Alembic."""


def as_asyncpg_url(database_url: str) -> str:
    """Select SQLAlchemy's asyncpg dialect for ordinary PostgreSQL URLs.

    Supabase connection strings and the Go gateway conventionally use
    ``postgresql://``. The Python service is async, so SQLAlchemy must instead
    receive ``postgresql+asyncpg://``; otherwise it attempts the optional
    synchronous psycopg2 driver.
    """

    if database_url.startswith("postgresql://"):
        return "postgresql+asyncpg://" + database_url.removeprefix("postgresql://")
    if database_url.startswith("postgres://"):
        return "postgresql+asyncpg://" + database_url.removeprefix("postgres://")
    return database_url
