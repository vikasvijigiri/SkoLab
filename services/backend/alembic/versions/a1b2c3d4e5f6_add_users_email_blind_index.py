"""add users.email_bidx blind-index column

Equality lookups on the Fernet-encrypted users.email column are impossible
(random IV per encryption). This adds a deterministic HMAC-SHA256 blind index
in its own column so /recommendations/peers/check-registered can resolve
emails again. Populated going forward by the User.validate_user_email
validator; existing rows are filled by scripts/backfill_email_bidx.py.

Revision ID: a1b2c3d4e5f6
Revises: 614f9e81193b
Create Date: 2026-09-03

"""

from typing import Sequence, Union

from alembic import op
import sqlalchemy as sa


revision: str = "a1b2c3d4e5f6"
down_revision: Union[str, Sequence[str], None] = "614f9e81193b"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.add_column("users", sa.Column("email_bidx", sa.String(length=64), nullable=True))
    op.create_index("ix_users_email_bidx", "users", ["email_bidx"], unique=False)


def downgrade() -> None:
    op.drop_index("ix_users_email_bidx", table_name="users")
    op.drop_column("users", "email_bidx")
