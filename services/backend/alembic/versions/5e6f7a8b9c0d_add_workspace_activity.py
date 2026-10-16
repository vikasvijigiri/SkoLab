"""add workspace_activity and users.activity_seen_at: what co-authors did

Revision ID: 5e6f7a8b9c0d
Revises: 4d5e6f7a8b9c
Create Date: 2026-10-16

Backs the activity feed behind the web app's notifications (Go gateway,
internal/activity): a co-author saved the document, someone joined or left,
a member was removed or had their role changed, ownership moved. Each event
is written in the same transaction as the change it describes. Saves by one
person to one document within ten minutes share a row, counted in ``count``.
``subject_id`` is the person an event is about (the member removed, the new
owner), so they are told even after losing access. ``users.activity_seen_at``
holds where the reader last caught up, the same on every device.

Events go with their workspace. Actor and subject are kept as SET NULL so a
deleted account leaves "someone" behind rather than taking the history. Like
every table, row security is on, nothing for PUBLIC, anon or authenticated,
and the skolab_app grant and policy apply.
"""

from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


revision: str = "5e6f7a8b9c0d"
down_revision: Union[str, Sequence[str], None] = "4d5e6f7a8b9c"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None

TABLE = "workspace_activity"
ROLE = "skolab_app"
POLICY = "skolab_app_all"
KINDS = (
    "document_edited",
    "member_joined",
    "member_left",
    "member_removed",
    "role_changed",
    "ownership_transferred",
)


def _user_fk(name: str) -> sa.Column:
    return sa.Column(name, sa.String(length=100), sa.ForeignKey("users.id", ondelete="SET NULL"), nullable=True)


def upgrade() -> None:
    inspector = sa.inspect(op.get_bind())
    if not inspector.has_table(TABLE):
        op.create_table(
            TABLE,
            sa.Column("id", sa.BigInteger(), primary_key=True, autoincrement=True),
            sa.Column(
                "workspace_id",
                sa.String(length=100),
                sa.ForeignKey("workspaces.id", ondelete="CASCADE"),
                nullable=False,
            ),
            sa.Column("kind", sa.String(length=32), nullable=False),
            _user_fk("actor_id"),
            _user_fk("subject_id"),
            sa.Column("role", sa.String(length=20), nullable=True),
            sa.Column("count", sa.Integer(), nullable=False, server_default="1"),
            sa.Column(
                "occurred_at",
                sa.DateTime(),
                nullable=False,
                server_default=sa.text("(now() AT TIME ZONE 'utc')"),
            ),
            sa.CheckConstraint(
                "kind IN (" + ", ".join(f"'{kind}'" for kind in KINDS) + ")",
                name="chk_workspace_activity_kind",
            ),
            sa.CheckConstraint("count >= 1", name="chk_workspace_activity_count"),
            sa.CheckConstraint(
                "role IS NULL OR role IN ('owner', 'editor', 'commenter', 'viewer')",
                name="chk_workspace_activity_role",
            ),
        )
        # The feed reads a workspace's newest events; the subject index finds
        # events about someone who may no longer belong to the workspace.
        op.create_index("ix_workspace_activity_workspace", TABLE, ["workspace_id", "occurred_at"])
        op.create_index(
            "ix_workspace_activity_subject",
            TABLE,
            ["subject_id", "occurred_at"],
            postgresql_where=sa.text("subject_id IS NOT NULL"),
        )
    if not any(c["name"] == "activity_seen_at" for c in inspector.get_columns("users")):
        op.add_column("users", sa.Column("activity_seen_at", sa.DateTime(), nullable=True))
    op.execute(f"""
DO $activity$
DECLARE r text;
BEGIN
  ALTER TABLE public.{TABLE} ENABLE ROW LEVEL SECURITY;
  REVOKE ALL ON TABLE public.{TABLE} FROM PUBLIC;
  FOREACH r IN ARRAY ARRAY['anon','authenticated'] LOOP
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = r) THEN
      EXECUTE format('REVOKE ALL ON TABLE public.{TABLE} FROM %I', r);
      EXECUTE format('REVOKE ALL ON SEQUENCE public.{TABLE}_id_seq FROM %I', r);
    END IF;
  END LOOP;
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '{ROLE}') THEN
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE public.{TABLE} TO {ROLE};
    GRANT USAGE, SELECT ON SEQUENCE public.{TABLE}_id_seq TO {ROLE};
    DROP POLICY IF EXISTS {POLICY} ON public.{TABLE};
    CREATE POLICY {POLICY} ON public.{TABLE} FOR ALL TO {ROLE} USING (true) WITH CHECK (true);
  END IF;
END $activity$;
""")


def downgrade() -> None:
    op.execute(f"DROP TABLE IF EXISTS public.{TABLE}")
    if any(c["name"] == "activity_seen_at" for c in sa.inspect(op.get_bind()).get_columns("users")):
        op.drop_column("users", "activity_seen_at")
