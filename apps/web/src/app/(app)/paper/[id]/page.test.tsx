import { describe, expect, it } from "vitest";
import { http, HttpResponse } from "msw";
import { renderWithProviders, screen, waitFor } from "@/test/render";
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

  it("renders related papers from the similarity engine, with the why line", async () => {
    server.use(
      http.get("*/api/openalex/works/:id", () =>
        HttpResponse.json({ id: "W1", display_name: "On Computable Numbers" }),
      ),
      http.get(`${API}/api/v1/similar_papers`, () =>
        HttpResponse.json({
          results: [
            {
              work_id: "W99",
              title: "A closely related result",
              authors: ["Grace Hopper"],
              year: 1952,
              score: 0.9,
              why: "90% topical · 5 shared references",
            },
          ],
          degraded: false,
        }),
      ),
    );
    renderWithProviders(<PaperDetailContent id="W1" />);
    expect(await screen.findByText("A closely related result")).toBeInTheDocument();
    expect(screen.getByText("90% topical · 5 shared references")).toBeInTheDocument();
  });

  it("hides the related-papers section when the engine returns nothing", async () => {
    server.use(
      http.get("*/api/openalex/works/:id", () =>
        HttpResponse.json({ id: "W1", display_name: "On Computable Numbers" }),
      ),
      http.get(`${API}/api/v1/similar_papers`, () =>
        HttpResponse.json({ results: [], degraded: false }),
      ),
    );
    renderWithProviders(<PaperDetailContent id="W1" />);
    expect(await screen.findByText("On Computable Numbers")).toBeInTheDocument();
    await waitFor(() =>
      expect(screen.queryByText("Related papers")).not.toBeInTheDocument(),
    );
  });
});
