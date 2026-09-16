import { describe, expect, it } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { ReviewTab } from "./ReviewTab";

describe("ReviewTab", () => {
  it("reports structural manuscript checks without inventing a quality score", () => {
    renderWithProviders(<ReviewTab documentBody={"# Abstract\nA result.\n\n## References\n\\cite{openalex:W1}"} />);
    expect(screen.getByText("Abstract section present")).toBeInTheDocument();
    expect(screen.getByText("References section present")).toBeInTheDocument();
    expect(screen.getByText("1 citation marker found")).toBeInTheDocument();
    expect(screen.getByText("4/4")).toBeInTheDocument();
  });

  it("shows missing sections as actionable warnings", () => {
    renderWithProviders(<ReviewTab documentBody="An unstructured note" />);
    expect(screen.getByText("Abstract section present")).toBeInTheDocument();
    expect(screen.getByText("References section present")).toBeInTheDocument();
    expect(screen.getByText("0 citation markers found")).toBeInTheDocument();
  });
});
