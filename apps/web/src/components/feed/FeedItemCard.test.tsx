import { describe, expect, it, vi } from "vitest";
import userEvent from "@testing-library/user-event";
import { renderWithProviders, screen } from "@/test/render";
import { FeedItemCard } from "./FeedItemCard";
import type { UnifiedItem } from "@/lib/feed/unifiedFeed";

const newsItem: UnifiedItem = {
  id: "news:https://x/1",
  kind: "news",
  title: "A quantum result",
  why: "science news from Quanta Magazine",
  href: "https://x/1",
  external: true,
  ts: Date.now() - 3_600_000,
  source: "Quanta Magazine",
  meta: "short summary",
  score: 1,
};

const paperItem: UnifiedItem = {
  id: "paper:W1",
  kind: "paper",
  title: "A related paper",
  why: "83% match to your work",
  href: "/paper/W1",
  external: false,
  ts: 0,
  source: "Nature",
  score: 1,
};

describe("FeedItemCard", () => {
  it("renders a kind chip, why line and an external primary action for news", () => {
    renderWithProviders(
      <FeedItemCard item={newsItem} saved={false} onToggleSave={vi.fn()} onDismiss={vi.fn()} />,
    );
    expect(screen.getByText("News")).toBeInTheDocument();
    expect(screen.getByText(/science news from Quanta Magazine/i)).toBeInTheDocument();
    const read = screen.getByRole("link", { name: /read/i });
    expect(read).toHaveAttribute("href", "https://x/1");
    expect(read).toHaveAttribute("target", "_blank");
  });

  it("fires save and dismiss callbacks", async () => {
    const onToggleSave = vi.fn();
    const onDismiss = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(
      <FeedItemCard item={paperItem} saved={false} onToggleSave={onToggleSave} onDismiss={onDismiss} />,
    );
    await user.click(screen.getByRole("button", { name: /^save$/i }));
    await user.click(screen.getByRole("button", { name: /not relevant/i }));
    expect(onToggleSave).toHaveBeenCalledOnce();
    expect(onDismiss).toHaveBeenCalledOnce();
  });

  it("shows the saved state", () => {
    renderWithProviders(
      <FeedItemCard item={paperItem} saved onToggleSave={vi.fn()} onDismiss={vi.fn()} />,
    );
    expect(screen.getByRole("button", { name: /remove from saved/i })).toBeInTheDocument();
  });
});
