"""add originality_reports: the AI originality checks run on a workspace

Revision ID: 6f7a8b9c0d1e
Revises: 5e6f7a8b9c0d
Create Date: 2026-10-17

Backs the originality check (Go gateway, internal/ai): a request is queued,
run in the background against OpenAlex, and finished with a score, the
matched sentences and their sources (``result``, JSON). Reports go with
their workspace; the requester is kept as SET NULL. The gateway keeps the
newest twenty per workspace. Row security on, nothing for PUBLIC, anon or
authenticated, the skolab_app grant and policy, like every table.
"""

from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql


revision: str = "6f7a8b9c0d1e"
down_revision: Union[str, Sequence[str], None] = "5e6f7a8b9c0d"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None

TABLE = "originality_reports"
ROLE = "skolab_app"
POLICY = "skolab_app_all"


def upgrade() -> None:
    if not sa.inspect(op.get_bind()).has_table(TABLE):
        op.create_table(
            TABLE,
            sa.Column("id", sa.String(length=36), primary_key=True),
            sa.Column(
                "workspace_id",
                sa.String(length=100),
                sa.ForeignKey("workspaces.id", ondelete="CASCADE"),
                nullable=False,
            ),
            sa.Column(
                "requested_by",
                sa.String(length=100),
                sa.ForeignKey("users.id", ondelete="SET NULL"),
                nullable=True,
            ),
            sa.Column(
                "status", sa.String(length=16), nullable=False, server_default="queued"
            ),
            sa.Column(
                "document_version", sa.Integer(), nullable=False, server_default="0"
            ),
            sa.Column("score", sa.Float(), nullable=True),
            sa.Column("result", postgresql.JSONB(), nullable=True),
            sa.Column("error", sa.String(length=200), nullable=True),
            sa.Column(
                "created_at",
                sa.DateTime(),
                nullable=False,
                server_default=sa.text("(now() AT TIME ZONE 'utc')"),
            ),
            sa.Column("started_at", sa.DateTime(), nullable=True),
            sa.Column("finished_at", sa.DateTime(), nullable=True),
            sa.CheckConstraint(
                "status IN ('queued', 'running', 'done', 'failed')",
                name="chk_originality_report_status",
            ),
            sa.CheckConstraint(
                "score IS NULL OR (score >= 0 AND score <= 100)",
                name="chk_originality_report_score",
            ),
        )
        op.create_index(
            "ix_originality_reports_workspace", TABLE, ["workspace_id", "created_at"]
        )
    op.execute(f"""
DO $originality$
DECLARE r text;
BEGIN
  ALTER TABLE public.{TABLE} ENABLE ROW LEVEL SECURITY;
  REVOKE ALL ON TABLE public.{TABLE} FROM PUBLIC;
  FOREACH r IN ARRAY ARRAY['anon','authenticated'] LOOP
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = r) THEN
      EXECUTE format('REVOKE ALL ON TABLE public.{TABLE} FROM %I', r);
    END IF;
  END LOOP;
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '{ROLE}') THEN
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE public.{TABLE} TO {ROLE};
    DROP POLICY IF EXISTS {POLICY} ON public.{TABLE};
    CREATE POLICY {POLICY} ON public.{TABLE} FOR ALL TO {ROLE} USING (true) WITH CHECK (true);
  END IF;
END $originality$;
""")


def downgrade() -> None:
    op.execute(f"DROP TABLE IF EXISTS public.{TABLE}")
