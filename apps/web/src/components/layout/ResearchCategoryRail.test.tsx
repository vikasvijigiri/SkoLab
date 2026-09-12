import { describe, expect, it } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "@/test/render";
import { ResearchCategoryRail } from "./ResearchCategoryRail";

describe("ResearchCategoryRail", () => {
  it("exposes the research marketplace categories with accessible links", () => {
    renderWithProviders(<ResearchCategoryRail />);

    expect(screen.getByRole("navigation", { name: "Research categories" })).toBeInTheDocument();
    for (const label of ["For you", "Papers", "People", "Topics", "Methods", "Datasets", "Grants", "Jobs"]) {
      expect(screen.getByRole("link", { name: label })).toBeInTheDocument();
    }
  });
});
