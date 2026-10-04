"""Exercise deny-by-default SQL permissions on the disposable CI database."""
import importlib.util
import os
from pathlib import Path

import asyncpg
import pytest


@pytest.mark.asyncio
async def test_public_roles_cannot_read_backend_tables_or_future_tables():
    url = os.environ["TEST_DATABASE_URL"].replace("postgresql+asyncpg://", "postgresql://")
    try:
        conn = await asyncpg.connect(url, timeout=5, statement_cache_size=0)
    except (OSError, TimeoutError):
        if os.environ.get("CI") == "true":
            raise
        pytest.skip("Disposable PostgreSQL is unavailable locally")
    try:
        async with conn.transaction():
            # Changes roll back, including roles and the deliberately unsafe
            # grants used to prove the mitigation repairs existing exposure.
            for role in ("anon", "authenticated"):
                if not await conn.fetchval("SELECT EXISTS(SELECT 1 FROM pg_roles WHERE rolname=$1)", role):
                    await conn.execute(f"CREATE ROLE {role} NOLOGIN")
                await conn.execute(f"GRANT SELECT, INSERT, UPDATE, DELETE ON public.users TO {role}")
            path = Path(__file__).resolve().parents[1] / "alembic/versions/c9d0e1f2a3b4_restrict_public_database_access.py"
            spec = importlib.util.spec_from_file_location("security_migration", path)
            module = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(module)
            for statement in module.statements():
                await conn.execute(statement)
            await conn.execute("CREATE TABLE public.__skolab_acl_test (id int)")
            for role in ("anon", "authenticated"):
                for table in ("users", "workspaces", "websocket_tickets", "__skolab_acl_test"):
                    assert not await conn.fetchval(
                        "SELECT has_table_privilege($1, $2, 'SELECT,INSERT,UPDATE,DELETE,TRUNCATE')",
                        role, "public." + table,
                    )
                with pytest.raises(asyncpg.InsufficientPrivilegeError):
                    async with conn.transaction():
                        await conn.execute(f"SET LOCAL ROLE {role}")
                        await conn.execute("SELECT id FROM public.users WHERE false")
            await conn.execute("SELECT id FROM public.users WHERE false")
            assert await conn.fetchval("SELECT relrowsecurity FROM pg_class WHERE oid='public.users'::regclass")
            # Roll back all test state explicitly without masking assertions.
            raise _RollbackTest()
    except _RollbackTest:
        pass
    finally:
        await conn.close()


class _RollbackTest(Exception):
    pass
