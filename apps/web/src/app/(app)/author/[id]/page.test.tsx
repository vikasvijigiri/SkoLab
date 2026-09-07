import { describe, expect, it, vi } from "vitest";
import { http, HttpResponse } from "msw";
import userEvent from "@testing-library/user-event";
import { renderWithProviders, screen, waitFor } from "@/test/render";
import { server } from "@/test/handlers";
import { makeAuthorResponse } from "@/test/fixtures";
import { AuthorDetailContent } from "./page";

vi.mock("next/navigation", () => ({
  useSearchParams: () => new URLSearchParams("name=Ada%20Lovelace"),
}));

const API = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";

describe("AuthorDetailContent", () => {
  it("shows the skeleton while the author query is pending", () => {
    server.use(
      http.get(`${API}/search_author`, async () => {
        await new Promise((r) => setTimeout(r, 50));
        return HttpResponse.json(makeAuthorResponse());
      }),
    );
    const { container } = renderWithProviders(<AuthorDetailContent authorId="A5000000001" />);
    expect(container.querySelector(".animate-pulse")).not.toBeNull();
  });

  it("renders the author name, stat tiles and radar once data arrives", async () => {
    server.use(
      http.get(`${API}/search_author`, () =>
        HttpResponse.json(makeAuthorResponse({ display_name: "Grace Hopper", h_index: 51 })),
      ),
    );
    renderWithProviders(<AuthorDetailContent authorId="A5000000001" />);
    expect(await screen.findByText("Grace Hopper")).toBeInTheDocument();
    expect(screen.getByText("H-Index")).toBeInTheDocument();
    expect(screen.getByText("Impact Signature")).toBeInTheDocument();
  });

  it("links publication co-authors, suggested connections, and the ORCID", async () => {
    server.use(
      http.get(`${API}/search_author`, () =>
        HttpResponse.json(
          makeAuthorResponse({
            display_name: "Ada Lovelace",
            orcid: "0000-0002-1825-0097",
            works: [
              {
                id: "W7001",
                title: "Notes on the Analytical Engine",
                year: 1843,
                is_open_access: true,
                citations: 12,
                creativity_score: 0,
                complexity_score: 0,
                impact_factor: 0,
                disruption_score: 0,
                semantic_novelty: 0,
                open_science_score: 0,
                authors: ["Ada Lovelace|https://openalex.org/A5000000001", "Michael Faraday|https://openalex.org/A5000000042"],
              },
            ],
          }),
        ),
      ),
    );
    renderWithProviders(<AuthorDetailContent authorId="A5000000001" />);

    // co-author in a publication row
    const faraday = await screen.findByRole("link", { name: "Michael Faraday" });
    expect(faraday).toHaveAttribute("href", "/author/A5000000042?name=Michael%20Faraday");

    // suggested-connections row (default mockCollaborators handler → Charles Babbage / A5000000002)
    const conn = await screen.findByRole("link", { name: /babbage/i });
    expect(conn).toHaveAttribute("href", "/author/A5000000002?name=Charles%20Babbage");

    // ORCID
    const orcid = screen.getByRole("link", { name: /0000-0002-1825-0097/ });
    expect(orcid).toHaveAttribute("href", "https://orcid.org/0000-0002-1825-0097");
  });

  it("shows an ErrorBanner with Retry on failure and refetches on click", async () => {
    let calls = 0;
    server.use(
      http.get(`${API}/search_author`, () => {
        calls += 1;
        return calls === 1
          ? new HttpResponse(null, { status: 500 })
          : HttpResponse.json(makeAuthorResponse({ display_name: "Second Try" }));
      }),
    );
    const user = userEvent.setup();
    renderWithProviders(<AuthorDetailContent authorId="A5000000001" />);

    const retry = await screen.findByRole("button", { name: /retry/i });
    await user.click(retry);
    await waitFor(() => expect(screen.getByText("Second Try")).toBeInTheDocument());
  });
});
