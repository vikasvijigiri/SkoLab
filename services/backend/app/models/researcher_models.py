import datetime


def utcnow():
    return datetime.datetime.now(datetime.timezone.utc).replace(tzinfo=None)


import re
from typing import Optional
from sqlalchemy import (
    Column,
    String,
    Integer,
    Float,
    Boolean,
    DateTime,
    JSON,
    Text,
    Index,
)
from sqlalchemy.orm import validates
from app.db.database import Base

# Standard DOI format regex: starts with 10. followed by 4 or more digits, a slash, and character sequence
DOI_REGEX = re.compile(r"^10\.\d{4,9}/[-._;()/:A-Za-z0-9]+$")


def clean_and_validate_doi(doi: Optional[str]) -> Optional[str]:
    if not doi:
        return None
    # Strip common URL prefixes
    clean = doi
    for prefix in ["https://doi.org/", "http://doi.org/", "doi:"]:
        if clean.startswith(prefix):
            clean = clean[len(prefix) :]
    if not DOI_REGEX.match(clean):
        raise ValueError(f"Invalid DOI format: {doi}")
    return clean


class ResearcherWork(Base):
    """
    One row per (author, paper) pair.
    TTL: 7 days — refreshed when teleport_researcher runs again.
    """

    __tablename__ = "researcher_works"

    id = Column(Integer, primary_key=True, autoincrement=True)
    author_openalex_id = Column(
        String(100), index=True, nullable=False
    )  # e.g. "A5020214245"
    work_openalex_id = Column(
        String(100), index=True, nullable=False
    )  # e.g. "W2101..."
    title = Column(Text, nullable=False)
    publication_year = Column(Integer, nullable=True)
    doi = Column(String(255), nullable=True)
    journal = Column(String(255), nullable=True)
    is_open_access = Column(Boolean, default=False)
    citations = Column(Integer, default=0)
    abstract = Column(Text, nullable=True)
    concepts = Column(JSON, nullable=True)  # list of display_name strings
    countries = Column(JSON, nullable=True)  # list of country_codes
    impact_factor = Column(Float, default=0.0)
    # Computed metric scores (set by researcher_worker)
    creativity_score = Column(Float, default=0.0)
    complexity_score = Column(Float, default=0.0)
    disruption_score = Column(Float, default=0.0)
    semantic_novelty = Column(Float, default=0.0)
    open_science_score = Column(Float, default=0.0)

    last_synced = Column(DateTime, default=utcnow, onupdate=utcnow)
    expires_at = Column(DateTime, nullable=True)  # 7-day TTL

    __table_args__ = (
        Index(
            "ix_rw_author_work", "author_openalex_id", "work_openalex_id", unique=True
        ),
    )

    @validates("doi")
    def validate_doi(self, key, value):
        if value is not None:
            return clean_and_validate_doi(value)
        return value


class ResearcherMetrics(Base):
    """
    One row per OpenAlex author — full enriched profile including all 10 Modern
    Research Metrics, computed by teleport_researcher.
    Replaces the Firestore 'global_researchers' collection.
    TTL: 7 days.
    """

    __tablename__ = "researcher_metrics"

    openalex_id = Column(String(100), primary_key=True, index=True)
    display_name = Column(String(255), nullable=False)
    orcid = Column(String(100), nullable=True)

    # Core OpenAlex stats
    h_index = Column(Integer, default=0)
    i10_index = Column(Integer, default=0)
    works_count = Column(Integer, default=0)
    cited_by_count = Column(Integer, default=0)
    current_institution = Column(String(255), nullable=True)
    field_of_study = Column(String(255), nullable=True)
    expertise = Column(JSON, nullable=True)  # list[str]
    academic_history = Column(JSON, nullable=True)  # list[str]

    # Aggregate averages across works
    average_creativity = Column(Float, default=0.0)
    average_complexity = Column(Float, default=0.0)
    average_skill_score = Column(Float, default=0.0)
    average_impact = Column(Float, default=0.0)
    average_activity = Column(Float, default=0.0)

    # The 10 Modern Research Metrics
    disruption_score = Column(Float, default=0.0)
    citation_acceleration = Column(Float, default=0.0)
    future_impact_score = Column(Float, default=0.0)
    network_centrality = Column(Float, default=0.0)
    semantic_novelty = Column(Float, default=0.0)
    interdisciplinary_index = Column(Float, default=0.0)
    policy_patent_score = Column(Float, default=0.0)
    open_science_score = Column(Float, default=0.0)
    collaboration_diversity = Column(Float, default=0.0)
    research_consistency = Column(Float, default=0.0)

    # Composite
    innovation_score = Column(Float, nullable=True)

    # LLM-generated next-research prediction
    next_prediction = Column(Text, nullable=True)
    metrics_computed = Column(Boolean, default=False)

    last_teleported = Column(Float, nullable=True)  # unix timestamp
    last_synced = Column(DateTime, default=utcnow, onupdate=utcnow)
    expires_at = Column(DateTime, nullable=True)  # 7-day TTL

    @validates("expertise")
    def validate_expertise(self, key, value):
        if value is None:
            return None
        if not isinstance(value, list):
            raise ValueError("Expertise must be a list")

        CLEAN_TEXT_REGEX = re.compile(r"^[a-zA-Z0-9\s\-_.,()]+$")
        clean_list = []
        for item in value:
            if isinstance(item, str):
                stripped = item.strip()
                if stripped and CLEAN_TEXT_REGEX.match(stripped):
                    clean_list.append(stripped)
            else:
                raise ValueError("Expertise list must contain only strings")
        return clean_list
