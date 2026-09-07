import { describe, expect, it, beforeEach } from "vitest";
import userEvent from "@testing-library/user-event";
import { renderWithProviders, screen, within } from "@/test/render";
import { mockActivityFeed, mockScienceNews } from "@/test/fixtures";
import { UnifiedFeed } from "./UnifiedFeed";
import type { DailyFeedItem } from "@/lib/types";

const papers: DailyFeedItem[] = [
  {
    id: "W1",
    title: "A ranked paper",
    authors: ["A"],
    journal: "J",
    year: 2026,
    relevance_score: 88,
    recommendation_reason: "",
  },
];

beforeEach(() => {
  try {
    localStorage.clear();
  } catch {
    /* ignore */
  }
});

describe("UnifiedFeed accessibility", () => {
  it("renders a labelled role=feed with <article> items once populated", () => {
    renderWithProviders(
      <UnifiedFeed
        papers={papers}
        news={mockScienceNews}
        activity={mockActivityFeed}
        jobs={[]}
        loading={false}
      />,
    );
    const feed = screen.getByRole("feed", { name: /research feed/i });
    expect(within(feed).getAllByRole("article").length).toBeGreaterThan(0);
    expect(screen.getByRole("heading", { name: "Your feed" })).toBeInTheDocument();
  });

  it("marks the active lens with aria-pressed", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <UnifiedFeed papers={papers} news={[]} activity={[]} jobs={[]} loading={false} />,
    );
    const forYou = screen.getByRole("button", { name: "For you" });
    const papersBtn = screen.getByRole("button", { name: "Papers" });
    expect(forYou).toHaveAttribute("aria-pressed", "true");
    expect(papersBtn).toHaveAttribute("aria-pressed", "false");

    await user.click(papersBtn);
    expect(papersBtn).toHaveAttribute("aria-pressed", "true");
    expect(forYou).toHaveAttribute("aria-pressed", "false");
  });

  it("sets aria-busy while loading with no items yet", () => {
    const { container } = renderWithProviders(
      <UnifiedFeed papers={[]} news={[]} activity={[]} jobs={[]} loading />,
    );
    expect(container.querySelector('[aria-busy="true"]')).not.toBeNull();
  });
});
