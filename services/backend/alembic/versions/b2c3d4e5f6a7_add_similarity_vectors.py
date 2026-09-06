"""add pgvector similarity tables (work_embeddings, author_embeddings)

Backs the similarity engine (docs/plans/2026-09-06-similarity-engine.md):
persistent bge-small (384-d) vectors for works and authors, populated lazily
by the teleport worker. No ANN index in this revision — exact kNN is fine
under ~50k rows and avoids HNSW build-memory pressure on the 512 MB instance;
an HNSW index lands in its own migration once row counts justify it.

Revision ID: b2c3d4e5f6a7
Revises: a1b2c3d4e5f6
Create Date: 2026-09-06

"""

from typing import Sequence, Union

from alembic import op


revision: str = "b2c3d4e5f6a7"
down_revision: Union[str, Sequence[str], None] = "a1b2c3d4e5f6"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.execute("CREATE EXTENSION IF NOT EXISTS vector")
    op.execute(
        """
        CREATE TABLE IF NOT EXISTS work_embeddings (
            work_id           text PRIMARY KEY,
            embedding         vector(384) NOT NULL,
            title             text,
            concepts          text[],
            referenced_works  text[],
            publication_year  integer,
            updated_at        timestamptz NOT NULL DEFAULT now()
        )
        """
    )
    op.execute(
        """
        CREATE TABLE IF NOT EXISTS author_embeddings (
            author_id     text PRIMARY KEY,
            embedding     vector(384) NOT NULL,
            concepts      text[],
            coauthor_ids  text[],
            institution   text,
            works_count   integer,
            h_index       integer,
            updated_at    timestamptz NOT NULL DEFAULT now()
        )
        """
    )
    # updated_at drives the Phase D TTL sweep.
    op.execute(
        "CREATE INDEX IF NOT EXISTS ix_work_embeddings_updated_at "
        "ON work_embeddings (updated_at)"
    )
    op.execute(
        "CREATE INDEX IF NOT EXISTS ix_author_embeddings_updated_at "
        "ON author_embeddings (updated_at)"
    )


def downgrade() -> None:
    op.execute("DROP TABLE IF EXISTS work_embeddings")
    op.execute("DROP TABLE IF EXISTS author_embeddings")
    # Leave the `vector` extension in place — other schema may adopt it.
