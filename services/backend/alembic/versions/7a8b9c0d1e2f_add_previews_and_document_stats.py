"""add document previews and stats for the documents page

Revision ID: 7a8b9c0d1e2f
Revises: 6f7a8b9c0d1e
Create Date: 2026-10-17

The documents page shows each document's first page and its facts before
it is opened. ``workspace_outputs.preview`` holds a small JPEG of the last
compiled PDF's first page, made by the Go gateway (internal/preview) the
first time it is asked for and cleared by every new compile.
``workspace_documents.words`` and ``progress`` are kept by every save
(internal/manuscript), so listing many documents never re-reads their
sources; ``stats_version`` lets the gateway fill them in once for documents
saved before this column existed. All have server defaults: the gateway
writes plain SQL.
"""

from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


revision: str = "7a8b9c0d1e2f"
down_revision: Union[str, Sequence[str], None] = "6f7a8b9c0d1e"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def _has(table: str, column: str) -> bool:
    return any(c["name"] == column for c in sa.inspect(op.get_bind()).get_columns(table))


def upgrade() -> None:
    if not _has("workspace_outputs", "preview"):
        op.add_column("workspace_outputs", sa.Column("preview", sa.LargeBinary(), nullable=True))
    for name, column in (
        ("words", sa.Column("words", sa.Integer(), nullable=False, server_default="0")),
        ("progress", sa.Column("progress", sa.Integer(), nullable=False, server_default="0")),
        ("stats_version", sa.Column("stats_version", sa.Integer(), nullable=False, server_default="0")),
    ):
        if not _has("workspace_documents", name):
            op.add_column("workspace_documents", column)


def downgrade() -> None:
    for name in ("stats_version", "progress", "words"):
        if _has("workspace_documents", name):
            op.drop_column("workspace_documents", name)
    if _has("workspace_outputs", "preview"):
        op.drop_column("workspace_outputs", "preview")
