import { describe, expect, it } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { mockSimilarResearchers } from "@/test/fixtures";
import { PeerSuggestionsCard } from "./PeerSuggestionsCard";

describe("PeerSuggestionsCard", () => {
  it("renders each peer with its why line", () => {
    renderWithProviders(
      <PeerSuggestionsCard peers={mockSimilarResearchers} loading={false} />,
    );
    expect(screen.getByText("Grace Hopper")).toBeInTheDocument();
    expect(
      screen.getByText("same institution · 2 shared collaborators · 79% topical match"),
    ).toBeInTheDocument();
  });

  it("shows the connect-your-work prompt when unresolved", () => {
    renderWithProviders(<PeerSuggestionsCard peers={[]} loading={false} unresolved />);
    expect(screen.getByText(/connect your work/i)).toBeInTheDocument();
  });

  it("shows skeletons while loading", () => {
    const { container } = renderWithProviders(
      <PeerSuggestionsCard peers={[]} loading />,
    );
    expect(container.querySelectorAll(".animate-pulse").length).toBeGreaterThan(0);
  });
});
