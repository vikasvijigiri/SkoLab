import { http, HttpResponse } from "msw";
import { setupServer } from "msw/node";
import {
  makeAuthorResponse,
  mockCollaborators,
  mockDailyFeed,
  mockHeatmap,
  mockJournals,
  mockSuggestion,
  mockBreakthroughPrediction,
  mockGrants,
  mockIndustryOpportunities,
  mockOpenAlexWorks,
  mockPaperIntelligence,
  mockSimilarPapers,
  mockSimilarResearchers,
  mockActivityFeed,
  mockScienceNews,
} from "./fixtures";

const API = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";

/** Default happy-path handlers. A test overrides one with `server.use(...)`. */
export const handlers = [
  http.get(`${API}/api/v1/author_suggestions`, () => HttpResponse.json([mockSuggestion])),
  http.get(`${API}/search_author`, () => HttpResponse.json(makeAuthorResponse())),
  http.get(`${API}/refresh_author`, () => HttpResponse.json(makeAuthorResponse())),
  http.get(`${API}/network_collaborators`, () => HttpResponse.json(mockCollaborators)),
  http.get(`${API}/citation_heatmap`, () => HttpResponse.json(mockHeatmap)),
  http.get(`${API}/journal_advisor`, () => HttpResponse.json(mockJournals)),
  http.get(`${API}/api/v1/daily_feed`, () => HttpResponse.json(mockDailyFeed)),
  http.post(`${API}/api/v1/daily_feed/dismiss`, () => HttpResponse.json({ success: true })),
  http.get(`${API}/api/v1/daily_conjecture`, () =>
    HttpResponse.json({
      id: "C1",
      category: "Physics",
      title: "A daily puzzle",
      hypothesis: "If X then Y.",
      options: ["A", "B"],
      correctOptionIndex: 0,
      explanation: "Because Z.",
    }),
  ),
  http.get("https://api.openalex.org/*", () => HttpResponse.json({ results: [] })),

  // Phase 2 pages
  http.get(`${API}/api/v1/industry_opportunities`, () => HttpResponse.json(mockIndustryOpportunities)),
  http.get(`${API}/match_grants`, () => HttpResponse.json(mockGrants)),
  http.get(`${API}/api/v1/analyze_paper`, () => HttpResponse.json(mockPaperIntelligence)),
  http.get(`${API}/api/v1/similar_papers`, () =>
    HttpResponse.json({ results: mockSimilarPapers, degraded: false }),
  ),
  http.get(`${API}/api/v1/similar_researchers`, () =>
    HttpResponse.json({ results: mockSimilarResearchers, degraded: false }),
  ),
  http.get(`${API}/api/v1/activity_feed`, () =>
    HttpResponse.json({ items: mockActivityFeed, degraded: false }),
  ),
  http.get(`${API}/api/v1/science_news`, () => HttpResponse.json({ items: mockScienceNews })),
  http.post(`${API}/api/v1/discovery/predict`, () => HttpResponse.json(mockBreakthroughPrediction)),
  http.post(`${API}/api/v1/discovery/nexus-chat`, () =>
    HttpResponse.json({ content: "Synthesized answer." }),
  ),
  // Same-origin Next route handlers (not the gateway) — match any host.
  http.get("*/api/openalex/works", () => HttpResponse.json(mockOpenAlexWorks)),
  http.get("*/api/openalex/taxonomy", ({ request }) => {
    const kind = new URL(request.url).searchParams.get("kind");
    const by: Record<string, { id: string; display_name: string }[]> = {
      fields: [
        { id: "11", display_name: "Engineering" },
        { id: "31", display_name: "Physics and Astronomy" },
      ],
      subfields: [{ id: "3104", display_name: "Condensed Matter Physics" }],
      topics: [
        { id: "T10001", display_name: "Superconductivity" },
        { id: "T10002", display_name: "Topological materials" },
      ],
    };
    return HttpResponse.json(by[kind ?? "fields"] ?? []);
  }),
  http.get("*/api/openalex/authors", () =>
    HttpResponse.json([
      {
        id: "A5000000001",
        display_name: "Ada Lovelace",
        orcid: "0000-0002-1825-0097",
        works_count: 120,
        cited_by_count: 9001,
        h_index: 42,
        institution: "Analytical Engine Institute",
      },
    ]),
  ),
];

export const server = setupServer(...handlers);
