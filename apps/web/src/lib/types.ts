// Firestore `researchers/{uid}` document — mirrors Android's SkoLabUser.kt
export interface SkoLabUser {
  uid: string;
  name: string;
  username: string;
  authorName: string;
  email: string;
  phone: string;
  researchFocus: string;
  complexityScore: number;
  savedPapers: string[];
  isOnline: boolean;
  emailVerified: boolean;
  academicStatus: string;
  cvUri: string;
  cvFileName: string;
  about: string;
  openAlexId: string;
  lastActive: number;
}

// GET /api/v1/leaderboard/:field (Go gateway)
export interface LeaderboardEntry {
  rank: number;
  user_name: string;
  institution: string;
  entropy_score: number;
}

// GET /api/v1/author_suggestions, /network_collaborators similar-researcher entries
export interface AuthorSuggestion {
  id: string;
  display_name: string;
  institution: string;
  field_of_study?: string;
  h_index?: number;
  innovation_score?: number;
  works_count?: number;
}

export interface Work {
  id?: string;
  title?: string;
  year?: number;
  doi?: string;
  journal?: string;
  is_open_access: boolean;
  citations: number;
  creativity_score: number;
  complexity_score: number;
  impact_factor: number;
  disruption_score: number;
  semantic_novelty: number;
  open_science_score: number;
  authors?: string[];
}

// GET /search_author
export interface AuthorResponse {
  id: string;
  display_name: string;
  orcid?: string;
  h_index: number;
  i10_index: number;
  works_count: number;
  cited_by_count: number;
  institution: string;
  field_of_study?: string;
  expertise: string[];
  academic_history: string[];
  works: Work[];
  innovation_score?: number;
  metrics_computed: boolean;
  llm_active: boolean;
  average_creativity: number;
  average_complexity: number;
  average_skill_score: number;
  average_impact: number;
  average_activity: number;
  disruption_score: number;
  citation_acceleration: number;
  future_impact_score: number;
  network_centrality: number;
  semantic_novelty: number;
  interdisciplinary_index: number;
  policy_patent_score: number;
  open_science_score: number;
  collaboration_diversity: number;
  research_consistency: number;
  next_prediction?: string;
  similar_researchers: AuthorSuggestion[];
}

export interface NetworkCollaborator {
  id: string;
  name: string;
  institution: string;
  field: string;
  connection_path: string;
  relevance_score: number;
  papers_collaborated?: number;
  total_publications?: number;
  h_index?: number;
}

export interface CitationHeatmap {
  years: number[];
  citations: number[];
  works: number[];
  institutional_reach: number;
  h_index: number;
}

export interface JournalRecommendation {
  journal_name: string;
  estimated_impact_factor: number;
  match_score: number;
  submission_tips: string;
}

export interface GrantMatch {
  title: string;
  agency: string;
  agency_color: string;
  days_left: number;
  amount: string;
  field: string;
  match_score: number;
  url: string;
  rationale: string;
}

// GET /daily_feed
export interface DailyFeedItem {
  id: string;
  title: string;
  authors: string[];
  journal: string;
  year: number;
  publication_date?: string;
  relevance_score: number;
  recommendation_reason: string;
  doi?: string;
  abstract?: string;
  methodology?: string;
  tools_used?: string[];
  key_findings?: string;
}

// GET /daily_conjecture
export interface Conjecture {
  id: string;
  category: string;
  title: string;
  hypothesis: string;
  options: string[];
  correctOptionIndex: number;
  explanation: string;
}

// GET /industry_opportunities
export interface IndustryOpportunity {
  id: string;
  type: "JOB" | "FUNDING" | "REQUIREMENT";
  title: string;
  companyOrFunder: string;
  tags: string[];
  description: string;
  postedAgo?: string;
  url?: string;
  eligibility?: string;
  amount?: string;
  procedureSteps?: string[];
  deadline?: string;
  status?: string;
  requiredSkills?: string[];
  matchScore?: number;
  relevanceExplanation?: string;
  location?: string;
  positionLevel?: string;
  remoteType?: string;
}

// GET /analyze_paper
export interface PaperIntelligence {
  tldr: string;
  key_findings: string[];
  techniques: string[];
  tools_and_software: string[];
  core_concepts: string[];
  formulas: string[];
  limitations: string[];
  real_world_impact: string;
  future_directions: string[];
  confidence: "High" | "Medium" | "Low";
  text_source: string;
}

// GET /api/openalex/works (server proxy of https://api.openalex.org/works)
export interface OpenAlexWork {
  id: string;
  display_name: string;
  publication_year?: number;
  doi?: string;
  cited_by_count?: number;
  primary_location?: { source?: { display_name?: string } };
  authorships?: { author: { display_name: string } }[];
  abstract_inverted_index?: Record<string, number[]>;
}

// GET /api/v1/recommendations/peers
export interface PeerRecommendation {
  uid?: string;
  name: string;
  username?: string;
  email?: string;
  phone?: string;
  research_focus?: string;
  is_registered: boolean;
  relevance_score: number;
}

// Firestore collabs_groups/{id}
export interface CollabProject {
  id: string;
  name: string;
  description: string;
  ownerUid: string;
  ownerName: string;
  members: { uid: string; name: string; email: string; phone?: string }[];
  memberUids: string[];
  recentEquations: string;
  manuscriptProgress: number;
  manuscriptDraft: string;
  createdAt?: unknown;
}

export interface CollabMessage {
  id: string;
  senderUid: string;
  senderName: string;
  text: string;
  timestamp: number;
}

export interface CollabTask {
  id: string;
  title: string;
  isCompleted: boolean;
  assignee?: string;
}

export interface CollabMeeting {
  id: string;
  title: string;
  when: string;
  timestamp: number;
}
