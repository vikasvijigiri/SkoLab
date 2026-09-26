"""add single-use websocket connection tickets

Revision ID: d4e5f6a7b8c9
Revises: c3d4e5f6a7b8
Create Date: 2026-09-26

Firebase ID tokens must not travel in a WebSocket URL: query strings are
commonly retained by access logs and diagnostic tooling. The gateway instead
exchanges a verified Firebase identity and workspace authorization for a
short-lived, one-use opaque ticket. Only the SHA-256 digest is persisted.
"""

from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


revision: str = "d4e5f6a7b8c9"
down_revision: Union[str, Sequence[str], None] = "c3d4e5f6a7b8"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def _has_table(name: str) -> bool:
    return sa.inspect(op.get_bind()).has_table(name)


def upgrade() -> None:
    if _has_table("websocket_tickets"):
        return

    op.create_table(
        "websocket_tickets",
        sa.Column("ticket_hash", sa.String(length=64), primary_key=True),
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
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            nullable=False,
            server_default=sa.text("CURRENT_TIMESTAMP"),
        ),
    )
    op.create_index(
        "ix_websocket_tickets_expires_at", "websocket_tickets", ["expires_at"]
    )


def downgrade() -> None:
    if _has_table("websocket_tickets"):
        op.drop_table("websocket_tickets")
