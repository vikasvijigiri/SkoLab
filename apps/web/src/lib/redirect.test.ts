import { describe, expect, it } from "vitest";
import { safeNext, signInPath } from "./redirect";

describe("safeNext", () => {
  it.each([
    [null, "/"],
    ["", "/"],
    ["https://evil.example/", "/"],
    ["//evil.example", "/"],
    ["/\\evil.example", "/"],
    ["javascript:alert(1)", "/"],
    ["/sign-in", "/"],
    ["/verify-email?next=/x", "/"],
    ["/workspaces/42?tab=members#top", "/workspaces/42?tab=members#top"],
    ["/", "/"],
  ])("maps %j to %j", (raw, expected) => expect(safeNext(raw)).toBe(expected));
});

describe("signInPath", () => {
  it("only adds next when it leads somewhere", () => {
    expect(signInPath("/")).toBe("/sign-in");
    expect(signInPath("/workspaces/1?x=1")).toBe("/sign-in?next=%2Fworkspaces%2F1%3Fx%3D1");
  });
});
