import datetime
from sqlalchemy import Column, String, Integer, DateTime, Boolean, ForeignKey, JSON
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

