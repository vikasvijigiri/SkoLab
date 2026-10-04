"""Deny Data API roles access to backend-owned application tables.

This backend authenticates Firebase users in the gateway and connects as the
table owner (or a dedicated BYPASSRLS service role), not as Supabase end users.
There are intentionally no client RLS policies. Do not FORCE RLS on owners.
"""
from alembic import op

revision = "c9d0e1f2a3b4"
down_revision = "b8c9d0e1f2a3"
branch_labels = None
depends_on = None

TABLES = (
    "agent_chat_history", "agent_document_uploads", "agent_history_summaries",
    "alembic_version", "api_request_log", "author_embeddings", "author_search_log",
    "cache_entries", "conjectures", "connections", "daily_feed_items", "messages",
    "researcher_connections", "researcher_metrics", "researcher_profiles",
    "researcher_works", "scraped_opportunities", "security_audit_log",
    "usage_counters", "user_activity_log", "user_circles", "user_preferences",
    "user_settings", "users", "websocket_tickets", "work_embeddings",
    "workspace_invites", "workspace_members", "workspaces",
)


def statements():
    names = ",".join("'" + name + "'" for name in TABLES)
    return [f"""
DO $security$
DECLARE t text; r text;
BEGIN
  FOREACH t IN ARRAY ARRAY[{names}] LOOP
    IF to_regclass(format('public.%I', t)) IS NOT NULL THEN
      EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY', t);
      EXECUTE format('REVOKE ALL ON TABLE public.%I FROM PUBLIC', t);
      FOREACH r IN ARRAY ARRAY['anon','authenticated'] LOOP
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = r) THEN
          EXECUTE format('REVOKE ALL ON TABLE public.%I FROM %I', t, r);
        END IF;
      END LOOP;
    END IF;
  END LOOP;
  ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE ALL ON TABLES FROM PUBLIC;
  FOREACH r IN ARRAY ARRAY['anon','authenticated'] LOOP
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = r) THEN
      EXECUTE format('ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE ALL ON TABLES FROM %I', r);
    END IF;
  END LOOP;
END $security$;
"""]


def upgrade():
    for statement in statements():
        op.execute(statement)


def downgrade():
    # An application rollback must not silently reopen public database access.
    pass
