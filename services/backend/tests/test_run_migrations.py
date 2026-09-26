"""Regression coverage for PostgreSQL URL normalization."""

from app.db.url import as_asyncpg_url


def test_standard_postgres_urls_select_asyncpg_for_alembic():
    assert (
        as_asyncpg_url("postgresql://user:password@db.example:6543/postgres")
        == "postgresql+asyncpg://user:password@db.example:6543/postgres"
    )
    assert (
        as_asyncpg_url("postgres://user:password@db.example/postgres")
        == "postgresql+asyncpg://user:password@db.example/postgres"
    )


def test_asyncpg_url_is_unchanged():
    url = "postgresql+asyncpg://user:password@db.example/postgres"
    assert as_asyncpg_url(url) == url
