import { describe, expect, it } from "vitest";
import { resolveCollabFlags } from "./collabFlags";

describe("resolveCollabFlags (seam)", () => {
  it("returns an empty map today — no fabricated flags", () => {
    expect(resolveCollabFlags(["A1", "A2", "0000-0001-2345-6789"])).toEqual({});
  });
  it("is stable on empty input", () => {
    expect(resolveCollabFlags([])).toEqual({});
  });
  it("never returns a falsy value for an id (absence means unknown)", () => {
    const out = resolveCollabFlags(["A1"]);
    expect(Object.values(out).every((v) => v === true)).toBe(true);
  });
});
