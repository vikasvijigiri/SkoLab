import type {
  AuthorResponse,
  AuthorSuggestion,
  CitationHeatmap,
  DailyFeedItem,
  JournalRecommendation,
  NetworkCollaborator,
} from "@/lib/types";

export const mockSuggestion: AuthorSuggestion = {
  id: "A5000000001",
  display_name: "Ada Lovelace",
  institution: "Analytical Engine Institute",
  field_of_study: "Computer Science",
  h_index: 42,
  works_count: 120,
};

export function makeAuthorResponse(overrides: Partial<AuthorResponse> = {}): AuthorResponse {
  return {
    id: "A5000000001",
    display_name: "Ada Lovelace",
    h_index: 42,
    i10_index: 30,
    works_count: 120,
    cited_by_count: 9001,
    institution: "Analytical Engine Institute",
    field_of_study: "Computer Science",
    expertise: ["Programming", "Mathematics"],
    skills: ["Algorithm design"],
    tools: ["Difference Engine"],
    academic_history: ["1843 — first published algorithm"],
    works: [],
    innovation_score: 88,
    metrics_computed: true,
    llm_active: false,
    average_creativity: 70,
    average_complexity: 65,
    average_skill_score: 72,
    average_impact: 60,
    average_activity: 55,
    disruption_score: 40,
    citation_acceleration: 12,
    future_impact_score: 80,
    network_centrality: 50,
    semantic_novelty: 62,
    interdisciplinary_index: 45,
    policy_patent_score: 20,
    open_science_score: 75,
    collaboration_diversity: 58,
    research_consistency: 68,
    similar_researchers: [mockSuggestion],
    ...overrides,
  };
}

export const mockCollaborators: NetworkCollaborator[] = [
  {
    id: "A5000000002",
    name: "Charles Babbage",
    institution: "Analytical Engine Institute",
    field: "Computer Science",
    connection_path: "co-authored 3 works",
    relevance_score: 0.91,
    h_index: 38,
  },
];

export const mockHeatmap: CitationHeatmap = {
  years: [2021, 2022, 2023],
  citations: [100, 180, 260],
  works: [5, 8, 11],
  institutional_reach: 12,
  h_index: 42,
};

export const mockJournals: JournalRecommendation[] = [
  {
    journal_name: "Journal of Analytical Computation",
    works_count: 320,
    is_oa: true,
    citation_impact: 6.4,
    match_score: 0.82,
    rationale: "Strong topical overlap with recent works.",
  },
];

export const mockDailyFeed: DailyFeedItem[] = [
  {
    id: "W1",
    title: "On the notation of the Analytical Engine",
    authors: ["Ada Lovelace"],
    journal: "Taylor's Scientific Memoirs",
    year: 1843,
    relevance_score: 0.95,
    recommendation_reason: "Matches your core research area.",
  },
];
