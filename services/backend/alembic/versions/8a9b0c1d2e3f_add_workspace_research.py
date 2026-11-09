"""add workspace research evidence and reproducibility records

Revision ID: 8a9b0c1d2e3f
Revises: 7a8b9c0d1e2f, f6a7b8c9d0e1
Create Date: 2026-11-09
"""

from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql


revision: str = "8a9b0c1d2e3f"
down_revision: Union[str, Sequence[str], None] = ("7a8b9c0d1e2f", "f6a7b8c9d0e1")
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    if not sa.inspect(op.get_bind()).has_table("workspace_research"):
        op.create_table(
            "workspace_research",
            sa.Column("workspace_id", sa.String(100), sa.ForeignKey("workspaces.id", ondelete="CASCADE"), primary_key=True),
            sa.Column("items", postgresql.JSONB(astext_type=sa.Text()), nullable=False, server_default=sa.text("'[]'::jsonb")),
            sa.Column("reproducibility", postgresql.JSONB(astext_type=sa.Text()), nullable=False, server_default=sa.text("'{}'::jsonb")),
            sa.Column("version", sa.Integer(), nullable=False, server_default="1"),
            sa.Column("updated_at", sa.DateTime(), nullable=False, server_default=sa.text("(now() AT TIME ZONE 'utc')")),
            sa.Column("updated_by", sa.String(100), sa.ForeignKey("users.id", ondelete="SET NULL"), nullable=True),
            sa.CheckConstraint("version >= 1", name="chk_workspace_research_version"),
        )
    op.execute("""
DO $research$
DECLARE r text;
BEGIN
  ALTER TABLE public.workspace_research ENABLE ROW LEVEL SECURITY;
  REVOKE ALL ON TABLE public.workspace_research FROM PUBLIC;
  FOREACH r IN ARRAY ARRAY['anon','authenticated'] LOOP
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = r) THEN
      EXECUTE format('REVOKE ALL ON TABLE public.workspace_research FROM %I', r);
    END IF;
  END LOOP;
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'skolab_app') THEN
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE public.workspace_research TO skolab_app;
    DROP POLICY IF EXISTS skolab_app_all ON public.workspace_research;
    CREATE POLICY skolab_app_all ON public.workspace_research FOR ALL TO skolab_app USING (true) WITH CHECK (true);
  END IF;
END $research$;
""")


def downgrade() -> None:
    op.execute("DROP TABLE IF EXISTS public.workspace_research")
