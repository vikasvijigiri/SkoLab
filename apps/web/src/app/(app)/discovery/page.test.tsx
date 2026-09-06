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

describe("DiscoveryContent — click-only", () => {
  it("has no text inputs and shows the leaderboard by default", async () => {
    server.use(
      http.get(`${API}/api/v1/leaderboard/:field`, () =>
        HttpResponse.json([
          { rank: 1, id: "A1", user_name: "Ada Lovelace", institution: "AEI", entropy_score: 99 },
        ]),
      ),
    );
    const { container } = renderWithProviders(<DiscoveryContent />);
    expect(await screen.findByText("Ada Lovelace")).toBeInTheDocument();
    expect(screen.getByText("Top Researchers")).toBeInTheDocument();
    expect(container.querySelector("input, textarea")).toBeNull();
  });

  it("drills down field → sub-field by clicking chips and shows node results", async () => {
    const user = userEvent.setup();
    renderWithProviders(<DiscoveryContent />);

    // Field chips come from the taxonomy handler.
    await user.click(await screen.findByRole("button", { name: "Physics and Astronomy" }));
    // Sub-field chip appears; picking it switches results to the node view.
    await user.click(await screen.findByRole("button", { name: "Condensed Matter Physics" }));

    expect(
      await screen.findByText(/Top researchers in Condensed Matter Physics/i),
    ).toBeInTheDocument();
    // The authors handler returns Ada Lovelace for any taxon query.
    expect(await screen.findByText("Ada Lovelace")).toBeInTheDocument();
  });

  it("surfaces an ErrorBanner with Retry when the default fetch fails", async () => {
    server.use(
      http.get(`${API}/api/v1/leaderboard/:field`, () => new HttpResponse(null, { status: 500 })),
    );
    renderWithProviders(<DiscoveryContent />);
    expect(await screen.findByRole("button", { name: /retry/i })).toBeInTheDocument();
  });
});
