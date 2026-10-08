"""Add skolab_app, a least-privilege group role for the running service.

Until now the service connected as the table owner: any SQL bug could
TRUNCATE, ALTER or DROP. This creates a NOLOGIN group role with only what
the running service needs -- DML on application tables, sequence use, read
access to alembic_version -- plus a row-security policy for it alone (RLS is
enabled without policies, which would otherwise hide every row from a
non-owner). Data API roles (anon, authenticated) stay denied.

Nothing changes until an operator opts in, once per environment:

    CREATE ROLE skolab_api LOGIN PASSWORD '...' IN ROLE skolab_app;

then sets DATABASE_URL to skolab_api and MIGRATION_DATABASE_URL to the
owner (deploy/AUDIT-2026-10-08.md, roadmap item 4). Tables created by later
migrations inherit the grants through default privileges but must add the
policy themselves (APP_POLICY_SQL); tests/test_runtime_role.py fails if one
is missed.
"""
from alembic import op

revision = "e1f2a3b4c5d6"
down_revision = "d0e1f2a3b4c5"
branch_labels = None
depends_on = None

ROLE = "skolab_app"
POLICY = "skolab_app_all"

# Applies the role's access to one table, named by the format() argument %I.
APP_POLICY_SQL = f"""
      EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE public.%I TO {ROLE}', t);
      EXECUTE format('DROP POLICY IF EXISTS {POLICY} ON public.%I', t);
      EXECUTE format('CREATE POLICY {POLICY} ON public.%I FOR ALL TO {ROLE} USING (true) WITH CHECK (true)', t);
"""


def statements():
    return [f"""
DO $runtime$
DECLARE t text;
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '{ROLE}') THEN
    CREATE ROLE {ROLE} NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
  END IF;
  EXECUTE format('GRANT CONNECT ON DATABASE %I TO {ROLE}', current_database());
  GRANT USAGE ON SCHEMA public TO {ROLE};

  FOR t IN SELECT tablename FROM pg_tables
           WHERE schemaname = 'public' AND tablename <> 'alembic_version' LOOP
    {APP_POLICY_SQL}
  END LOOP;
  IF to_regclass('public.alembic_version') IS NOT NULL THEN
    -- The schema guard reads the revision; only migrations may write it.
    GRANT SELECT ON TABLE public.alembic_version TO {ROLE};
    DROP POLICY IF EXISTS {POLICY} ON public.alembic_version;
    CREATE POLICY {POLICY} ON public.alembic_version FOR SELECT TO {ROLE} USING (true);
  END IF;
  GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO {ROLE};

  -- Future tables and sequences created by the migrating role.
  ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO {ROLE};
  ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO {ROLE};
END $runtime$;
"""]


def upgrade():
    for statement in statements():
        op.execute(statement)


def downgrade():
    op.execute(f"""
DO $runtime$
DECLARE t text;
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '{ROLE}') THEN
    RETURN;
  END IF;
  FOR t IN SELECT tablename FROM pg_policies WHERE schemaname = 'public' AND policyname = '{POLICY}' LOOP
    EXECUTE format('DROP POLICY {POLICY} ON public.%I', t);
  END LOOP;
  ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE ALL ON TABLES FROM {ROLE};
  ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE ALL ON SEQUENCES FROM {ROLE};
  REVOKE ALL ON ALL TABLES IN SCHEMA public FROM {ROLE};
  REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM {ROLE};
  REVOKE USAGE ON SCHEMA public FROM {ROLE};
  EXECUTE format('REVOKE CONNECT ON DATABASE %I FROM {ROLE}', current_database());
  -- Login roles granted membership (skolab_api) lose it with the group.
  DROP ROLE {ROLE};
END $runtime$;
""")
