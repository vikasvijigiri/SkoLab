import datetime
from sqlalchemy import Column, String, Integer, DateTime, Boolean, ForeignKey, JSON, Text
from sqlalchemy.orm import relationship
from app.db.database import Base

class User(Base):
    __tablename__ = "users"
    
    id = Column(String, primary_key=True, index=True) # e.g. "vikas_uid"
    openalex_id = Column(String, index=True, nullable=True) # e.g. "A5020214245"
    display_name = Column(String, nullable=False)
    email = Column(String, nullable=True)
    created_at = Column(DateTime, default=datetime.datetime.utcnow)

    preferences = relationship("UserPreference", back_populates="user", cascade="all, delete")
    connections_sent = relationship("Connection", foreign_keys="[Connection.user_id]", back_populates="user", cascade="all, delete")
    connections_received = relationship("Connection", foreign_keys="[Connection.connected_user_id]", back_populates="connected_user", cascade="all, delete")

class UserPreference(Base):
    __tablename__ = "user_preferences"
    
    id = Column(Integer, primary_key=True, autoincrement=True)
    user_id = Column(String, ForeignKey("users.id", ondelete="CASCADE"), index=True)
    preference_key = Column(String, nullable=False)
    preference_value = Column(JSON, nullable=True) # Storing as JSON allows for complex preferences

    user = relationship("User", back_populates="preferences")

class Connection(Base):
    __tablename__ = "connections"
    
    id = Column(Integer, primary_key=True, autoincrement=True)
    user_id = Column(String, ForeignKey("users.id", ondelete="CASCADE"), index=True)
    connected_user_id = Column(String, ForeignKey("users.id", ondelete="CASCADE"), index=True)
    status = Column(String, default="pending") # "pending", "accepted", "blocked"
    created_at = Column(DateTime, default=datetime.datetime.utcnow)

    user = relationship("User", foreign_keys=[user_id], back_populates="connections_sent")
    connected_user = relationship("User", foreign_keys=[connected_user_id], back_populates="connections_received")

class Message(Base):
    __tablename__ = "messages"
    
    id = Column(Integer, primary_key=True, autoincrement=True)
    sender_id = Column(String, ForeignKey("users.id", ondelete="CASCADE"), index=True)
    receiver_id = Column(String, ForeignKey("users.id", ondelete="CASCADE"), index=True)
    content = Column(String, nullable=False)
    timestamp = Column(DateTime, default=datetime.datetime.utcnow)

class AgentChatHistory(Base):
    __tablename__ = "agent_chat_history"
    
    id = Column(Integer, primary_key=True, autoincrement=True)
    user_id = Column(String, index=True, nullable=True)  # No FK — allows local/anonymous users
    context_id = Column(String, index=True, nullable=True) # E.g., paper ID or author ID to scope the chat
    role = Column(String, nullable=False) # "user" or "assistant" or "system"
    content = Column(String, nullable=False)
    timestamp = Column(DateTime, default=datetime.datetime.utcnow)

class CacheEntry(Base):
    __tablename__ = "cache_entries"
    
    id = Column(Integer, primary_key=True, autoincrement=True)
    cache_key = Column(String, unique=True, index=True, nullable=False)
    data = Column(JSON, nullable=False)
    last_synced = Column(DateTime, default=datetime.datetime.utcnow, onupdate=datetime.datetime.utcnow)
    # Optional TTL: if set, the entry is considered stale after this datetime
    expires_at = Column(DateTime, nullable=True)


class ResearcherProfile(Base):
    """
    Persistent store of researcher profiles fetched from OpenAlex.
    Updated each time the network collaborator pipeline runs for this author.
    TTL: 7 days — stale profiles get refreshed automatically.
    """
    __tablename__ = "researcher_profiles"

    openalex_id = Column(String, primary_key=True, index=True)  # e.g. "A5020214245"
    display_name = Column(String, nullable=False)
    institution = Column(String, nullable=True)
    field_of_study = Column(String, nullable=True)
    h_index = Column(Integer, nullable=True)
    works_count = Column(Integer, nullable=True)
    concepts = Column(JSON, nullable=True)          # list of concept display names
    raw_profile = Column(JSON, nullable=True)       # full OpenAlex author JSON blob
    last_synced = Column(DateTime, default=datetime.datetime.utcnow, onupdate=datetime.datetime.utcnow)
    expires_at = Column(DateTime, nullable=True)   # refresh after 7 days


class ResearcherConnection(Base):
    """
    Persistent store of computed co-author connections for each researcher.
    Depth 1 = direct co-author; Depth 2 = co-author's co-author.
    TTL: 24 hours per author_id.
    """
    __tablename__ = "researcher_connections"

    id = Column(Integer, primary_key=True, autoincrement=True)
    # The primary researcher whose graph this belongs to
    author_openalex_id = Column(String, index=True, nullable=False)
    # The discovered connection
    connection_openalex_id = Column(String, index=True, nullable=False)
    connection_name = Column(String, nullable=False)
    connection_institution = Column(String, nullable=True)
    connection_field = Column(String, nullable=True)
    depth = Column(Integer, nullable=False)          # 1 or 2
    connection_path = Column(Text, nullable=True)    # human-readable explanation
    relevance_score = Column(Integer, default=70)
    papers_collaborated = Column(Integer, default=1)
    total_publications = Column(Integer, nullable=True)
    h_index = Column(Integer, nullable=True)
    last_synced = Column(DateTime, default=datetime.datetime.utcnow)
    expires_at = Column(DateTime, nullable=True)    # refresh after 24 hours

