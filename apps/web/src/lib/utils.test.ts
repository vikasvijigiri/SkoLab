import { describe, expect, it } from "vitest";
import { cn } from "./utils";

describe("cn", () => {
  it("joins truthy class values and drops falsy ones", () => {
    expect(cn("a", false && "b", "c")).toBe("a c");
  });

  it("lets a later tailwind class win a conflict", () => {
    expect(cn("px-2", "px-4")).toBe("px-4");
  });
});
