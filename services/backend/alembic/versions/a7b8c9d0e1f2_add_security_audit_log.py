"""add security_audit_log for account-level security actions

Revision ID: a7b8c9d0e1f2
Revises: f6a7b8c9d0e1
Create Date: 2026-10-02

Backs the Go gateway's internal/security.Audit: account deletion and
workspace create/rename/delete are appended here. No foreign keys -- the
trail must outlive the account and workspace it describes.
"""

from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


revision: str = "a7b8c9d0e1f2"
down_revision: Union[str, Sequence[str], None] = "f6a7b8c9d0e1"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def _has_table(name: str) -> bool:
    return sa.inspect(op.get_bind()).has_table(name)


def upgrade() -> None:
    if _has_table("security_audit_log"):
        return
    op.create_table(
        "security_audit_log",
        sa.Column("id", sa.BigInteger(), primary_key=True, autoincrement=True),
        sa.Column("occurred_at", sa.DateTime(), nullable=False),
        sa.Column("event", sa.String(length=64), nullable=False),
        sa.Column("outcome", sa.String(length=16), nullable=False),
        sa.Column("actor_id", sa.String(length=100), nullable=True),
        sa.Column("workspace_id", sa.String(length=100), nullable=True),
        sa.Column("ip", sa.String(length=64), nullable=True),
        sa.Column("request_id", sa.String(length=100), nullable=True),
        sa.Column("reason", sa.String(length=255), nullable=True),
    )
    op.create_index(
        "ix_security_audit_log_actor_time",
        "security_audit_log",
        ["actor_id", "occurred_at"],
    )
    op.create_index("ix_security_audit_log_time", "security_audit_log", ["occurred_at"])


def downgrade() -> None:
    if _has_table("security_audit_log"):
        op.drop_table("security_audit_log")
