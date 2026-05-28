"""
app/models/content_models.py
==============================
PostgreSQL tables for LLM-generated content served to the Android app.

Tables
------
daily_feed_items  – Papers recommended for an author's daily feed, with TTL.
conjectures       – LLM-generated scientific conjectures / puzzles per author.
"""
import datetime
from sqlalchemy import Column, String, Integer, Float, Boolean, DateTime, JSON, Text, Index
from app.db.database import Base


class DailyFeedItem(Base):
    """
    One row per (author, paper) pair in the daily feed.
    The set is regenerated when expires_at lapses (default 24-hour TTL).
    """
    __tablename__ = "daily_feed_items"

    id               = Column(Integer, primary_key=True, autoincrement=True)
    author_openalex_id = Column(String, index=True, nullable=False)
    work_openalex_id   = Column(String, nullable=True)    # can be null for LLM-only items
    title              = Column(Text, nullable=False)
    abstract_snippet   = Column(Text, nullable=True)
    journal            = Column(String, nullable=True)
    publication_year   = Column(Integer, nullable=True)
    doi                = Column(String, nullable=True)
    is_open_access     = Column(Boolean, default=False)
    citations          = Column(Integer, default=0)
    relevance_score    = Column(Float, default=0.0)
    reason             = Column(Text, nullable=True)    # LLM-generated "why this paper"
    concepts           = Column(JSON, nullable=True)   # list[str]
    authors            = Column(JSON, nullable=True)   # list[str]
    raw_data           = Column(JSON, nullable=True)   # full OpenAlex work JSON

    created_at = Column(DateTime, default=datetime.datetime.utcnow)
    expires_at = Column(DateTime, nullable=True)   # 24-hour TTL

    __table_args__ = (
        Index("ix_dfi_author_work", "author_openalex_id", "work_openalex_id"),
    )


class Conjecture(Base):
    """
    LLM-generated scientific puzzle / conjecture for a given author.
    Personalised based on that author's publications.
    TTL: 24 hours (a fresh conjecture is generated daily).
    """
    __tablename__ = "conjectures"

    id                  = Column(Integer, primary_key=True, autoincrement=True)
    author_openalex_id  = Column(String, index=True, nullable=False)
    category            = Column(String, nullable=True)
    title               = Column(String, nullable=False)
    hypothesis          = Column(Text, nullable=False)
    options             = Column(JSON, nullable=False)        # list[str] — 4 options
    correct_option_index = Column(Integer, nullable=False)
    explanation         = Column(Text, nullable=False)

    created_at = Column(DateTime, default=datetime.datetime.utcnow)
    expires_at = Column(DateTime, nullable=True)   # 24-hour TTL
