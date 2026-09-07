import { describe, expect, it } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { mockScienceNews } from "@/test/fixtures";
import { ScienceNewsCard } from "./ScienceNewsCard";

describe("ScienceNewsCard", () => {
  it("renders each headline as an external link with a source badge", () => {
    renderWithProviders(<ScienceNewsCard items={mockScienceNews} loading={false} />);
    const link = screen.getByRole("link", {
      name: /A new state of matter observed in a spin liquid/i,
    });
    expect(link).toHaveAttribute("href", "https://www.quantamagazine.org/example-a");
    expect(link).toHaveAttribute("target", "_blank");
    expect(link).toHaveAttribute("rel", expect.stringContaining("noopener"));
    expect(screen.getByText("Quanta Magazine")).toBeInTheDocument();
    expect(screen.getByText("Phys.org")).toBeInTheDocument();
  });

  it("shows skeletons while loading", () => {
    const { container } = renderWithProviders(<ScienceNewsCard items={[]} loading />);
    expect(container.querySelectorAll(".animate-pulse").length).toBeGreaterThan(0);
  });

  it("shows a calm placeholder when empty", () => {
    renderWithProviders(<ScienceNewsCard items={[]} loading={false} />);
    expect(screen.getByText(/headlines will appear here/i)).toBeInTheDocument();
  });
});
