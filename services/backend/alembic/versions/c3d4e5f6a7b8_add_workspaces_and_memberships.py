"""add collaborative workspaces and explicit memberships

Revision ID: c3d4e5f6a7b8
Revises: b2c3d4e5f6a7
Create Date: 2026-09-26

The WebSocket gateway authorizes a connection against these records before
upgrading it. Owners are authorized directly; collaborators require an active
membership row. There is deliberately no permissive fallback when the schema or
database is unavailable.
"""

from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


revision: str = "c3d4e5f6a7b8"
down_revision: Union[str, Sequence[str], None] = "b2c3d4e5f6a7"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def _has_table(name: str) -> bool:
    return sa.inspect(op.get_bind()).has_table(name)


def upgrade() -> None:
    if not _has_table("workspaces"):
        op.create_table(
            "workspaces",
            sa.Column("id", sa.String(length=100), primary_key=True),
            sa.Column(
                "owner_id",
                sa.String(length=100),
                sa.ForeignKey("users.id", ondelete="CASCADE"),
                nullable=False,
            ),
            sa.Column("title", sa.String(length=255), nullable=False),
            sa.Column("created_at", sa.DateTime(), nullable=False),
            sa.CheckConstraint(
                "length(trim(title)) > 0", name="chk_workspace_title_nonempty"
            ),
        )
        op.create_index("ix_workspaces_owner_id", "workspaces", ["owner_id"])

    if not _has_table("workspace_members"):
        op.create_table(
            "workspace_members",
            sa.Column("id", sa.Integer(), primary_key=True, autoincrement=True),
            sa.Column(
                "workspace_id",
                sa.String(length=100),
                sa.ForeignKey("workspaces.id", ondelete="CASCADE"),
                nullable=False,
            ),
            sa.Column(
                "user_id",
                sa.String(length=100),
                sa.ForeignKey("users.id", ondelete="CASCADE"),
                nullable=False,
            ),
            sa.Column(
                "role", sa.String(length=20), nullable=False, server_default="viewer"
            ),
            sa.Column(
                "status", sa.String(length=20), nullable=False, server_default="active"
            ),
            sa.Column("created_at", sa.DateTime(), nullable=False),
            sa.UniqueConstraint("workspace_id", "user_id", name="uq_workspace_member"),
            sa.CheckConstraint(
                "role IN ('owner', 'editor', 'commenter', 'viewer')",
                name="chk_workspace_member_role",
            ),
            sa.CheckConstraint(
                "status IN ('active', 'invited', 'removed')",
                name="chk_workspace_member_status",
            ),
        )
        op.create_index(
            "ix_workspace_members_workspace_id", "workspace_members", ["workspace_id"]
        )
        op.create_index(
            "ix_workspace_members_user_id", "workspace_members", ["user_id"]
        )


def downgrade() -> None:
    if _has_table("workspace_members"):
        op.drop_table("workspace_members")
    if _has_table("workspaces"):
        op.drop_table("workspaces")
