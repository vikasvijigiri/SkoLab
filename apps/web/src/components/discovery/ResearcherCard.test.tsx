import { describe, expect, it, vi, beforeEach } from "vitest";
import userEvent from "@testing-library/user-event";
import { renderWithProviders, screen } from "@/test/render";
import { ResearcherCard } from "./ResearcherCard";
import type { ResearcherResult, ResearcherSignals } from "@/lib/types";

const push = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push }),
}));

const auth = vi.hoisted(() => ({ user: null as null | { uid: string } }));
vi.mock("@/lib/hooks/AuthProvider", () => ({
  useAuth: () => ({ user: auth.user }),
}));

const signals = (over: Partial<ResearcherSignals> = {}): ResearcherSignals => ({
  activity: "active",
  momentum: "rising",
  momentumScore: 0.4,
  yearsActiveVisible: 12,
  careerStage: "established",
  activeDecades: [2010, 2020],
  topicalFocus: 0.66,
  standingPercentile: 90,
  sparkline: [10, 20, 30, 45],
  ...over,
});

const result = (over: Partial<ResearcherResult> = {}): ResearcherResult => ({
  id: "A100",
  display_name: "Ada Lovelace",
  orcid: "0000-0001-2345-6789",
  institution: "Analytical Engine Lab",
  country: "GB",
  instType: "education",
  hIndex: 41,
  i10: 60,
  worksCount: 120,
  citedBy: 5000,
  twoYrMean: 4,
  topics: ["Computing", "Algorithms"],
  signals: signals(),
  fit: { score: 78, why: "3 shared topics · same institution" },
  ...over,
});

describe("ResearcherCard", () => {
  beforeEach(() => {
    auth.user = null;
    push.mockClear();
  });

  it("renders identity, fit score and the why line", () => {
    renderWithProviders(<ResearcherCard r={result()} index={0} />);
    expect(screen.getByText("Ada Lovelace")).toBeInTheDocument();
    expect(screen.getByText("Fit 78")).toBeInTheDocument();
    expect(screen.getByText(/3 shared topics · same institution/)).toBeInTheDocument();
    expect(screen.getByLabelText("ORCID-verified identity")).toBeInTheDocument();
  });

  it("shows top-% standing when a percentile is present, raw H- when not", () => {
    const { rerender } = renderWithProviders(
      <ResearcherCard r={result({ signals: signals({ standingPercentile: 90 }) })} index={0} />,
    );
    expect(screen.getByText("top 10%")).toBeInTheDocument();
    rerender(
      <ResearcherCard r={result({ signals: signals({ standingPercentile: null }) })} index={0} />,
    );
    expect(screen.getByText("H-41")).toBeInTheDocument();
  });

  it("marks a deceased researcher in memoriam and uses no danger colour", () => {
    const { container } = renderWithProviders(
      <ResearcherCard r={result({ deceased: { year: 1852 } })} index={0} />,
    );
    expect(screen.getByText(/in memoriam/i)).toBeInTheDocument();
    expect(screen.getByTitle("Deceased 1852")).toBeInTheDocument();
    expect(container.innerHTML).not.toContain("--danger");
  });

  it("shows the collaboration chip only when the flag is true", () => {
    const { rerender } = renderWithProviders(<ResearcherCard r={result()} index={0} />);
    expect(screen.queryByText(/open to collaboration/i)).not.toBeInTheDocument();
    rerender(<ResearcherCard r={result({ openToCollaboration: true })} index={0} />);
    expect(screen.getByText(/open to collaboration/i)).toBeInTheDocument();
  });

  it("omits the ORCID badge when there is no ORCID", () => {
    renderWithProviders(<ResearcherCard r={result({ orcid: null })} index={0} />);
    expect(screen.queryByLabelText("ORCID-verified identity")).not.toBeInTheDocument();
  });

  it("hides the start-a-project action for a signed-out visitor", () => {
    renderWithProviders(<ResearcherCard r={result()} index={0} />);
    expect(screen.queryByText(/start a project with/i)).not.toBeInTheDocument();
  });

  it("shows the start-a-project action for a signed-in researcher and routes to CoLab with the match", async () => {
    auth.user = { uid: "me" };
    renderWithProviders(<ResearcherCard r={result()} index={0} />);
    const button = screen.getByText(/start a project with ada/i);
    await userEvent.click(button);
    expect(push).toHaveBeenCalledWith("/workspace?withResearcher=A100&withResearcherName=Ada+Lovelace");
  });

  it("never offers to start a project with a deceased researcher", () => {
    auth.user = { uid: "me" };
    renderWithProviders(<ResearcherCard r={result({ deceased: { year: 1852 } })} index={0} />);
    expect(screen.queryByText(/start a project with/i)).not.toBeInTheDocument();
  });
});
