"""add per-user usage counters for cost quotas

Revision ID: e5f6a7b8c9d0
Revises: d4e5f6a7b8c9
Create Date: 2026-09-27

Backs app/core/quota.py: a shared, replica-safe fixed-window counter so a
single authenticated account cannot run up unbounded LLM / scraping spend.
"""

from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


revision: str = "e5f6a7b8c9d0"
down_revision: Union[str, Sequence[str], None] = "d4e5f6a7b8c9"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def _has_table(name: str) -> bool:
    return sa.inspect(op.get_bind()).has_table(name)


def upgrade() -> None:
    if _has_table("usage_counters"):
        return

    op.create_table(
        "usage_counters",
        sa.Column("bucket_key", sa.String(length=160), primary_key=True),
        sa.Column("count", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("expires_at", sa.DateTime(), nullable=False),
    )
    op.create_index("ix_usage_counters_expires_at", "usage_counters", ["expires_at"])


def downgrade() -> None:
    if _has_table("usage_counters"):
        op.drop_table("usage_counters")
