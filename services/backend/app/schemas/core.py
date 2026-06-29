from pydantic import BaseModel, Field
from typing import List, Optional


class Work(BaseModel):
    id: Optional[str] = None
    title: Optional[str] = None
    year: Optional[int] = None
    doi: Optional[str] = None
    journal: Optional[str] = None  # journal / venue name
    is_open_access: bool = False
    citations: int = 0
    creativity_score: float = 0.0
    complexity_score: float = 0.0
    impact_factor: float = 0.0
    disruption_score: float = 0.0
    semantic_novelty: float = 0.0
    open_science_score: float = 0.0
    authors: Optional[List[str]] = None


class AuthorSuggestion(BaseModel):
    id: str
    display_name: str
    institution: str
    field_of_study: Optional[str] = None
    h_index: Optional[int] = None
    innovation_score: Optional[int] = None
    works_count: Optional[int] = None


class AuthorResponse(BaseModel):
    id: str
    display_name: str
    orcid: Optional[str] = None
    h_index: int
    i10_index: int
    works_count: int
    cited_by_count: int
    institution: str
    field_of_study: Optional[str] = None
    expertise: List[str] = []
    academic_history: List[str]
    works: List[Work]
    innovation_score: Optional[float] = None
    metrics_computed: bool = False
    llm_active: bool = True
    average_creativity: float = 0.0
    average_complexity: float = 0.0
    average_skill_score: float = 0.0
    average_impact: float = 0.0
    average_activity: float = 0.0
    disruption_score: float = 0.0
    citation_acceleration: float = 0.0
    future_impact_score: float = 0.0
    network_centrality: float = 0.0
    semantic_novelty: float = 0.0
    interdisciplinary_index: float = 0.0
    policy_patent_score: float = 0.0
    open_science_score: float = 0.0
    collaboration_diversity: float = 0.0
    research_consistency: float = 0.0
    next_prediction: Optional[str] = None
    similar_researchers: List[AuthorSuggestion] = []


class PaperIntelligenceResponse(BaseModel):
    tldr: str = ""
    key_findings: List[str] = []
    techniques: List[str] = []
    tools_and_software: List[str] = []
    core_concepts: List[str] = []
    formulas: List[str] = []
    limitations: List[str] = []
    real_world_impact: str = ""
    future_directions: List[str] = []
    confidence: str = "Medium"
    text_source: str = "abstract_only"


class ChatMessage(BaseModel):
    role: str
    content: str


class ChatRequest(BaseModel):
    author_id: str
    paper_title: str
    user_message: str = Field(..., max_length=2000)
    history: List[ChatMessage] = []


class ConjectureResponse(BaseModel):
    id: str
    category: str
    title: str
    hypothesis: str
    options: List[str]
    correctOptionIndex: int
    explanation: str





class AgentChatRequest(BaseModel):
    message: str = Field(..., max_length=2000)
    history: list[dict[str, str]] = []
    mode: str = "RESEARCH"
    user_memory: Optional[dict] = None


class ActivityEventPayload(BaseModel):
    type: str
    timestamp: Optional[int] = None
    hourOfDay: Optional[int] = None
    paperTitle: Optional[str] = None
    paperDomain: Optional[str] = None
    paperJournal: Optional[str] = None
    durationSeconds: Optional[int] = None
    authorName: Optional[str] = None
    authorInstitution: Optional[str] = None
    query: Optional[str] = None
    filterValue: Optional[str] = None
    collaboratorName: Optional[str] = None
    collaboratorField: Optional[str] = None


class UserMemoryEventsRequest(BaseModel):
    user_id: str
    events: List[ActivityEventPayload]


class UserMemoryProfileResponse(BaseModel):
    user_id: str
    top_topics: List[str] = []
    active_hours: List[int] = []
    reading_pace: str = "unknown"
    research_style: str = "unknown"
    avg_read_minutes: float = 0.0
    unfinished_papers: List[str] = []
    recently_read_papers: List[str] = []
    frequent_collaborators: List[str] = []
    frequent_search_terms: List[str] = []
    last_active_topic: str = ""
    total_papers_read: int = 0
    researcher_bio: Optional[str] = None
    last_updated: int = 0


class AcademicPaperInfo(BaseModel):
    id: str
    title: str
    doi: Optional[str] = None
    year: Optional[int] = None
    journal: Optional[str] = None
    citations: int = 0
    authors: List[str] = []


class TieupIdea(BaseModel):
    title: str
    description: str
    search_queries: List[str] = []
    papers: List[AcademicPaperInfo] = []


class IndustryAcademicTieupsResponse(BaseModel):
    trending: List[TieupIdea] = []
    futuristic: List[TieupIdea] = []

