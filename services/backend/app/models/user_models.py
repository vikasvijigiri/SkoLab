import datetime


def utcnow():
    return datetime.datetime.now(datetime.timezone.utc).replace(tzinfo=None)


import re
from sqlalchemy import (
    Column,
    String,
    Integer,
    DateTime,
    ForeignKey,
    JSON,
    Text,
    CheckConstraint,
    UniqueConstraint,
    Float,
)
from sqlalchemy.orm import relationship, validates
from app.db.database import Base
from app.db.encrypted_type import EncryptedString


class User(Base):
    __tablename__ = "users"

    id = Column(String(100), primary_key=True, index=True)  # e.g. "user_uid"
    openalex_id = Column(String(100), index=True, nullable=True)  # e.g. "A5020214245"
    display_name = Column(String(255), nullable=False)
    email = Column(EncryptedString, nullable=True)
    created_at = Column(DateTime, default=utcnow)

    preferences = relationship(
        "UserPreference", back_populates="user", cascade="all, delete"
    )
    connections_sent = relationship(
        "Connection",
        foreign_keys="[Connection.user_id]",
        back_populates="user",
        cascade="all, delete",
    )
    connections_received = relationship(
        "Connection",
        foreign_keys="[Connection.connected_user_id]",
        back_populates="connected_user",
        cascade="all, delete",
    )

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
        return address


class UserPreference(Base):
    __tablename__ = "user_preferences"

    id = Column(Integer, primary_key=True, autoincrement=True)
    user_id = Column(
        String(100), ForeignKey("users.id", ondelete="CASCADE"), index=True
    )
    preference_key = Column(String(255), nullable=False)
    preference_value = Column(
        JSON, nullable=True
    )  # Storing as JSON allows for complex preferences

    user = relationship("User", back_populates="preferences")

    __table_args__ = (
        UniqueConstraint("user_id", "preference_key", name="uq_user_preference_key"),
    )


class Connection(Base):
    __tablename__ = "connections"

    id = Column(Integer, primary_key=True, autoincrement=True)
    user_id = Column(
        String(100), ForeignKey("users.id", ondelete="CASCADE"), index=True
    )
    connected_user_id = Column(
        String(100), ForeignKey("users.id", ondelete="CASCADE"), index=True
    )
    status = Column(String(50), default="pending")  # "pending", "accepted", "blocked"
    created_at = Column(DateTime, default=utcnow)

    user = relationship(
        "User", foreign_keys=[user_id], back_populates="connections_sent"
    )
    connected_user = relationship(
        "User", foreign_keys=[connected_user_id], back_populates="connections_received"
    )

    __table_args__ = (
        CheckConstraint(
            "status IN ('pending', 'accepted', 'blocked')", name="chk_connection_status"
        ),
    )


class Message(Base):
    __tablename__ = "messages"

    id = Column(Integer, primary_key=True, autoincrement=True)
    sender_id = Column(
        String(100), ForeignKey("users.id", ondelete="CASCADE"), index=True
    )
    receiver_id = Column(
        String(100), ForeignKey("users.id", ondelete="CASCADE"), index=True
    )
    content = Column(String(4000), nullable=False)
    timestamp = Column(DateTime, default=utcnow)


class AgentChatHistory(Base):
    __tablename__ = "agent_chat_history"

    id = Column(Integer, primary_key=True, autoincrement=True)
    user_id = Column(
        String(100), index=True, nullable=True
    )  # No FK — allows local/anonymous users
    context_id = Column(
        String(100), index=True, nullable=True
    )  # E.g., paper ID or author ID to scope the chat
    role = Column(String(50), nullable=False)  # "user" or "assistant" or "system"
    content = Column(String(4000), nullable=False)
    timestamp = Column(DateTime, default=utcnow)

    __table_args__ = (
        CheckConstraint(
            "role IN ('user', 'assistant', 'system')", name="chk_agent_chat_role"
        ),
    )


class CacheEntry(Base):
    __tablename__ = "cache_entries"

    id = Column(Integer, primary_key=True, autoincrement=True)
    cache_key = Column(String(512), unique=True, index=True, nullable=False)
    data = Column(JSON, nullable=False)
    last_synced = Column(DateTime, default=utcnow, onupdate=utcnow)
    # Optional TTL: if set, the entry is considered stale after this datetime
    expires_at = Column(DateTime, nullable=True)


class ResearcherProfile(Base):
    """
    Persistent store of researcher profiles fetched from OpenAlex.
    Updated each time the network collaborator pipeline runs for this author.
    TTL: 7 days — stale profiles get refreshed automatically.
    """

    __tablename__ = "researcher_profiles"

    openalex_id = Column(
        String(100), primary_key=True, index=True
    )  # e.g. "A5020214245"
    display_name = Column(String(255), nullable=False)
    institution = Column(String(255), nullable=True)
    field_of_study = Column(String(255), nullable=True)
    h_index = Column(Integer, nullable=True)
    works_count = Column(Integer, nullable=True)
    concepts = Column(JSON, nullable=True)  # list of concept display names
    raw_profile = Column(JSON, nullable=True)  # full OpenAlex author JSON blob
    last_synced = Column(DateTime, default=utcnow, onupdate=utcnow)
    expires_at = Column(DateTime, nullable=True)  # refresh after 7 days


class UserCircle(Base):
    """
    A user's research circle — peers in the same or adjacent field.
    Populated automatically after Spark sessions and manually by the user.
    Used to prioritise who gets ringed when "Ask an Expert" is triggered.
    """

    __tablename__ = "user_circles"

    id = Column(Integer, primary_key=True, autoincrement=True)
    user_id = Column(
        String(100),
        ForeignKey("users.id", ondelete="CASCADE"),
        index=True,
        nullable=False,
    )
    peer_id = Column(
        String(100),
        ForeignKey("users.id", ondelete="CASCADE"),
        index=True,
        nullable=False,
    )
    # "spark_session" | "same_field" | "collab" | "manual"
    relationship_type = Column(String(50), nullable=False, default="same_field")
    field_tags = Column(JSON, nullable=True)  # overlapping research tags
    spark_sessions_count = Column(Integer, default=0)  # times they've helped each other
    relevance_score = Column(Float, default=0.5)  # 0–1, higher = closer match
    last_interacted = Column(DateTime, nullable=True)
    created_at = Column(DateTime, default=utcnow)

    __table_args__ = (
        UniqueConstraint("user_id", "peer_id", name="uq_user_circle_pair"),
        CheckConstraint(
            "relationship_type IN ('spark_session', 'same_field', 'collab', 'manual')",
            name="chk_circle_relationship_type",
        ),
    )


class ResearcherConnection(Base):
    """
    Persistent store of computed co-author connections for each researcher.
    Depth 1 = direct co-author; Depth 2 = co-author's co-author.
    TTL: 24 hours per author_id.
    """

    __tablename__ = "researcher_connections"

    id = Column(Integer, primary_key=True, autoincrement=True)
    # The primary researcher whose graph this belongs to
    author_openalex_id = Column(String(100), index=True, nullable=False)
    # The discovered connection
    connection_openalex_id = Column(String(100), index=True, nullable=False)
    connection_name = Column(String(255), nullable=False)
    connection_institution = Column(String(255), nullable=True)
    connection_field = Column(String(255), nullable=True)
    depth = Column(Integer, nullable=False)  # 1 or 2
    connection_path = Column(Text, nullable=True)  # human-readable explanation
    relevance_score = Column(Integer, default=70)
    papers_collaborated = Column(Integer, default=1)
    total_publications = Column(Integer, nullable=True)
    h_index = Column(Integer, nullable=True)
    last_synced = Column(DateTime, default=utcnow)
    expires_at = Column(DateTime, nullable=True)  # refresh after 24 hours
