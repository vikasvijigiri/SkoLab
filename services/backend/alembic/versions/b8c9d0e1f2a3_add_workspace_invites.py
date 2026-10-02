"""add workspace_invites for shareable invite links

Revision ID: b8c9d0e1f2a3
Revises: a7b8c9d0e1f2
Create Date: 2026-10-02

Backs the Go gateway's invite links (internal/workspace/sharing*.go). Only a
SHA-256 digest of each token is stored; a link never grants ownership.
"""

from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


revision: str = "b8c9d0e1f2a3"
down_revision: Union[str, Sequence[str], None] = "a7b8c9d0e1f2"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def _has_table(name: str) -> bool:
    return sa.inspect(op.get_bind()).has_table(name)


def upgrade() -> None:
    if _has_table("workspace_invites"):
        return
    op.create_table(
        "workspace_invites",
        sa.Column("id", sa.String(length=36), primary_key=True),
        sa.Column(
            "workspace_id",
            sa.String(length=100),
            sa.ForeignKey("workspaces.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("token_digest", sa.String(length=64), nullable=False, unique=True),
        sa.Column("role", sa.String(length=20), nullable=False),
        sa.Column(
            "created_by",
            sa.String(length=100),
            sa.ForeignKey("users.id", ondelete="SET NULL"),
            nullable=True,
        ),
        sa.Column("created_at", sa.DateTime(), nullable=False),
        sa.Column("expires_at", sa.DateTime(), nullable=False),
        sa.Column("max_uses", sa.Integer(), nullable=True),
        sa.Column("uses", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("revoked_at", sa.DateTime(), nullable=True),
        sa.CheckConstraint(
            "role IN ('editor', 'commenter', 'viewer')", name="chk_invite_role"
        ),
        sa.CheckConstraint(
            "max_uses IS NULL OR max_uses > 0", name="chk_invite_max_uses"
        ),
        sa.CheckConstraint("uses >= 0", name="chk_invite_uses"),
    )
    op.create_index(
        "ix_workspace_invites_workspace_id", "workspace_invites", ["workspace_id"]
    )


def downgrade() -> None:
    if _has_table("workspace_invites"):
        op.drop_table("workspace_invites")
