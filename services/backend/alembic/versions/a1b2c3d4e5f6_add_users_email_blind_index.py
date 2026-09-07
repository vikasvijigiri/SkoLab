"""add users.email_bidx blind-index column

Equality lookups on the Fernet-encrypted users.email column are impossible
(random IV per encryption). This adds a deterministic HMAC-SHA256 blind index
in its own column so /recommendations/peers/check-registered can resolve
emails again. Populated going forward by the User.validate_user_email
validator; existing rows are filled by scripts/backfill_email_bidx.py.

Revision ID: a1b2c3d4e5f6
Revises: 614f9e81193b
Create Date: 2026-09-03

Idempotent: safe to run after Base.metadata.create_all (which already adds
`email_bidx` from the current model) and safe to re-run.
"""

from typing import Sequence, Union

from alembic import op

revision: str = "a1b2c3d4e5f6"
down_revision: Union[str, Sequence[str], None] = "614f9e81193b"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def _pg() -> bool:
    return op.get_bind().dialect.name == "postgresql"


def _has_column(table: str, column: str) -> bool:
    import sqlalchemy as sa

    insp = sa.inspect(op.get_bind())
    try:
        return any(c["name"] == column for c in insp.get_columns(table))
    except Exception:
        return False


def upgrade() -> None:
    if _pg():
        op.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS email_bidx varchar(64)")
    elif not _has_column("users", "email_bidx"):
        import sqlalchemy as sa

        op.add_column(
            "users", sa.Column("email_bidx", sa.String(length=64), nullable=True)
        )
    op.execute("CREATE INDEX IF NOT EXISTS ix_users_email_bidx ON users (email_bidx)")


def downgrade() -> None:
    op.execute("DROP INDEX IF EXISTS ix_users_email_bidx")
    if _pg():
        op.execute("ALTER TABLE users DROP COLUMN IF EXISTS email_bidx")
    elif _has_column("users", "email_bidx"):
        op.drop_column("users", "email_bidx")
