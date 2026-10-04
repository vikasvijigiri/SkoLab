"""Preserve Firebase sign-in time for socket revocation checks."""
from alembic import op
import sqlalchemy as sa

revision = "d0e1f2a3b4c5"
down_revision = "c9d0e1f2a3b4"
branch_labels = None
depends_on = None


def upgrade():
    if "session_auth_time" in {c["name"] for c in sa.inspect(op.get_bind()).get_columns("websocket_tickets")}:
        return
    op.add_column("websocket_tickets", sa.Column(
        "session_auth_time", sa.BigInteger(), nullable=False, server_default="0"
    ))


def downgrade():
    op.drop_column("websocket_tickets", "session_auth_time")
