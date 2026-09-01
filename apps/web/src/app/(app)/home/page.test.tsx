import { describe, expect, it, vi } from "vitest";
import { http, HttpResponse } from "msw";
import { renderWithProviders, screen } from "@/test/render";
import { server } from "@/test/handlers";
import HomePage from "./page";

vi.mock("@/lib/hooks/AuthProvider", () => ({
  useAuth: () => ({ user: { uid: "u1", displayName: "Ada Lovelace" }, loading: false, configured: true }),
}));

vi.mock("@/lib/hooks/useMyProfile", () => ({
  useMyProfile: () => ({
    firestoreProfile: { name: "Ada Lovelace", researchFocus: "Computing" },
    author: { id: "A1", field_of_study: "Computer Science" },
    loading: false,
    error: null,
    refetch: vi.fn(),
  }),
}));

const API = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";

describe("HomePage", () => {
  it("greets the user and renders the daily-feed recommendations from the API", async () => {
    server.use(
      http.get(`${API}/api/v1/daily_feed`, () =>
        HttpResponse.json([
          {
            id: "W1",
            title: "A very relevant paper",
            authors: ["Ada Lovelace"],
            journal: "J. Computing",
            year: 2025,
            relevance_score: 0.9,
            recommendation_reason: "On topic.",
          },
        ]),
      ),
    );
    renderWithProviders(<HomePage />);
    expect(screen.getByText(/Good to see you, Ada/i)).toBeInTheDocument();
    // Appears in both the feed rail and the AI Daily Brief top-paper row.
    expect((await screen.findAllByText("A very relevant paper")).length).toBeGreaterThan(0);
  });

  it("still renders the shell when the feed API fails", async () => {
    server.use(http.get(`${API}/api/v1/daily_feed`, () => new HttpResponse(null, { status: 500 })));
    renderWithProviders(<HomePage />);
    expect(
      await screen.findByText(/No recommendations yet/i),
    ).toBeInTheDocument();
  });
});
