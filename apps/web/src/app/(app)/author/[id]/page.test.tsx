import { describe, expect, it, vi } from "vitest";
import { http, HttpResponse } from "msw";
import userEvent from "@testing-library/user-event";
import { renderWithProviders, screen, waitFor } from "@/test/render";
import { server } from "@/test/handlers";
import { makeAuthorResponse } from "@/test/fixtures";
import { AuthorDetailContent } from "./page";

vi.mock("next/navigation", () => ({
  useSearchParams: () => new URLSearchParams("name=Ada%20Lovelace"),
  useRouter: () => ({ push: vi.fn() }),
}));

// The Highlights layer's Track button reads auth directly, same as
// ResearcherCard's "start a project" action — mock it out so these tests
// don't need a real AuthProvider tree.
vi.mock("@/lib/hooks/AuthProvider", () => ({
  useAuth: () => ({ user: null }),
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

  it("renders the author name and the Highlights layer once data arrives", async () => {
    server.use(
      http.get(`${API}/search_author`, () =>
        HttpResponse.json(makeAuthorResponse({ display_name: "Grace Hopper", h_index: 51 })),
      ),
    );
    renderWithProviders(<AuthorDetailContent authorId="A5000000001" />);
    expect(await screen.findByText("Grace Hopper")).toBeInTheDocument();
    expect(screen.getByText("Highlights")).toBeInTheDocument();
    // The dense dashboard (decision 0021) is collapsed by default.
    expect(screen.queryByText("Impact Signature")).not.toBeInTheDocument();
  });

  it("expands the Full research dashboard to reveal stat tiles and the radar", async () => {
    server.use(
      http.get(`${API}/search_author`, () =>
        HttpResponse.json(makeAuthorResponse({ display_name: "Grace Hopper", h_index: 51 })),
      ),
    );
    const user = userEvent.setup();
    renderWithProviders(<AuthorDetailContent authorId="A5000000001" />);
    await screen.findByText("Grace Hopper");
    await user.click(screen.getByRole("button", { name: /full research dashboard/i }));
    expect(screen.getByText("H-Index")).toBeInTheDocument();
    expect(screen.getByText("Impact Signature")).toBeInTheDocument();
  });

  it("links publication co-authors, suggested connections, and the ORCID inside the expanded dashboard", async () => {
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
    const user = userEvent.setup();
    renderWithProviders(<AuthorDetailContent authorId="A5000000001" />);

    // ORCID lives on the always-visible Highlights hero, not the collapsible dashboard.
    const orcid = await screen.findByRole("link", { name: /0000-0002-1825-0097/ });
    expect(orcid).toHaveAttribute("href", "https://orcid.org/0000-0002-1825-0097");

    await user.click(screen.getByRole("button", { name: /full research dashboard/i }));

    // co-author in a publication row
    const faraday = await screen.findByRole("link", { name: "Michael Faraday" });
    expect(faraday).toHaveAttribute("href", "/author/A5000000042?name=Michael%20Faraday");

    // suggested-connections row (default mockCollaborators handler → Charles Babbage / A5000000002)
    const conn = await screen.findByRole("link", { name: /babbage/i });
    expect(conn).toHaveAttribute("href", "/author/A5000000002?name=Charles%20Babbage");
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
