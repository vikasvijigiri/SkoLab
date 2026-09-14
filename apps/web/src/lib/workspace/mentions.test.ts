import { describe, expect, it } from "vitest";
import { parseMentionedMembers } from "./mentions";
import type { CollabMember } from "@/lib/types";

const members: CollabMember[] = [
  { uid: "u1", name: "Ada Lovelace", email: "ada@x.edu", role: "owner" },
  { uid: "u2", name: "Grace Hopper", email: "grace@x.edu", role: "editor" },
  { uid: "u3", name: "Katherine Johnson", email: "kat@x.edu", role: "viewer" },
];

describe("parseMentionedMembers", () => {
  it("matches a real member's full display name, case-insensitively", () => {
    const got = parseMentionedMembers("hey @grace hopper can you check this", members, "u1");
    expect(got.map((m) => m.uid)).toEqual(["u2"]);
  });

  it("matches multiple distinct mentions in one message", () => {
    const got = parseMentionedMembers("@Grace Hopper and @Katherine Johnson, thoughts?", members, "u1");
    expect(got.map((m) => m.uid).sort()).toEqual(["u2", "u3"]);
  });

  it("excludes the sender even if they mention themselves", () => {
    const got = parseMentionedMembers("@Ada Lovelace here", members, "u1");
    expect(got).toEqual([]);
  });

  it("returns nothing when no real member name is mentioned", () => {
    const got = parseMentionedMembers("@nobody-in-particular", members, "u1");
    expect(got).toEqual([]);
  });

  it("does not fuzzy-match a partial name", () => {
    // "@Grace" alone should not match "Grace Hopper" — full display name only.
    const got = parseMentionedMembers("@Grace can you help", members, "u1");
    expect(got).toEqual([]);
  });

  it("ignores a message with no @ at all", () => {
    const got = parseMentionedMembers("Grace Hopper wrote the compiler", members, "u1");
    expect(got).toEqual([]);
  });
});
