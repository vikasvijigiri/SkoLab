import { describe, expect, it } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { LandingTryDemo } from "./LandingTryDemo";

describe("LandingTryDemo", () => {
  it("auto-selects a field and renders a researcher signature with real numbers", async () => {
    renderWithProviders(<LandingTryDemo />);
    // The default /api/openalex/authors handler returns Ada Lovelace (9001 cites).
    // Name shows twice: the researcher chip + the signature caption.
    expect((await screen.findAllByText("Ada Lovelace")).length).toBeGreaterThan(0);
    expect(screen.getByText("Citations")).toBeInTheDocument();
    expect(screen.getByText("9.0k")).toBeInTheDocument(); // 9001 formatted
    expect(screen.getByText("h-index")).toBeInTheDocument();
  });

  it("prompts a field pick and never shows a text input", () => {
    const { container } = renderWithProviders(<LandingTryDemo />);
    expect(screen.getByText(/Pick a field/i)).toBeInTheDocument();
    expect(container.querySelector("input")).toBeNull();
  });
});
