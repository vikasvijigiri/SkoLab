import { describe, expect, it } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { mockActivityFeed } from "@/test/fixtures";
import { ActivityFeedItem } from "./ActivityFeedItem";

describe("ActivityFeedItem", () => {
  it("renders a paper_published item with actor, work title and why chip", () => {
    renderWithProviders(<ActivityFeedItem item={mockActivityFeed[0]!} />);
    expect(screen.getByText("Grace Hopper")).toBeInTheDocument();
    expect(screen.getByText("Compilers for the analytical engine")).toBeInTheDocument();
    expect(screen.getByText("In your network")).toBeInTheDocument();
    // Object links to the paper.
    expect(screen.getByRole("link", { name: /Compilers for the analytical engine/i })).toHaveAttribute(
      "href",
      "/paper/W3000000001",
    );
  });

  it("renders a connection_made item as a profile link with no work body", () => {
    renderWithProviders(<ActivityFeedItem item={mockActivityFeed[1]!} />);
    expect(screen.getByText("Katherine Johnson")).toBeInTheDocument();
    expect(screen.getByText(/is now connected with you/i)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /view profile/i })).toHaveAttribute(
      "href",
      "/author/A5000000003",
    );
  });

  it("renders a trending item with no actor avatar", () => {
    renderWithProviders(<ActivityFeedItem item={mockActivityFeed[2]!} />);
    expect(screen.getByText("A widely-cited recent result")).toBeInTheDocument();
    expect(screen.getByText("140 citations already")).toBeInTheDocument();
  });
});
