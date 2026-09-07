import { describe, expect, it } from "vitest";
import { cn, shortOpenAlexId } from "./utils";

describe("cn", () => {
  it("joins truthy class values and drops falsy ones", () => {
    expect(cn("a", false && "b", "c")).toBe("a c");
  });

  it("lets a later tailwind class win a conflict", () => {
    expect(cn("px-2", "px-4")).toBe("px-4");
  });
});

describe("shortOpenAlexId", () => {
  it("strips the canonical OpenAlex URL prefix", () => {
    expect(shortOpenAlexId("https://openalex.org/W7206172422")).toBe("W7206172422");
    expect(shortOpenAlexId("https://openalex.org/A5023888391")).toBe("A5023888391");
  });

  it("passes an already-bare id through unchanged", () => {
    expect(shortOpenAlexId("W7206172422")).toBe("W7206172422");
  });

  it("handles the api.openalex.org/works form and a trailing slash", () => {
    expect(shortOpenAlexId("https://api.openalex.org/works/W123")).toBe("W123");
    expect(shortOpenAlexId("https://openalex.org/W123/")).toBe("W123");
  });

  it("is a no-op on empty / falsy input", () => {
    expect(shortOpenAlexId("")).toBe("");
  });
});
