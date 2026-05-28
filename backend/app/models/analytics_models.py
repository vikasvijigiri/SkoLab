"""
app/models/analytics_models.py
================================
PostgreSQL tables for analytics, metrics, and user settings.

Tables
------
user_settings       – Structured per-user app settings (theme, notifications, privacy).
user_activity_log   – Lightweight event log (searches, profile views, connections).
api_request_log     – Per-request log for backend monitoring (latency, endpoint, status).
author_search_log   – Tracks which OpenAlex authors are searched (for trending / suggestions).
"""
import datetime
from sqlalchemy import Column, String, Integer, Float, Boolean, DateTime, JSON, Text, Index
from app.db.database import Base


class UserSettings(Base):
    """
    Structured settings for each user.  Unlike UserPreference (generic key/value),
    this table has typed columns matching the Android app's settings screen.
    """
    __tablename__ = "user_settings"

    user_id                = Column(String, primary_key=True, index=True)
    # Appearance
    theme                  = Column(String, default="dark")     # "dark" | "light" | "system"
    accent_color           = Column(String, nullable=True)
    # Notifications
    notify_connections     = Column(Boolean, default=True)
    notify_daily_feed      = Column(Boolean, default=True)
    notify_paper_alerts    = Column(Boolean, default=True)
    notify_messages        = Column(Boolean, default=True)
    # Privacy
    profile_visibility     = Column(String, default="public")   # "public" | "connections" | "private"
    show_email             = Column(Boolean, default=False)
    show_institution       = Column(Boolean, default=True)
    # Feed preferences
    feed_topics            = Column(JSON, nullable=True)         # list[str] of preferred topics
    feed_refresh_hours     = Column(Integer, default=24)
    # Research preferences
    preferred_journals     = Column(JSON, nullable=True)         # list[str]
    preferred_fields       = Column(JSON, nullable=True)         # list[str]
    # Timestamps
    created_at             = Column(DateTime, default=datetime.datetime.utcnow)
    updated_at             = Column(DateTime, default=datetime.datetime.utcnow,
                                    onupdate=datetime.datetime.utcnow)


class UserActivityLog(Base):
    """
    Lightweight event stream per user.  Used for personalised recommendations
    and engagement analytics.  Old records can be pruned (>30 days).
    """
    __tablename__ = "user_activity_log"

    id           = Column(Integer, primary_key=True, autoincrement=True)
    user_id      = Column(String, index=True, nullable=True)   # nullable = anonymous
    event_type   = Column(String, index=True, nullable=False)  # e.g. "author_search", "paper_view"
    entity_id    = Column(String, nullable=True)               # e.g. openalex_id of the author
    entity_name  = Column(String, nullable=True)               # display name for quick reads
    event_metadata = Column(JSON, nullable=True)               # extra event context (renamed from 'metadata')
    created_at   = Column(DateTime, default=datetime.datetime.utcnow, index=True)

    __table_args__ = (
        Index("ix_ual_user_event", "user_id", "event_type"),
    )


class ApiRequestLog(Base):
    """
    Server-side request log for monitoring and performance tracking.
    One row per HTTP request.  Prune records > 7 days old in a cron job.
    """
    __tablename__ = "api_request_log"

    id          = Column(Integer, primary_key=True, autoincrement=True)
    endpoint    = Column(String, index=True, nullable=False)   # e.g. "/api/v1/search_author"
    method      = Column(String, default="GET")
    status_code = Column(Integer, nullable=True)
    latency_ms  = Column(Float, nullable=True)
    user_id     = Column(String, nullable=True)
    author_id   = Column(String, nullable=True)               # if request was author-scoped
    error_msg   = Column(Text, nullable=True)
    created_at  = Column(DateTime, default=datetime.datetime.utcnow, index=True)


class AuthorSearchLog(Base):
    """
    Deduplicated log of every OpenAlex author who has been searched.
    Powers 'trending researchers' and boosts suggestion ranking for popular authors.
    """
    __tablename__ = "author_search_log"

    id                = Column(Integer, primary_key=True, autoincrement=True)
    openalex_id       = Column(String, index=True, nullable=False)
    display_name      = Column(String, nullable=False)
    search_count      = Column(Integer, default=1)
    last_searched_at  = Column(DateTime, default=datetime.datetime.utcnow,
                               onupdate=datetime.datetime.utcnow)
    first_searched_at = Column(DateTime, default=datetime.datetime.utcnow)

    __table_args__ = (
        Index("ix_asl_openalex_id", "openalex_id", unique=True),
    )
