import { describe, expect, it, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { renderWithProviders, screen } from "@/test/render";
import { CoachPulseCard } from "./CoachPulseCard";
import type { CoachPulseImpact, CoachPulseTrackedActivity, CoachPulseWorthTracking, GrantMatch } from "@/lib/types";

const impact: CoachPulseImpact = {
  new_citations: 3,
  paper_title: "Deep Learning for X",
  paper_id: "W7206172422",
};

const trackedActivity: CoachPulseTrackedActivity = {
  author_id: "A1",
  author_name: "Elena Vasquez",
  work_title: "Topological Defects in Twisted Bilayer Graphene",
  work_id: "W2",
  published_at: "2026-09-10",
};

const worthTracking: CoachPulseWorthTracking = {
  author_id: "A3",
  author_name: "Priya Nathan",
  institution: "MIT",
  works_count: 12,
};

const topGrant: GrantMatch = {
  title: "NSF CAREER Award",
  agency: "NSF",
  agency_color: "var(--accent-orange)",
  days_left: 12,
  amount: "$500,000",
  field: "Physics",
  match_score: 87,
  url: "https://example.com/grant",
  rationale: "",
};

describe("CoachPulseCard", () => {
  it("shows the honest empty state when every signal is absent", () => {
    renderWithProviders(
      <CoachPulseCard
        impact={null}
        trackedActivity={null}
        worthTracking={null}
        topGrant={undefined}
        careerStage={undefined}
        loading={false}
        onTrack={vi.fn()}
      />,
    );
    expect(screen.getByText(/you.re caught up/i)).toBeInTheDocument();
    expect(screen.getByText(/nothing new since your last visit/i)).toBeInTheDocument();
  });

  it("renders all four signals with real data, no fabricated stance/count copy", () => {
    renderWithProviders(
      <CoachPulseCard
        impact={impact}
        trackedActivity={trackedActivity}
        worthTracking={worthTracking}
        topGrant={topGrant}
        careerStage={undefined}
        loading={false}
        onTrack={vi.fn()}
      />,
    );
    expect(screen.getByText("3")).toBeInTheDocument();
    expect(screen.getByText(/Deep Learning for X/)).toBeInTheDocument();
    expect(screen.getByText(/Elena Vasquez published new work/i)).toBeInTheDocument();
    expect(screen.getByText(/Priya Nathan/)).toBeInTheDocument();
    expect(screen.getByText(/NSF CAREER Award/)).toBeInTheDocument();
    expect(screen.getByText("87%")).toBeInTheDocument();
    // The honest-signal rule: no fabricated citation-stance breakdown.
    expect(screen.queryByText(/build.*method|contest/i)).not.toBeInTheDocument();
  });

  it("fires onTrack with the worth-tracking candidate's id and name", async () => {
    const user = userEvent.setup();
    const onTrack = vi.fn();
    renderWithProviders(
      <CoachPulseCard
        impact={null}
        trackedActivity={null}
        worthTracking={worthTracking}
        topGrant={undefined}
        careerStage={undefined}
        loading={false}
        onTrack={onTrack}
      />,
    );
    await user.click(screen.getByRole("button", { name: /track/i }));
    expect(onTrack).toHaveBeenCalledWith("A3", "Priya Nathan");
  });

  it("leads with the grant for a Postdoc", () => {
    const { container } = renderWithProviders(
      <CoachPulseCard
        impact={impact}
        trackedActivity={trackedActivity}
        worthTracking={worthTracking}
        topGrant={topGrant}
        careerStage="Postdoc"
        loading={false}
        onTrack={vi.fn()}
      />,
    );
    expect(container.querySelector("section > *:nth-child(2)")?.textContent).toContain("NSF CAREER Award");
  });

  it("leads with the peer suggestion for a PhD Student", () => {
    const { container } = renderWithProviders(
      <CoachPulseCard
        impact={impact}
        trackedActivity={trackedActivity}
        worthTracking={worthTracking}
        topGrant={topGrant}
        careerStage="PhD Student"
        loading={false}
        onTrack={vi.fn()}
      />,
    );
    expect(container.querySelector("section > *:nth-child(2)")?.textContent).toContain("Priya Nathan");
  });

  it("shows loading skeletons, not the empty state, while loading", () => {
    const { container } = renderWithProviders(
      <CoachPulseCard
        impact={null}
        trackedActivity={null}
        worthTracking={null}
        topGrant={undefined}
        careerStage={undefined}
        loading
        onTrack={vi.fn()}
      />,
    );
    expect(screen.queryByText(/you.re caught up/i)).not.toBeInTheDocument();
    expect(container.querySelectorAll(".animate-pulse").length).toBeGreaterThan(0);
  });
});
