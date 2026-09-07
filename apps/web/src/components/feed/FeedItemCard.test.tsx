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
  it("renders the kind chip, a why line, and the headline as the single primary target (external)", () => {
    renderWithProviders(
      <FeedItemCard item={newsItem} saved={false} onToggleSave={vi.fn()} onDismiss={vi.fn()} />,
    );
    expect(screen.getByText("News")).toBeInTheDocument();
    expect(screen.getByText(/science news from Quanta Magazine/i)).toBeInTheDocument();
    const link = screen.getByRole("link", { name: /A quantum result/i });
    expect(link).toHaveAttribute("href", "https://x/1");
    expect(link).toHaveAttribute("target", "_blank");
    // no redundant "Read" button any more
    expect(screen.queryByRole("link", { name: /^read$/i })).not.toBeInTheDocument();
  });

  it("renders a real <time> element for the timestamp", () => {
    const { container } = renderWithProviders(
      <FeedItemCard item={newsItem} saved={false} onToggleSave={vi.fn()} onDismiss={vi.fn()} />,
    );
    const t = container.querySelector("time");
    expect(t).not.toBeNull();
    expect(t?.getAttribute("datetime")).toMatch(/^\d{4}-\d{2}-\d{2}T/);
  });

  it("fires save and dismiss callbacks and exposes aria-pressed on save", async () => {
    const onToggleSave = vi.fn();
    const onDismiss = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(
      <FeedItemCard item={paperItem} saved={false} onToggleSave={onToggleSave} onDismiss={onDismiss} />,
    );
    const save = screen.getByRole("button", { name: /save this/i });
    expect(save).toHaveAttribute("aria-pressed", "false");
    await user.click(save);
    await user.click(screen.getByRole("button", { name: /not relevant/i }));
    expect(onToggleSave).toHaveBeenCalledOnce();
    expect(onDismiss).toHaveBeenCalledOnce();
  });

  it("shows the saved state", () => {
    renderWithProviders(
      <FeedItemCard item={paperItem} saved onToggleSave={vi.fn()} onDismiss={vi.fn()} />,
    );
    const save = screen.getByRole("button", { name: /saved — tap to remove/i });
    expect(save).toHaveAttribute("aria-pressed", "true");
  });
});
