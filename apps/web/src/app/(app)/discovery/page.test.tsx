import { describe, expect, it, vi } from "vitest";
import { http, HttpResponse } from "msw";
import userEvent from "@testing-library/user-event";
import { renderWithProviders, screen } from "@/test/render";
import { server } from "@/test/handlers";
import { DiscoveryContent } from "./page";

vi.mock("next/navigation", () => ({
  useSearchParams: () => new URLSearchParams(""),
}));

const API = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";

describe("DiscoveryContent", () => {
  it("shows the leaderboard by default (researchers mode, no query)", async () => {
    server.use(
      http.get(`${API}/api/v1/leaderboard/:field`, () =>
        HttpResponse.json([
          { rank: 1, id: "A1", user_name: "Ada Lovelace", institution: "AEI", entropy_score: 99 },
        ]),
      ),
    );
    renderWithProviders(<DiscoveryContent />);
    expect(await screen.findByText("Ada Lovelace")).toBeInTheDocument();
    expect(screen.getByText("Top Researchers")).toBeInTheDocument();
  });

  it("runs an author search once the query passes 3 chars", async () => {
    server.use(
      http.get(`${API}/api/v1/author_suggestions`, () =>
        HttpResponse.json([
          { id: "A9", display_name: "Grace Hopper", institution: "USN", h_index: 40 },
        ]),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<DiscoveryContent />);
    await user.type(screen.getByPlaceholderText(/search researchers/i), "grace");
    expect(await screen.findByText("Grace Hopper")).toBeInTheDocument();
  });

  it("surfaces an ErrorBanner with Retry when the default fetch fails", async () => {
    server.use(
      http.get(`${API}/api/v1/leaderboard/:field`, () => new HttpResponse(null, { status: 500 })),
    );
    renderWithProviders(<DiscoveryContent />);
    expect(await screen.findByRole("button", { name: /retry/i })).toBeInTheDocument();
  });
});
