import { describe, expect, it } from "vitest";
import { formatDate, formatDateTime, timeAgo } from "./dates";

describe("dates", () => {
  const now = Date.parse("2026-10-09T12:00:00Z");

  it.each([
    ["2026-10-09T11:59:30Z", "just now"],
    ["2026-10-09T11:55:00Z", "5 minutes ago"],
    ["2026-10-09T09:00:00Z", "3 hours ago"],
    ["2026-10-08T12:00:00Z", "yesterday"],
    ["2026-09-25T12:00:00Z", "2 weeks ago"],
    ["2025-10-09T12:00:00Z", "last year"],
  ])("%s reads as %s", (iso, expected) => expect(timeAgo(iso, now, "en")).toBe(expected));

  it("formats dates for people", () => {
    expect(formatDate("2026-10-09T12:00:00Z", "en-GB")).toBe("9 Oct 2026");
    expect(formatDateTime("2026-10-09T12:00:00Z", "en-GB")).toMatch(/^9 Oct 2026, \d\d:\d\d$/);
  });
});
