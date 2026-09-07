import { describe, expect, it } from "vitest";
import { renderWithProviders, screen } from "@/test/render";
import { AuthorInline, splitAuthorPair } from "./AuthorInline";

describe("splitAuthorPair", () => {
  it("splits a 'Name|id' pair and trims", () => {
    expect(splitAuthorPair("Ada Lovelace|https://openalex.org/A1")).toEqual({
      name: "Ada Lovelace",
      id: "https://openalex.org/A1",
    });
  });
  it("returns an id-less author for a bare name", () => {
    expect(splitAuthorPair("Anon")).toEqual({ name: "Anon", id: undefined });
  });
});

describe("AuthorInline", () => {
  it("links authors with an id and leaves id-less ones as plain text", () => {
    renderWithProviders(
      <AuthorInline
        authors={[
          { name: "Ada Lovelace", id: "https://openalex.org/A5023888391" },
          { name: "Anon" },
        ]}
      />,
    );
    expect(screen.getByRole("link", { name: "Ada Lovelace" })).toHaveAttribute(
      "href",
      "/author/A5023888391?name=Ada%20Lovelace",
    );
    expect(screen.queryByRole("link", { name: "Anon" })).not.toBeInTheDocument();
    expect(screen.getByText("Anon")).toBeInTheDocument();
  });

  it("collapses past `max` to a non-link '+N more'", () => {
    renderWithProviders(
      <AuthorInline
        max={2}
        authors={[
          { name: "A", id: "A1" },
          { name: "B", id: "A2" },
          { name: "C", id: "A3" },
          { name: "D", id: "A4" },
        ]}
      />,
    );
    expect(screen.getAllByRole("link")).toHaveLength(2);
    expect(screen.getByText("+2 more")).toBeInTheDocument();
  });

  it("renders nothing when there are no named authors", () => {
    const { container } = renderWithProviders(<AuthorInline authors={[{ name: "" }]} />);
    expect(container.textContent).toBe("");
  });
});
