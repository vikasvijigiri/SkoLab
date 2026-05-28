"""
app/models/researcher_models.py
================================
PostgreSQL tables for researcher-domain data fetched from OpenAlex and
enriched by the teleport_researcher background worker.

Tables
------
researcher_works    – Individual paper records per OpenAlex author (replaces
                     Firestore global_researchers.works array).
researcher_metrics  – Computed 10 Modern Research Metrics per author (replaces
                     Firestore global_researchers document).
"""
import datetime
from sqlalchemy import (
    Column, String, Integer, Float, Boolean, DateTime, JSON, Text, Index
)
from app.db.database import Base


class ResearcherWork(Base):
    """
    One row per (author, paper) pair.
    TTL: 7 days — refreshed when teleport_researcher runs again.
    """
    __tablename__ = "researcher_works"

    id = Column(Integer, primary_key=True, autoincrement=True)
    author_openalex_id = Column(String, index=True, nullable=False)   # e.g. "A5020214245"
    work_openalex_id   = Column(String, index=True, nullable=False)   # e.g. "W2101..."
    title              = Column(Text,   nullable=False)
    publication_year   = Column(Integer, nullable=True)
    doi                = Column(String,  nullable=True)
    journal            = Column(String,  nullable=True)
    is_open_access     = Column(Boolean, default=False)
    citations          = Column(Integer, default=0)
    abstract           = Column(Text,    nullable=True)
    concepts           = Column(JSON,    nullable=True)   # list of display_name strings
    countries          = Column(JSON,    nullable=True)   # list of country_codes
    impact_factor      = Column(Float,   default=0.0)
    # Computed metric scores (set by researcher_worker)
    creativity_score   = Column(Float,   default=0.0)
    complexity_score   = Column(Float,   default=0.0)
    disruption_score   = Column(Float,   default=0.0)
    semantic_novelty   = Column(Float,   default=0.0)
    open_science_score = Column(Float,   default=0.0)

    last_synced  = Column(DateTime, default=datetime.datetime.utcnow,
                          onupdate=datetime.datetime.utcnow)
    expires_at   = Column(DateTime, nullable=True)   # 7-day TTL

    __table_args__ = (
        Index("ix_rw_author_work", "author_openalex_id", "work_openalex_id", unique=True),
    )


class ResearcherMetrics(Base):
    """
    One row per OpenAlex author — full enriched profile including all 10 Modern
    Research Metrics, computed by teleport_researcher.
    Replaces the Firestore 'global_researchers' collection.
    TTL: 7 days.
    """
    __tablename__ = "researcher_metrics"

    openalex_id         = Column(String, primary_key=True, index=True)
    display_name        = Column(String, nullable=False)
    orcid               = Column(String, nullable=True)

    # Core OpenAlex stats
    h_index             = Column(Integer, default=0)
    i10_index           = Column(Integer, default=0)
    works_count         = Column(Integer, default=0)
    cited_by_count      = Column(Integer, default=0)
    current_institution = Column(String,  nullable=True)
    field_of_study      = Column(String,  nullable=True)
    expertise           = Column(JSON,    nullable=True)   # list[str]
    academic_history    = Column(JSON,    nullable=True)   # list[str]

    # Aggregate averages across works
    average_creativity  = Column(Float, default=0.0)
    average_complexity  = Column(Float, default=0.0)
    average_skill_score = Column(Float, default=0.0)
    average_impact      = Column(Float, default=0.0)
    average_activity    = Column(Float, default=0.0)

    # The 10 Modern Research Metrics
    disruption_score      = Column(Float, default=0.0)
    citation_acceleration = Column(Float, default=0.0)
    future_impact_score   = Column(Float, default=0.0)
    network_centrality    = Column(Float, default=0.0)
    semantic_novelty      = Column(Float, default=0.0)
    interdisciplinary_index = Column(Float, default=0.0)
    policy_patent_score   = Column(Float, default=0.0)
    open_science_score    = Column(Float, default=0.0)
    collaboration_diversity = Column(Float, default=0.0)
    research_consistency  = Column(Float, default=0.0)

    # Composite
    innovation_score      = Column(Float, nullable=True)

    # LLM-generated next-research prediction
    next_prediction       = Column(Text, nullable=True)
    metrics_computed      = Column(Boolean, default=False)

    last_teleported = Column(Float, nullable=True)   # unix timestamp
    last_synced     = Column(DateTime, default=datetime.datetime.utcnow,
                             onupdate=datetime.datetime.utcnow)
    expires_at      = Column(DateTime, nullable=True)   # 7-day TTL
