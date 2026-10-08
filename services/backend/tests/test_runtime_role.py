"""The least-privilege skolab_app role covers every table, and no more.

Runs against the disposable database as its owner. The migration's own
statements are applied inside a transaction that is rolled back (role
creation is transactional), so the checks hold whether the schema came from
Alembic (ci.yml) or from ci_bootstrap_db.py (checks.yml), and leave no trace.
Every table a later migration adds must grant skolab_app DML and add its row
security policy (alembic e1f2a3b4c5d6, APP_POLICY_SQL): a table without them
is invisible to a service running as skolab_app.
"""
import importlib.util
import os
from pathlib import Path

import asyncpg
import pytest

ROLE = "skolab_app"
POLICY = "skolab_app_all"


_MIGRATION = Path(__file__).resolve().parents[1] / "alembic/versions/e1f2a3b4c5d6_add_runtime_app_role.py"


def _migration_statements():
    spec = importlib.util.spec_from_file_location("runtime_role_migration", _MIGRATION)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.statements()


async def _owner_connection():
    url = os.environ["TEST_DATABASE_URL"].replace("postgresql+asyncpg://", "postgresql://")
    try:
        conn = await asyncpg.connect(url, timeout=5, statement_cache_size=0)
    except (OSError, TimeoutError):
        if os.environ.get("CI") == "true":
            raise
        pytest.skip("Disposable PostgreSQL is unavailable locally")
    if not await conn.fetchval(
        "SELECT rolsuper OR rolcreaterole FROM pg_roles WHERE rolname = current_user"
    ):
        await conn.close()
        pytest.skip("Connected without role-management rights (e.g. as the runtime role itself)")
    return conn


async def _with_migration(conn):
    """Start a transaction (rolled back by the caller) in which skolab_app exists.

    A migrated database already has it, and is checked as it stands -- that is
    what catches a later table that forgot its grant or policy. A schema built
    by ci_bootstrap_db.py never ran Alembic, so the migration is applied here.
    """
    tx = conn.transaction()
    await tx.start()
    if not await conn.fetchval("SELECT EXISTS(SELECT 1 FROM pg_roles WHERE rolname = $1)", ROLE):
        for statement in _migration_statements():
            await conn.execute(statement)
    return tx


@pytest.mark.asyncio
async def test_every_table_is_usable_by_the_runtime_role():
    conn = await _owner_connection()
    tx = await _with_migration(conn)
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
        if await conn.fetchval("SELECT to_regclass('public.alembic_version') IS NOT NULL"):
            assert await conn.fetchval("SELECT has_table_privilege($1, 'public.alembic_version', 'SELECT')", ROLE)
            assert not await conn.fetchval(
                "SELECT has_table_privilege($1, 'public.alembic_version', 'INSERT,UPDATE,DELETE')", ROLE
            )
        attrs = await conn.fetchrow(
            "SELECT rolcanlogin, rolsuper, rolcreaterole, rolcreatedb, rolbypassrls FROM pg_roles WHERE rolname = $1", ROLE
        )
        assert not any(attrs.values()), f"skolab_app has extra attributes: {dict(attrs)}"
    finally:
        await tx.rollback()
        await conn.close()


@pytest.mark.asyncio
async def test_runtime_role_reads_and_writes_rows_but_cannot_change_schema():
    conn = await _owner_connection()
    try:
        tx = await _with_migration(conn)
        try:
            await conn.execute(
                "INSERT INTO users (id, display_name) VALUES ('runtime-role-probe', 'Probe') ON CONFLICT (id) DO NOTHING"
            )
            forbidden = [
                "TRUNCATE users",
                "ALTER TABLE users ADD COLUMN probe int",
                "DROP TABLE websocket_tickets",
                "CREATE TABLE public.__runtime_probe (id int)",
            ]
            if await conn.fetchval("SELECT to_regclass('public.alembic_version') IS NOT NULL"):
                forbidden.append("UPDATE alembic_version SET version_num = version_num")
            await conn.execute(f"SET LOCAL ROLE {ROLE}")
            # Row security does not hide rows from the runtime role.
            assert await conn.fetchval("SELECT count(*) FROM users WHERE id = 'runtime-role-probe'") == 1
            await conn.execute("UPDATE users SET display_name = 'Probed' WHERE id = 'runtime-role-probe'")
            for ddl in forbidden:
                await conn.execute("SAVEPOINT probe")
                with pytest.raises(asyncpg.InsufficientPrivilegeError):
                    await conn.execute(ddl)
                await conn.execute("ROLLBACK TO SAVEPOINT probe")
        finally:
            await tx.rollback()
    finally:
        await conn.close()
