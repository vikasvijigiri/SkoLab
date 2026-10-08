"""The least-privilege skolab_app role covers every table, and no more.

Runs against the migrated disposable database as its owner (CI). Every
table a later migration adds must grant skolab_app DML and add its row
security policy (alembic e1f2a3b4c5d6, APP_POLICY_SQL): a table without them
is invisible to a service running as skolab_app.
"""
import os

import asyncpg
import pytest

ROLE = "skolab_app"
POLICY = "skolab_app_all"


async def _owner_connection():
    url = os.environ["TEST_DATABASE_URL"].replace("postgresql+asyncpg://", "postgresql://")
    try:
        conn = await asyncpg.connect(url, timeout=5, statement_cache_size=0)
    except (OSError, TimeoutError):
        if os.environ.get("CI") == "true":
            raise
        pytest.skip("Disposable PostgreSQL is unavailable locally")
    if not await conn.fetchval("SELECT EXISTS(SELECT 1 FROM pg_roles WHERE rolname = $1)", ROLE):
        await conn.close()
        pytest.fail("skolab_app is missing: run scripts/run_migrations.py on the test database")
    if await conn.fetchval("SELECT pg_has_role(current_user, $1, 'MEMBER')", ROLE) and not await conn.fetchval(
        "SELECT rolsuper FROM pg_roles WHERE rolname = current_user"
    ):
        await conn.close()
        pytest.skip("Connected as the runtime role itself; these checks need the schema owner")
    return conn


@pytest.mark.asyncio
async def test_every_table_is_usable_by_the_runtime_role():
    conn = await _owner_connection()
    try:
        tables = [r["tablename"] for r in await conn.fetch(
            "SELECT tablename FROM pg_tables WHERE schemaname = 'public' AND tablename <> 'alembic_version'"
        )]
        assert "workspaces" in tables
        for table in tables:
            assert await conn.fetchval(
                "SELECT has_table_privilege($1, $2, 'SELECT,INSERT,UPDATE,DELETE')", ROLE, "public." + table
            ), f"{table}: skolab_app lacks DML"
            assert await conn.fetchval(
                "SELECT EXISTS(SELECT 1 FROM pg_policies WHERE schemaname = 'public' AND tablename = $1 AND policyname = $2)",
                table, POLICY,
            ), f"{table}: no {POLICY} row-security policy; the service would see no rows"
            assert not await conn.fetchval(
                "SELECT has_table_privilege($1, $2, 'TRUNCATE')", ROLE, "public." + table
            ), f"{table}: skolab_app may TRUNCATE"
        assert await conn.fetchval("SELECT has_table_privilege($1, 'public.alembic_version', 'SELECT')", ROLE)
        assert not await conn.fetchval(
            "SELECT has_table_privilege($1, 'public.alembic_version', 'INSERT,UPDATE,DELETE')", ROLE
        )
        attrs = await conn.fetchrow(
            "SELECT rolcanlogin, rolsuper, rolcreaterole, rolcreatedb, rolbypassrls FROM pg_roles WHERE rolname = $1", ROLE
        )
        assert not any(attrs.values()), f"skolab_app has extra attributes: {dict(attrs)}"
    finally:
        await conn.close()


@pytest.mark.asyncio
async def test_runtime_role_reads_and_writes_rows_but_cannot_change_schema():
    conn = await _owner_connection()
    try:
        tx = conn.transaction()
        await tx.start()
        try:
            await conn.execute(
                "INSERT INTO users (id, display_name) VALUES ('runtime-role-probe', 'Probe') ON CONFLICT (id) DO NOTHING"
            )
            await conn.execute(f"SET LOCAL ROLE {ROLE}")
            # Row security does not hide rows from the runtime role.
            assert await conn.fetchval("SELECT count(*) FROM users WHERE id = 'runtime-role-probe'") == 1
            await conn.execute("UPDATE users SET display_name = 'Probed' WHERE id = 'runtime-role-probe'")
            for ddl in (
                "TRUNCATE users",
                "ALTER TABLE users ADD COLUMN probe int",
                "DROP TABLE websocket_tickets",
                "CREATE TABLE public.__runtime_probe (id int)",
                "UPDATE alembic_version SET version_num = version_num",
            ):
                await conn.execute("SAVEPOINT probe")
                with pytest.raises(asyncpg.InsufficientPrivilegeError):
                    await conn.execute(ddl)
                await conn.execute("ROLLBACK TO SAVEPOINT probe")
        finally:
            await tx.rollback()
    finally:
        await conn.close()
