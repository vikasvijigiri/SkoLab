import { describe, expect, it } from "vitest";
import { buildApa, buildBibtex, reconstructAbstract } from "./openalex-work";
import type { OpenAlexWork } from "./types";

describe("reconstructAbstract", () => {
  it("rebuilds running text from an inverted index", () => {
    expect(
      reconstructAbstract({ The: [0, 4], quick: [1], brown: [2], fox: [3], end: [5] }),
    ).toBe("The quick brown fox The end");
  });
  it("is empty for a missing / non-object index", () => {
    expect(reconstructAbstract(undefined)).toBe("");
    expect(reconstructAbstract(null)).toBe("");
  });
});

const work: OpenAlexWork = {
  id: "https://openalex.org/W1",
  display_name: "On the Analytical Engine",
  publication_year: 1843,
  doi: "https://doi.org/10.1/abc",
  primary_location: { source: { display_name: "Taylor's Scientific Memoirs" } },
  biblio: { volume: "3", issue: "1", first_page: "666", last_page: "731" },
  authorships: [
    { author: { id: "A1", display_name: "Ada Lovelace" } },
    { author: { id: "A2", display_name: "Charles Babbage" } },
  ],
};

describe("buildBibtex", () => {
  it("emits an @article entry with a name+year key and the DOI bare", () => {
    const b = buildBibtex(work);
    expect(b).toMatch(/^@article\{lovelace1843,/);
    expect(b).toContain("author = {Ada Lovelace and Charles Babbage}");
    expect(b).toContain("journal = {Taylor's Scientific Memoirs}");
    expect(b).toContain("pages = {666--731}");
    expect(b).toContain("doi = {10.1/abc}");
  });
});

describe("buildApa", () => {
  it("joins authors with an ampersand and includes year, venue, doi", () => {
    expect(buildApa(work)).toBe(
      "Ada Lovelace, & Charles Babbage (1843). On the Analytical Engine. Taylor's Scientific Memoirs. https://doi.org/10.1/abc",
    );
  });
});
