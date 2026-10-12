import datetime


def utcnow():
    return datetime.datetime.now(datetime.timezone.utc).replace(tzinfo=None)


import re
from sqlalchemy import (
    BigInteger,
    LargeBinary,
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


class WorkspaceDocument(Base):
    """The LaTeX source of a workspace's manuscript (one main.tex per workspace).

    Written by the Go gateway (internal/document). ``version`` increases by
    one on every save; a save names the version it was based on, so two
    editors cannot silently overwrite each other.
    """

    __tablename__ = "workspace_documents"

    workspace_id = Column(
        String(100),
        ForeignKey("workspaces.id", ondelete="CASCADE"),
        primary_key=True,
    )
    source = Column(Text, nullable=False)
    template_id = Column(String(64), nullable=True)
    version = Column(Integer, nullable=False, default=1)
    updated_at = Column(DateTime, default=utcnow, nullable=False)
    updated_by = Column(
        String(100),
        ForeignKey("users.id", ondelete="SET NULL"),
        nullable=True,
    )

    __table_args__ = (
        CheckConstraint("char_length(source) <= 100000", name="chk_workspace_document_size"),
        CheckConstraint("version >= 1", name="chk_workspace_document_version"),
    )


class WorkspaceFile(Base):
    """A file or folder in a workspace's project, beside its main.tex.

    Written by the Go gateway (internal/files). A text file keeps ``content``,
    an uploaded image or PDF keeps ``data``; a folder keeps neither. Paths are
    unique per workspace ignoring case, so a project unpacks the same on every
    file system.
    """

    __tablename__ = "workspace_files"

    id = Column(String(36), primary_key=True)
    workspace_id = Column(
        String(100),
        ForeignKey("workspaces.id", ondelete="CASCADE"),
        nullable=False,
    )
    path = Column(String(200), nullable=False)
    kind = Column(String(10), nullable=False)
    content = Column(Text, nullable=True)
    data = Column(LargeBinary, nullable=True)
    size = Column(Integer, nullable=False, default=0)
    content_type = Column(String(100), nullable=False, default="")
    version = Column(Integer, nullable=False, default=1)
    created_at = Column(DateTime, default=utcnow, nullable=False)
    updated_at = Column(DateTime, default=utcnow, nullable=False)
    updated_by = Column(
        String(100),
        ForeignKey("users.id", ondelete="SET NULL"),
        nullable=True,
    )

    __table_args__ = (
        CheckConstraint("kind IN ('folder', 'text', 'binary')", name="chk_workspace_file_kind"),
        CheckConstraint("size >= 0 AND size <= 5242880", name="chk_workspace_file_size"),
        CheckConstraint("version >= 1", name="chk_workspace_file_version"),
        Index("uq_workspace_files_path", "workspace_id", text("lower(path)"), unique=True),
    )


class WorkspaceOutput(Base):
    """The last PDF compiled from a workspace's project (Go gateway, internal/files)."""

    __tablename__ = "workspace_outputs"

    workspace_id = Column(
        String(100),
        ForeignKey("workspaces.id", ondelete="CASCADE"),
        primary_key=True,
    )
    pdf = Column(LargeBinary, nullable=False)
    size = Column(Integer, nullable=False)
    compiled_at = Column(DateTime, default=utcnow, nullable=False)
    compiled_by = Column(
        String(100),
        ForeignKey("users.id", ondelete="SET NULL"),
        nullable=True,
    )

    __table_args__ = (
        CheckConstraint("size >= 0 AND size <= 8388608", name="chk_workspace_output_size"),
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


class WorkspaceInvite(Base):
    """A shareable invite link into a workspace (Go gateway, internal/workspace).

    Only a SHA-256 digest of the token is stored; the token itself is shown
    once, at creation. A link never grants ownership.
    """

    __tablename__ = "workspace_invites"

    id = Column(String(36), primary_key=True)
    workspace_id = Column(
        String(100),
        ForeignKey("workspaces.id", ondelete="CASCADE"),
        index=True,
        nullable=False,
    )
    token_digest = Column(String(64), nullable=False, unique=True)
    role = Column(String(20), nullable=False)
    created_by = Column(
        String(100), ForeignKey("users.id", ondelete="SET NULL"), nullable=True
    )
    created_at = Column(DateTime, nullable=False, default=utcnow)
    expires_at = Column(DateTime, nullable=False)
    max_uses = Column(Integer, nullable=True)  # NULL = unlimited until expiry
    uses = Column(Integer, nullable=False, default=0)
    revoked_at = Column(DateTime, nullable=True)

    __table_args__ = (
        CheckConstraint(
            "role IN ('editor', 'commenter', 'viewer')", name="chk_invite_role"
        ),
        CheckConstraint("max_uses IS NULL OR max_uses > 0", name="chk_invite_max_uses"),
        CheckConstraint("uses >= 0", name="chk_invite_uses"),
    )


class WebSocketTicket(Base):
    """A single-use, short-lived ticket for the collaboration WebSocket
    (Go gateway, internal/websocket/tickets.go). Only the ticket's SHA-256
    is stored. Mirrors migration d4e5f6a7b8c9, so a database created from
    these models (a new environment, CI staging) has the same table.
    """

    __tablename__ = "websocket_tickets"

    session_auth_time = Column(BigInteger, nullable=False, server_default="0")

    ticket_hash = Column(String(64), primary_key=True)
    workspace_id = Column(
        String(100), ForeignKey("workspaces.id", ondelete="CASCADE"), nullable=False
    )
    user_id = Column(
        String(100), ForeignKey("users.id", ondelete="CASCADE"), nullable=False
    )
    expires_at = Column(DateTime(timezone=True), nullable=False)
    created_at = Column(
        DateTime(timezone=True), nullable=False, server_default=text("CURRENT_TIMESTAMP")
    )

    __table_args__ = (Index("ix_websocket_tickets_expires_at", "expires_at"),)


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
