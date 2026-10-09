"""add workspace_documents: the LaTeX source behind each workspace

Revision ID: 3c4d5e6f7a8b
Revises: e1f2a3b4c5d6
Create Date: 2026-10-09

Backs the editor's cloud documents (Go gateway, internal/document):
GET/PUT /api/v1/workspaces/{id}/document. One row per workspace, removed
with it. ``version`` is the optimistic-concurrency counter a save must
match. Like every table, it has row security on, nothing for PUBLIC, anon
or authenticated (c9d0e1f2a3b4), and the skolab_app grant and policy
(e1f2a3b4c5d6). A new environment already has the table from the models,
so creation is skipped there but the access rules still apply.
"""

from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


revision: str = "3c4d5e6f7a8b"
down_revision: Union[str, Sequence[str], None] = "e1f2a3b4c5d6"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None

TABLE = "workspace_documents"
ROLE = "skolab_app"
POLICY = "skolab_app_all"


def upgrade() -> None:
    if not sa.inspect(op.get_bind()).has_table(TABLE):
        op.create_table(
            TABLE,
            sa.Column(
                "workspace_id",
                sa.String(length=100),
                sa.ForeignKey("workspaces.id", ondelete="CASCADE"),
                primary_key=True,
            ),
            sa.Column("source", sa.Text(), nullable=False),
            sa.Column("template_id", sa.String(length=64), nullable=True),
            sa.Column("version", sa.Integer(), nullable=False, server_default="1"),
            sa.Column(
                "updated_at",
                sa.DateTime(),
                nullable=False,
                server_default=sa.text("(now() AT TIME ZONE 'utc')"),
            ),
            sa.Column(
                "updated_by",
                sa.String(length=100),
                sa.ForeignKey("users.id", ondelete="SET NULL"),
                nullable=True,
            ),
            sa.CheckConstraint("char_length(source) <= 100000", name="chk_workspace_document_size"),
            sa.CheckConstraint("version >= 1", name="chk_workspace_document_version"),
        )
    op.execute(f"""
DO $documents$
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
END $documents$;
""")


def downgrade() -> None:
    op.execute(f"DROP TABLE IF EXISTS public.{TABLE}")
