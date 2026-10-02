"""add workspaces.create_request_id for idempotent workspace creation

Revision ID: f6a7b8c9d0e1
Revises: e5f6a7b8c9d0
Create Date: 2026-10-02

Backs the Go gateway's POST /api/v1/workspaces (internal/workspace): a client
retry carrying the same Idempotency-Key returns the original workspace
instead of creating a second one. Keys are scoped per owner, so the unique
index is (owner_id, create_request_id); rows created without a key are NULL
and never collide.
"""

from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


revision: str = "f6a7b8c9d0e1"
down_revision: Union[str, Sequence[str], None] = "e5f6a7b8c9d0"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None

INDEX = "uq_workspaces_owner_create_request"


def _has_column(table: str, column: str) -> bool:
    return any(
        c["name"] == column for c in sa.inspect(op.get_bind()).get_columns(table)
    )


def _has_index(table: str, name: str) -> bool:
    return any(i["name"] == name for i in sa.inspect(op.get_bind()).get_indexes(table))


def upgrade() -> None:
    if not _has_column("workspaces", "create_request_id"):
        op.add_column(
            "workspaces",
            sa.Column("create_request_id", sa.String(length=100), nullable=True),
        )
    if not _has_index("workspaces", INDEX):
        op.create_index(
            INDEX,
            "workspaces",
            ["owner_id", "create_request_id"],
            unique=True,
            postgresql_where=sa.text("create_request_id IS NOT NULL"),
        )


def downgrade() -> None:
    if _has_index("workspaces", INDEX):
        op.drop_index(INDEX, table_name="workspaces")
    if _has_column("workspaces", "create_request_id"):
        op.drop_column("workspaces", "create_request_id")
