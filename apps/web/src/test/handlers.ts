import { http, HttpResponse } from "msw";
import { setupServer } from "msw/node";
import {
  makeAuthorResponse,
  mockCollaborators,
  mockDailyFeed,
  mockHeatmap,
  mockJournals,
  mockSuggestion,
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
];

export const server = setupServer(...handlers);
