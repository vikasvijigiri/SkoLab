import { describe, expect, it } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { TrendingResearchersStrip } from "./TrendingResearchersStrip";
import type { ResearcherResult, ResearcherSignals } from "@/lib/types";

const signals = (): ResearcherSignals => ({
  activity: "active",
  momentum: "rising",
  momentumScore: 0.4,
  yearsActiveVisible: 8,
  careerStage: "emerging",
  activeDecades: [2020],
  topicalFocus: 0.5,
  standingPercentile: 70,
  sparkline: [1, 2, 4],
});

const researcher = (id: string, name: string): ResearcherResult =>
  ({
    id,
    display_name: name,
    orcid: null,
    institution: "Analytical Engine Institute",
    country: "GB",
    instType: "education",
    hIndex: 12,
    i10: 6,
    worksCount: 20,
    citedBy: 300,
    twoYrMean: 3,
    topics: [],
    signals: signals(),
  }) as ResearcherResult;

describe("TrendingResearchersStrip", () => {
  it("renders nothing when there are no rising researchers", () => {
    const { container } = renderWithProviders(
      <TrendingResearchersStrip researchers={[]} scopeLabel="Condensed Matter Physics" />,
    );
    expect(container.firstChild).toBeNull();
  });

  it("shows the scope label and a card per researcher, linking to their author page", () => {
    renderWithProviders(
      <TrendingResearchersStrip
        researchers={[researcher("A1", "Ada Lovelace")]}
        scopeLabel="Condensed Matter Physics"
      />,
    );
    expect(screen.getByText(/Rising in Condensed Matter Physics/i)).toBeInTheDocument();
    const link = screen.getByRole("link", { name: /Ada Lovelace/i });
    expect(link).toHaveAttribute("href", expect.stringContaining("/author/A1"));
  });
});
