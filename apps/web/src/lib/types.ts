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
  id: string;
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

// Similarity engine (Go gateway internal/similarity — pgvector kNN + graph blend).
export interface SimilarPaper {
  work_id: string;
  title: string;
  authors: string[];
  year: number;
  score: number;
  /** Human explanation, e.g. "83% topical · 4 shared references". */
  why: string;
}

export interface SimilarResearcher {
  author_id: string;
  display_name: string;
  institution: string;
  field_of_study: string;
  h_index: number;
  score: number;
  /** e.g. "same institution · 3 shared collaborators · 79% topical match". */
  why: string;
  shared_collaborators: number;
}

/** Every similarity endpoint wraps its list and flags a degraded (OpenAlex-only) result. */
export interface SimilarResult<T> {
  results: T[];
  degraded: boolean;
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
  /** Distinct from expertise (topic/field tags) — LLM-derived research
   *  skills/tools implied by the researcher's actual papers. */
  skills: string[];
  tools: string[];
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
  works_count: number;
  is_oa: boolean;
  citation_impact: number;
  match_score: number;
  rationale: string;
}

export interface GrantMatch {
  title: string;
  agency: string;
  agency_color: string;
  /** Real days-remaining parsed from a scraped deadline — null when the
   *  source listing has no parseable date (e.g. "Rolling"/"Open Now"). */
  days_left: number | null;
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

// OpenAlex topic taxonomy node (field / subfield / topic) — drives the
// click-only pickers so no option list is hard-coded.
export interface OpenAlexTaxon {
  id: string;
  display_name: string;
}

// OpenAlex author search hit — the "is this you?" onboarding picker.
export interface OpenAlexAuthorHit {
  id: string;
  display_name: string;
  orcid: string | null;
  works_count: number;
  cited_by_count: number;
  h_index: number;
  institution: string;
}

// POST /api/v1/discovery/predict  (Horizon foresight engine)
export interface PaperSource {
  id: string;
  title: string;
  authors: string[];
  year: number;
  cited_by_count: number;
  doi?: string;
}

export interface BreakthroughPrediction {
  breakthrough_name: string;
  description: string;
  scientific_logic: string;
  business_application: string;
  time_horizon: string;
  feasibility: "High" | "Medium" | "Low";
  roadmap_steps: string[];
  pioneering_papers: PaperSource[];
  latest_papers: PaperSource[];
}

// Nexus collection workspace (client-derived from OpenAlexWork) + chat
export interface NexusCollectionPaper {
  id: string;
  title: string;
  authors: string[];
  year: number;
  cited_by_count?: number;
  abstract: string;
  doi?: string;
}

export interface NexusMessage {
  role: "user" | "assistant";
  content: string;
}

/** Trimmed paper shape sent in the POST /api/v1/discovery/nexus-chat body. */
export interface NexusChatPaper {
  title: string;
  authors: string[];
  year: number;
  abstract: string;
}

// Overleaf-style collaborator roles.
export type CollabRole = "owner" | "editor" | "reviewer" | "viewer";

export interface CollabMember {
  uid: string;
  name: string;
  email: string;
  phone?: string;
  /** Absent on rows written before roles existed — treat as "editor". */
  role?: CollabRole;
}

// Firestore collabs_groups/{id}
export interface CollabProject {
  id: string;
  name: string;
  description: string;
  ownerUid: string;
  ownerName: string;
  members: CollabMember[];
  memberUids: string[];
  recentEquations: string;
  manuscriptProgress: number;
  manuscriptDraft: string;
  createdAt?: unknown;
  /** Bumped on any document save; drives the dashboard's "updated" sort. */
  updatedAt?: number;
  updatedByName?: string;
}

// Firestore collabs_groups/{id}/documents/{docId}
export interface CollabDocument {
  id: string;
  title: string;
  body: string;
  order: number;
  updatedAt: number;
  updatedByUid: string;
  updatedByName: string;
}

// Firestore collabs_groups/{id}/presence/{uid} — heartbeat, stale after ~45s.
export interface CollabPresence {
  uid: string;
  name: string;
  docId: string | null;
  lastSeen: number;
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
