import { describe, expect, it } from "vitest";
import { http, HttpResponse } from "msw";
import { renderWithProviders, screen } from "@/test/render";
import { server } from "@/test/handlers";
import { mockPaperIntelligence } from "@/test/fixtures";
import { PaperDetailContent } from "./page";

const API = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";

describe("PaperDetailContent", () => {
  it("renders the paper title once the work record loads", async () => {
    server.use(
      http.get("*/api/openalex/works/:id", () =>
        HttpResponse.json({ id: "W1", display_name: "On Computable Numbers", publication_year: 1936 }),
      ),
    );
    renderWithProviders(<PaperDetailContent id="W1" />);
    expect(await screen.findByText("On Computable Numbers")).toBeInTheDocument();
  });

  it("shows the analysis tldr after the intelligence query resolves", async () => {
    server.use(
      http.get("*/api/openalex/works/:id", () =>
        HttpResponse.json({ id: "W1", display_name: "On Computable Numbers" }),
      ),
      http.get(`${API}/api/v1/analyze_paper`, () =>
        HttpResponse.json({ ...mockPaperIntelligence, tldr: "A foundational result." }),
      ),
    );
    renderWithProviders(<PaperDetailContent id="W1" />);
    expect(await screen.findByText("A foundational result.")).toBeInTheDocument();
  });

  it("shows a retryable error when the work record fails to load", async () => {
    server.use(http.get("*/api/openalex/works/:id", () => new HttpResponse(null, { status: 404 })));
    renderWithProviders(<PaperDetailContent id="W1" />);
    expect(await screen.findByRole("button", { name: /retry/i })).toBeInTheDocument();
  });
});
