import datetime


def utcnow():
    return datetime.datetime.now(datetime.timezone.utc).replace(tzinfo=None)


import re
from sqlalchemy import (
    BigInteger,
    Column,
    String,
    Integer,
    DateTime,
    ForeignKey,
    Text,
    CheckConstraint,
    Index,
    UniqueConstraint,
    text,
)
from sqlalchemy.orm import validates
from app.db.database import Base
from app.db.encrypted_type import EncryptedString


class User(Base):
    __tablename__ = "users"

    id = Column(String(100), primary_key=True, index=True)  # e.g. "user_uid"
    openalex_id = Column(String(100), index=True, nullable=True)  # e.g. "A5020214245"
    display_name = Column(String(255), nullable=False)
    username = Column(String(100), unique=True, index=True, nullable=True)
    author_name = Column(String(255), nullable=True)
    email = Column(EncryptedString, nullable=True)
    # Deterministic HMAC of the normalised email, for equality lookups the
    # Fernet-encrypted `email` column cannot serve. Kept in sync by the
    # `validate_user_email` validator below. See app/db/blind_index.py.
    email_bidx = Column(String(64), index=True, nullable=True)
    phone = Column(String(50), nullable=True)
    research_focus = Column(Text, nullable=True)
    created_at = Column(DateTime, default=utcnow)

    @validates("email")
    def validate_user_email(self, key, address):
        if address is not None:
            if len(address) > 255:
                raise ValueError("Email exceeds maximum length of 255 characters")
            EMAIL_REGEX = re.compile(
                r"^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$"
            )
            if not EMAIL_REGEX.match(address):
                raise ValueError(f"Invalid email format: {address}")
        # Keep the blind index in lock-step with the encrypted value on every
        # ORM write. Imported lazily to avoid a config import at module load.
        from app.db.blind_index import email_blind_index

        self.email_bidx = email_blind_index(address)
        return address


class Workspace(Base):
    """A collaborative manuscript workspace owned by one researcher."""

    __tablename__ = "workspaces"

    id = Column(String(100), primary_key=True)
    owner_id = Column(
        String(100),
        ForeignKey("users.id", ondelete="CASCADE"),
        index=True,
        nullable=False,
    )
    title = Column(String(255), nullable=False)
    created_at = Column(DateTime, default=utcnow, nullable=False)
    # Idempotency-Key of the POST /api/v1/workspaces call that created this
    # row (Go gateway, internal/workspace); unique per owner when present.
    create_request_id = Column(String(100), nullable=True)

    __table_args__ = (
        CheckConstraint("length(trim(title)) > 0", name="chk_workspace_title_nonempty"),
        Index(
            "uq_workspaces_owner_create_request",
            "owner_id",
            "create_request_id",
            unique=True,
            postgresql_where=text("create_request_id IS NOT NULL"),
        ),
    )


class WorkspaceMember(Base):
    """An explicit, active role assignment for a workspace collaborator."""

    __tablename__ = "workspace_members"

    id = Column(Integer, primary_key=True, autoincrement=True)
    workspace_id = Column(
        String(100),
        ForeignKey("workspaces.id", ondelete="CASCADE"),
        index=True,
        nullable=False,
    )
    user_id = Column(
        String(100),
        ForeignKey("users.id", ondelete="CASCADE"),
        index=True,
        nullable=False,
    )
    role = Column(String(20), nullable=False, default="viewer")
    status = Column(String(20), nullable=False, default="active")
    created_at = Column(DateTime, default=utcnow, nullable=False)

    __table_args__ = (
        UniqueConstraint("workspace_id", "user_id", name="uq_workspace_member"),
        CheckConstraint(
            "role IN ('owner', 'editor', 'commenter', 'viewer')",
            name="chk_workspace_member_role",
        ),
        CheckConstraint(
            "status IN ('active', 'invited', 'removed')",
            name="chk_workspace_member_status",
        ),
    )


class UsageCounter(Base):
    """Fixed-window cost counter backing per-user quotas (app/core/quota.py).

    One row per (user, window) bucket; ``count`` is the cost units consumed.
    Rows are dead after ``expires_at`` and swept opportunistically.
    """

    __tablename__ = "usage_counters"

    bucket_key = Column(String(160), primary_key=True)
    count = Column(Integer, nullable=False, default=0)
    expires_at = Column(DateTime, nullable=False, index=True)


class SecurityAuditLog(Base):
    """Append-only record of account-level security actions.

    Written by the Go gateway (internal/security.Audit) for account deletion
    and workspace create/rename/delete. Deliberately has no foreign keys: the
    trail must outlive the account and workspace it describes. High-volume
    denials (bad tokens, throttling) are metrics and logs, not rows here.
    """

    __tablename__ = "security_audit_log"

    id = Column(BigInteger, primary_key=True, autoincrement=True)
    occurred_at = Column(DateTime, nullable=False, default=utcnow)
    event = Column(String(64), nullable=False)
    outcome = Column(String(16), nullable=False)
    actor_id = Column(String(100), nullable=True)
    workspace_id = Column(String(100), nullable=True)
    ip = Column(String(64), nullable=True)
    request_id = Column(String(100), nullable=True)
    reason = Column(String(255), nullable=True)

    __table_args__ = (
        Index("ix_security_audit_log_actor_time", "actor_id", "occurred_at"),
        Index("ix_security_audit_log_time", "occurred_at"),
    )
