import { describe, expect, it, vi, beforeEach } from "vitest";
import userEvent from "@testing-library/user-event";
import { renderWithProviders, screen } from "@/test/render";
import { TrendingTopicCard } from "./TrendingTopicCard";
import type { TrendingTopic } from "@/lib/types";

// TrendingTopicCard now renders TrackTopicButton (decision 0021's topic
// half), which calls useAuth() — same mocking pattern as ResearcherCard.test.
const auth = vi.hoisted(() => ({ user: null as null | { uid: string } }));
vi.mock("@/lib/hooks/AuthProvider", () => ({
  useAuth: () => ({ user: auth.user }),
}));

const topic: TrendingTopic = {
  id: "T1",
  displayName: "Spin Liquids",
  recentCount: 40,
  priorCount: 20,
  growth: 1.0,
};

describe("TrendingTopicCard", () => {
  beforeEach(() => {
    auth.user = null;
  });

  it("shows a growth percentage, not a raw count", () => {
    renderWithProviders(
      <TrendingTopicCard t={topic} index={0} windowDays={90} onSelect={vi.fn()} />,
    );
    expect(screen.getByText("Spin Liquids")).toBeInTheDocument();
    expect(screen.getByText("+100%")).toBeInTheDocument();
  });

  it("shows the actual recent/prior counts so the percentage is checkable", () => {
    renderWithProviders(
      <TrendingTopicCard t={topic} index={0} windowDays={90} onSelect={vi.fn()} />,
    );
    expect(screen.getByText(/40 papers/i)).toBeInTheDocument();
    expect(screen.getByText(/20 the 90 days before/i)).toBeInTheDocument();
  });

  it("calls onSelect with the topic when clicked", async () => {
    const onSelect = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(
      <TrendingTopicCard t={topic} index={0} windowDays={90} onSelect={onSelect} />,
    );
    await user.click(screen.getByRole("button", { name: /Spin Liquids/i }));
    expect(onSelect).toHaveBeenCalledWith(topic);
  });

  it("is click-only — no text inputs", () => {
    const { container } = renderWithProviders(
      <TrendingTopicCard t={topic} index={0} windowDays={90} onSelect={vi.fn()} />,
    );
    expect(container.querySelector("input, textarea")).toBeNull();
  });

  it("shows the Track button for a signed-in viewer", () => {
    auth.user = { uid: "me" };
    renderWithProviders(
      <TrendingTopicCard t={topic} index={0} windowDays={90} onSelect={vi.fn()} />,
    );
    expect(screen.getByRole("button", { name: /track spin liquids/i })).toBeInTheDocument();
  });

  it("hides Track for a signed-out visitor", () => {
    renderWithProviders(
      <TrendingTopicCard t={topic} index={0} windowDays={90} onSelect={vi.fn()} />,
    );
    expect(screen.queryByRole("button", { name: /track spin liquids/i })).not.toBeInTheDocument();
  });
});
