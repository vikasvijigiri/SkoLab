import { describe, expect, it, vi, beforeEach } from "vitest";
import { http, HttpResponse } from "msw";
import userEvent from "@testing-library/user-event";
import { renderWithProviders, screen } from "@/test/render";
import { server } from "@/test/handlers";
import { DiscoveryContent } from "./page";

vi.mock("next/navigation", () => ({
  useSearchParams: () => new URLSearchParams(""),
  useRouter: () => ({ push: vi.fn() }),
}));

// ResearcherCard's "start a project with this match" action reads auth
// directly (not through useMyProfile) to decide whether to render.
vi.mock("@/lib/hooks/AuthProvider", () => ({
  useAuth: () => ({ user: null }),
}));

// useMyProfile pulls in AuthProvider + Firestore; mock it and drive resolution.
const profile = vi.hoisted(() => ({
  current: {
    author: null as null | { field_of_study?: string; expertise?: string[]; institution?: string },
    firestoreProfile: null as null | { researchFocus?: string },
    loading: false,
    error: null,
    unresolved: true,
    refetch: vi.fn(),
  },
}));
vi.mock("@/lib/hooks/useMyProfile", () => ({
  useMyProfile: () => profile.current,
}));

const API = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";

beforeEach(() => {
  profile.current = {
    author: null,
    firestoreProfile: null,
    loading: false,
    error: null,
    unresolved: true,
    refetch: vi.fn(),
  };
});

describe("DiscoveryContent — click-only", () => {
  it("has no text inputs and shows the leaderboard for an unresolved viewer", async () => {
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

  it("defaults a resolved viewer straight to the fit-first grid — no click", async () => {
    profile.current = {
      author: {
        field_of_study: "Condensed Matter Physics",
        expertise: ["superconductivity", "spin glasses"],
        institution: "Analytical Engine Institute",
      },
      firestoreProfile: { researchFocus: "Condensed Matter Physics" },
      loading: false,
      error: null,
      unresolved: false,
      refetch: vi.fn(),
    };
    renderWithProviders(<DiscoveryContent />);
    expect(
      (await screen.findAllByText(/Researchers in Condensed Matter Physics/i)).length,
    ).toBeGreaterThan(0);
    // Rendered once in the main grid, and again in the "rising" highlight
    // strip (decisions/0015's trending-persons follow-up) — the fixture's
    // counts_by_year climbs each year, so Ada classifies as rising.
    expect((await screen.findAllByText("Ada Lovelace")).length).toBeGreaterThan(0);
    // fit badge is rendered by ResearcherCard
    expect(await screen.findByText(/^Fit \d+$/)).toBeInTheDocument();
  });

  it("still lets an unresolved viewer drill down to a subfield (explore another area)", async () => {
    const user = userEvent.setup();
    renderWithProviders(<DiscoveryContent />);
    await user.click(await screen.findByRole("button", { name: "Physics and Astronomy" }));
    await user.click(await screen.findByRole("button", { name: "Condensed Matter Physics" }));
    expect(
      (await screen.findAllByText(/Researchers in Condensed Matter Physics/i)).length,
    ).toBeGreaterThan(0);
    expect((await screen.findAllByText("Ada Lovelace")).length).toBeGreaterThan(0);
  });

  it("toggling a filter chip keeps the grid rendered (no crash, no text input)", async () => {
    profile.current = {
      author: { field_of_study: "Condensed Matter Physics", expertise: ["spin glasses"], institution: "X" },
      firestoreProfile: { researchFocus: "Condensed Matter Physics" },
      loading: false,
      error: null,
      unresolved: false,
      refetch: vi.fn(),
    };
    const user = userEvent.setup();
    const { container } = renderWithProviders(<DiscoveryContent />);
    await screen.findAllByText("Ada Lovelace");
    const emerging = await screen.findByRole("button", { name: "Emerging" });
    await user.click(emerging);
    expect(emerging).toHaveAttribute("aria-pressed", "true");
    expect(container.querySelector("input, textarea")).toBeNull();
  });

  it("shows trending topics scoped to the viewer's field, and drills into one on click", async () => {
    profile.current = {
      author: { field_of_study: "Condensed Matter Physics", expertise: ["spin glasses"], institution: "X" },
      firestoreProfile: { researchFocus: "Condensed Matter Physics" },
      loading: false,
      error: null,
      unresolved: false,
      refetch: vi.fn(),
    };
    const user = userEvent.setup();
    renderWithProviders(<DiscoveryContent />);
    await screen.findAllByText("Ada Lovelace");
    await user.click(screen.getByRole("tab", { name: "topics" }));
    expect(await screen.findByText("Spin Liquids")).toBeInTheDocument();
    expect(screen.getByText("+100%")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: /Spin Liquids/i }));
    // Selecting a trending topic drills into its papers.
    expect(await screen.findByText(/Top papers in Spin Liquids/i)).toBeInTheDocument();
  });

  it("surfaces an ErrorBanner with Retry when the default fetch fails", async () => {
    server.use(
      http.get(`${API}/api/v1/leaderboard/:field`, () => new HttpResponse(null, { status: 500 })),
    );
    renderWithProviders(<DiscoveryContent />);
    expect(await screen.findByRole("button", { name: /retry/i })).toBeInTheDocument();
  });
});
