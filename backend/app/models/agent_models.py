"""
app/models/agent_models.py
===========================
PostgreSQL tables for agent (Ask Skolar) domain data.

Tables
------
agent_history_summaries  – LLM-compressed summaries of long chat histories.
                           Replaces the in-memory history_summary_cache.
agent_document_uploads   – Extracted text from user-uploaded PDF / text files.
"""
import datetime
from sqlalchemy import Column, String, Integer, DateTime, Text, Index
from app.db.database import Base


class AgentHistorySummary(Base):
    """
    Stores LLM-generated summaries of older chat history segments so that
    the agent can handle long conversations without re-summarising every time.

    cache_key  = SHA-256 hex digest of the serialised older_messages list
    summary    = the compressed text produced by the summarisation LLM
    TTL: 12 hours (matches the old history_summary_cache ttl of 43200 s)
    """
    __tablename__ = "agent_history_summaries"

    id         = Column(Integer, primary_key=True, autoincrement=True)
    cache_key  = Column(String, unique=True, index=True, nullable=False)
    user_id    = Column(String, index=True, nullable=True)   # optional — for future scoping
    summary    = Column(Text, nullable=False)
    created_at = Column(DateTime, default=datetime.datetime.utcnow)
    expires_at = Column(DateTime, nullable=True)   # 12-hour TTL


class AgentDocumentUpload(Base):
    """
    Stores extracted text from uploaded documents so subsequent agent calls
    can reference the document without re-parsing.

    TTL: 24 hours.
    """
    __tablename__ = "agent_document_uploads"

    id             = Column(Integer, primary_key=True, autoincrement=True)
    user_id        = Column(String, index=True, nullable=True)
    filename       = Column(String, nullable=False)
    content_type   = Column(String, nullable=True)
    extracted_text = Column(Text, nullable=False)
    file_size_kb   = Column(Integer, nullable=True)
    uploaded_at    = Column(DateTime, default=datetime.datetime.utcnow)
    expires_at     = Column(DateTime, nullable=True)   # 24-hour TTL
