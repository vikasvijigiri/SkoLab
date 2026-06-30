"""
Pydantic schemas for the SkoLab Recommendation System.
Covers papers, grants, and collaborator recommendation response models.
"""

from pydantic import BaseModel, Field
from typing import List, Optional


class PaperRecommendation(BaseModel):
    id: str
    title: str
    authors: List[str]
    journal: Optional[str] = None
    year: int
    relevance_score: float = Field(..., ge=0.0, le=1.0, description="Cosine similarity score")
    recommendation_reason: str
    novelty_score: float = Field(default=0.0, ge=0.0, le=1.0, description="Recency + citation novelty signal")
    citation_percentile: float = Field(default=0.0, ge=0.0, le=1.0, description="Percentile rank within age cohort")
    serendipity_flag: bool = Field(default=False, description="True if injected as adjacent-discipline discovery")
    doi: Optional[str] = None
    abstract: Optional[str] = None


class GrantRecommendation(BaseModel):
    title: str
    agency: str
    agency_color: str
    days_left: int
    amount: str
    field: str
    match_score: int = Field(..., ge=0, le=100)
    url: str
    rationale: str
    bayesian_success_probability: float = Field(
        default=0.0, ge=0.0, le=1.0,
        description="Bayesian posterior probability of award success"
    )


class CollaboratorRecommendation(BaseModel):
    id: Optional[str] = None
    name: str
    institution: Optional[str] = None
    field: Optional[str] = None
    synergy_score: float = Field(default=0.0, ge=0.0, le=1.0, description="Jaccard + PageRank synergy signal")
    match_score: int = Field(..., ge=0, le=100)
    depth: int = Field(default=1, description="Co-authorship network depth (1=direct, 2=indirect)")
    team_composition_role: Optional[str] = Field(
        default=None,
        description="Skill gap this collaborator fills for the user's research team"
    )
    connection_path: Optional[str] = None


class RecommendationResponse(BaseModel):
    papers: List[PaperRecommendation]
    grants: List[GrantRecommendation]
    collaborators: List[CollaboratorRecommendation]
    algorithm_version: str = Field(default="hybrid-v2", description="Recommendation engine version")
    cached: bool = Field(default=False, description="True if result was served from cache")


class PeerRecommendation(BaseModel):
    uid: Optional[str] = None
    name: str
    username: Optional[str] = None
    email: Optional[str] = None
    phone: Optional[str] = None
    research_focus: Optional[str] = None
    is_registered: bool
    relevance_score: float = Field(..., ge=0.0, le=1.0)


class PeerInviteLogRequest(BaseModel):
    user_id: str
    peer_email: Optional[str] = None
    peer_phone: Optional[str] = None
    peer_uid: Optional[str] = None
