import { describe, expect, it } from "vitest";
import { Briefcase, Coins } from "lucide-react";
import { renderWithProviders, screen, within } from "@/test/render";
import { AIDailyBriefCard, type BriefItem } from "./AIDailyBriefCard";

const items: BriefItem[] = [
  {
    key: "grant",
    icon: Coins,
    color: "var(--accent-emerald)",
    label: "Grant match",
    text: "**Deep Learning for Climate** (NSF) — 91% fit",
    href: "https://example.org/grant",
  },
  {
    key: "opportunity",
    icon: Briefcase,
    color: "var(--accent-orange)",
    label: "Role opened",
    text: "**Research Scientist** at **Acme Labs**",
    href: "/opportunity/1",
  },
];

describe("AIDailyBriefCard", () => {
  it("renders one card per service inside a labelled scroll rail", () => {
    renderWithProviders(<AIDailyBriefCard items={items} loading={false} />);
    const rail = screen.getByRole("list", { name: /daily brief/i });
    expect(within(rail).getAllByRole("listitem")).toHaveLength(2);
    expect(screen.getByText("Grant match")).toBeInTheDocument();
    expect(screen.getByText("Role opened")).toBeInTheDocument();
  });

  it("gives each service its own accent colour", () => {
    renderWithProviders(<AIDailyBriefCard items={items} loading={false} />);
    expect(screen.getByText("Grant match").getAttribute("style")).toContain(
      "var(--accent-emerald)",
    );
    expect(screen.getByText("Role opened").getAttribute("style")).toContain(
      "var(--accent-orange)",
    );
  });

  it("opens external briefs in a new tab and keeps internal ones as app links", () => {
    renderWithProviders(<AIDailyBriefCard items={items} loading={false} />);
    const external = screen.getByRole("link", { name: /grant match/i });
    expect(external).toHaveAttribute("href", "https://example.org/grant");
    expect(external).toHaveAttribute("target", "_blank");
    expect(external).toHaveAttribute("rel", "noopener noreferrer");
    expect(screen.getByRole("link", { name: /role opened/i })).toHaveAttribute(
      "href",
      "/opportunity/1",
    );
  });

  it("shows skeletons while loading", () => {
    const { container } = renderWithProviders(<AIDailyBriefCard items={[]} loading />);
    expect(container.querySelectorAll(".animate-pulse").length).toBeGreaterThan(0);
  });

  it("shows the building-your-brief message when there is nothing yet", () => {
    renderWithProviders(<AIDailyBriefCard items={[]} loading={false} />);
    expect(screen.getByText(/building your personalized brief/i)).toBeInTheDocument();
  });

  it("stacks the cards vertically and full-width in the 'stack' layout", () => {
    renderWithProviders(<AIDailyBriefCard items={items} loading={false} layout="stack" />);
    const rail = screen.getByRole("list", { name: /daily brief/i });
    expect(rail.className).toContain("flex-col");
    expect(rail.className).not.toContain("overflow-x-auto");
    // each card fills the column rather than a fixed 256px rail width
    const firstCard = within(rail).getAllByRole("listitem")[0]?.querySelector("div");
    expect(firstCard?.className).toContain("w-full");
    expect(firstCard?.className).not.toContain("w-64");
  });
});
