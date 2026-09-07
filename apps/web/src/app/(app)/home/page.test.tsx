import { describe, expect, it, vi, beforeEach } from "vitest";
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
    unresolved: false,
    refetch: vi.fn(),
  }),
}));

const API = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";

beforeEach(() => {
  try {
    localStorage.clear();
  } catch {
    /* ignore */
  }
});

describe("HomePage", () => {
  it("greets the user and blends recommended papers into the unified feed", async () => {
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
    expect((await screen.findAllByText("A very relevant paper")).length).toBeGreaterThan(0);
  });

  it("blends network activity and science news into the same feed", async () => {
    renderWithProviders(<HomePage />);
    // activity paper_published title is wrapped: `X published "…"`.
    expect(await screen.findByText(/Compilers for the analytical engine/)).toBeInTheDocument();
    expect(screen.getByText(/is now connected with you/i)).toBeInTheDocument();
    // science news headline appears as a feed card.
    expect(
      await screen.findByText("A new state of matter observed in a spin liquid"),
    ).toBeInTheDocument();
  });

  it("exposes the lens filter with a default 'For you'", async () => {
    renderWithProviders(<HomePage />);
    expect(await screen.findByRole("button", { name: "For you" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Papers" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Roles" })).toBeInTheDocument();
  });

  it("still renders the shell when the recommendations API fails", async () => {
    server.use(http.get(`${API}/api/v1/daily_feed`, () => new HttpResponse(null, { status: 500 })));
    renderWithProviders(<HomePage />);
    expect(screen.getByText(/Good to see you, Ada/i)).toBeInTheDocument();
    // Other sources still populate the feed.
    expect(
      await screen.findByText(/Compilers for the analytical engine/),
    ).toBeInTheDocument();
  });
});
