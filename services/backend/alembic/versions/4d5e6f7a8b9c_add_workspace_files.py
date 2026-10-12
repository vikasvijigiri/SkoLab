"""add workspace_files and workspace_outputs: a project's files and its last PDF

Revision ID: 4d5e6f7a8b9c
Revises: 3c4d5e6f7a8b
Create Date: 2026-10-09

Backs the editor's project files (Go gateway, internal/files): folders,
extra .tex and .bib files, uploaded images, and the PDF the last compile
produced. Both are removed with their workspace. Paths are unique per
workspace ignoring case. Like every table, row security is on, nothing for
PUBLIC, anon or authenticated, and the skolab_app grant and policy apply. A
new environment already has the tables from the models, so creation is
skipped there but the access rules still apply.
"""

from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


revision: str = "4d5e6f7a8b9c"
down_revision: Union[str, Sequence[str], None] = "3c4d5e6f7a8b"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None

TABLES = ("workspace_files", "workspace_outputs")
ROLE = "skolab_app"
POLICY = "skolab_app_all"


def _user_fk(name: str) -> sa.Column:
    return sa.Column(name, sa.String(length=100), sa.ForeignKey("users.id", ondelete="SET NULL"), nullable=True)


def _now() -> sa.TextClause:
    return sa.text("(now() AT TIME ZONE 'utc')")


def upgrade() -> None:
    inspector = sa.inspect(op.get_bind())
    if not inspector.has_table("workspace_files"):
        op.create_table(
            "workspace_files",
            sa.Column("id", sa.String(length=36), primary_key=True),
            sa.Column(
                "workspace_id",
                sa.String(length=100),
                sa.ForeignKey("workspaces.id", ondelete="CASCADE"),
                nullable=False,
            ),
            sa.Column("path", sa.String(length=200), nullable=False),
            sa.Column("kind", sa.String(length=10), nullable=False),
            sa.Column("content", sa.Text(), nullable=True),
            sa.Column("data", sa.LargeBinary(), nullable=True),
            sa.Column("size", sa.Integer(), nullable=False, server_default="0"),
            sa.Column("content_type", sa.String(length=100), nullable=False, server_default=""),
            sa.Column("version", sa.Integer(), nullable=False, server_default="1"),
            sa.Column("created_at", sa.DateTime(), nullable=False, server_default=_now()),
            sa.Column("updated_at", sa.DateTime(), nullable=False, server_default=_now()),
            _user_fk("updated_by"),
            sa.CheckConstraint("kind IN ('folder', 'text', 'binary')", name="chk_workspace_file_kind"),
            sa.CheckConstraint("size >= 0 AND size <= 5242880", name="chk_workspace_file_size"),
            sa.CheckConstraint("version >= 1", name="chk_workspace_file_version"),
        )
        op.create_index(
            "uq_workspace_files_path",
            "workspace_files",
            ["workspace_id", sa.text("lower(path)")],
            unique=True,
        )
    if not inspector.has_table("workspace_outputs"):
        op.create_table(
            "workspace_outputs",
            sa.Column(
                "workspace_id",
                sa.String(length=100),
                sa.ForeignKey("workspaces.id", ondelete="CASCADE"),
                primary_key=True,
            ),
            sa.Column("pdf", sa.LargeBinary(), nullable=False),
            sa.Column("size", sa.Integer(), nullable=False),
            sa.Column("compiled_at", sa.DateTime(), nullable=False, server_default=_now()),
            _user_fk("compiled_by"),
            sa.CheckConstraint("size >= 0 AND size <= 8388608", name="chk_workspace_output_size"),
        )
    for table in TABLES:
        op.execute(f"""
DO $files$
DECLARE r text;
BEGIN
  ALTER TABLE public.{table} ENABLE ROW LEVEL SECURITY;
  REVOKE ALL ON TABLE public.{table} FROM PUBLIC;
  FOREACH r IN ARRAY ARRAY['anon','authenticated'] LOOP
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = r) THEN
      EXECUTE format('REVOKE ALL ON TABLE public.{table} FROM %I', r);
    END IF;
  END LOOP;
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '{ROLE}') THEN
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE public.{table} TO {ROLE};
    DROP POLICY IF EXISTS {POLICY} ON public.{table};
    CREATE POLICY {POLICY} ON public.{table} FOR ALL TO {ROLE} USING (true) WITH CHECK (true);
  END IF;
END $files$;
""")


def downgrade() -> None:
    for table in reversed(TABLES):
        op.execute(f"DROP TABLE IF EXISTS public.{table}")
