import { describe, expect, it } from "vitest";
import { buildDeathSparql, parseDeathBindings, sanitizeOrcids } from "./deaths";

describe("sanitizeOrcids", () => {
  it("keeps well-formed ORCIDs, strips the URL prefix, dedupes and caps", () => {
    const out = sanitizeOrcids(
      [
        "https://orcid.org/0000-0001-2345-6789",
        "0000-0001-2345-6789", // dup
        "0000-0002-0000-000X",
        "not-an-orcid",
        "0000-0003-0000-0001",
      ],
      2,
    );
    expect(out).toEqual(["0000-0001-2345-6789", "0000-0002-0000-000X"]);
  });
  it("returns [] for no valid input", () => {
    expect(sanitizeOrcids(["garbage", ""], 24)).toEqual([]);
  });
});

describe("buildDeathSparql", () => {
  it("embeds every ORCID in the VALUES block and asks for P496 + P570", () => {
    const q = buildDeathSparql(["0000-0001-2345-6789", "0000-0002-0000-000X"]);
    expect(q).toContain('VALUES ?orcid { "0000-0001-2345-6789" "0000-0002-0000-000X" }');
    expect(q).toContain("wdt:P496");
    expect(q).toContain("wdt:P570");
  });
});

describe("parseDeathBindings", () => {
  it("maps ORCID → death year", () => {
    const json = {
      results: {
        bindings: [
          { orcid: { value: "0000-0001-2345-6789" }, year: { value: "1996" } },
          { orcid: { value: "0000-0002-0000-000x" }, year: { value: "2007" } },
        ],
      },
    };
    expect(parseDeathBindings(json)).toEqual({
      "0000-0001-2345-6789": 1996,
      "0000-0002-0000-000X": 2007,
    });
  });
  it("skips malformed years and empty input without throwing", () => {
    expect(parseDeathBindings({ results: { bindings: [] } })).toEqual({});
    expect(
      parseDeathBindings({
        results: { bindings: [{ orcid: { value: "0000-0001-2345-6789" }, year: { value: "n/a" } }] },
      }),
    ).toEqual({});
  });
  it("tolerates a completely empty payload", () => {
    expect(parseDeathBindings({})).toEqual({});
  });
});
