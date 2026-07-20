"""
Pydantic schemas for the SkoLab Recommendation System.
Covers peer/collaborator autocomplete for CoLab project invites.
"""

from pydantic import BaseModel, Field
from typing import List, Optional


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


class RegisteredCheckRequest(BaseModel):
    emails: List[str]
    phones: List[str]


class RegisteredCheckResponse(BaseModel):
    registered_emails: List[str]
    registered_phones: List[str]

