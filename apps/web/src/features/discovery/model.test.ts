import { describe, expect, it } from "vitest";
import { bestTaxonMatch } from "./model";

describe("bestTaxonMatch", () => {
  const taxa = [
    { id: "f1", display_name: "Computer Science" },
    { id: "f2", display_name: "Quantum Physics" },
  ];

  it("matches a profile focus by token overlap", () => {
    expect(bestTaxonMatch("quantum physics", taxa)?.id).toBe("f2");
  });

  it("does not invent a taxonomy match for unrelated text", () => {
    expect(bestTaxonMatch("marine biology", taxa)).toBeNull();
  });
});
