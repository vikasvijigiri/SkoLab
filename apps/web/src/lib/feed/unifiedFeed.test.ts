import { describe, expect, it } from "vitest";
import { buildUnifiedFeed, type FeedPrefs } from "./unifiedFeed";
import type { ActivityItem, DailyFeedItem, IndustryOpportunity } from "@/lib/types";

const paper = (id: string, rel = 80): DailyFeedItem => ({
  id,
  title: `Paper ${id}`,
  authors: ["A"],
  journal: "J",
  year: 2026,
  relevance_score: rel,
  recommendation_reason: "",
});

const act = (id: string): ActivityItem => ({
  id,
  type: "connection_made",
  verb: "is now connected with you",
  ts: "2026-09-05T00:00:00Z",
  actor: { id: "A2", display_name: "Grace" },
  href: "/author/A2",
  why: "new connection",
});

const job = (id: string, match = 90): IndustryOpportunity => ({
  id,
  type: "JOB",
  title: `Job ${id}`,
  companyOrFunder: "Lab",
  tags: [],
  description: "",
  matchScore: match,
});

const noPrefs: FeedPrefs = { savedIds: new Set(), dismissedKinds: {}, dismissedIds: new Set() };

describe("buildUnifiedFeed", () => {
  it("merges every source into one list with kind-prefixed ids", () => {
    const out = buildUnifiedFeed(
      { papers: [paper("W1")], activity: [act("a1")], jobs: [job("j1")] },
      noPrefs,
    );
    expect(out.map((i) => i.kind).sort()).toEqual(["activity", "job", "paper"]);
    expect(out.find((i) => i.kind === "paper")!.id).toBe("paper:W1");
    expect(out.every((i) => i.why.length > 0)).toBe(true);
  });

  it("removes dismissed ids and damps a repeatedly-dismissed kind", () => {
    const prefs: FeedPrefs = {
      savedIds: new Set(),
      dismissedIds: new Set(["job:j1"]),
      dismissedKinds: { job: 3 },
    };
    const out = buildUnifiedFeed(
      { papers: [paper("W1", 50)], jobs: [job("j1"), job("j2")] },
      prefs,
    );
    expect(out.find((i) => i.id === "job:j1")).toBeUndefined();
    // paper outranks the damped job item
    expect(out[0]!.kind).toBe("paper");
  });

  it("lifts a saved item to the top", () => {
    const prefs: FeedPrefs = {
      savedIds: new Set(["job:j1"]),
      dismissedIds: new Set(),
      dismissedKinds: {},
    };
    const out = buildUnifiedFeed({ papers: [paper("W1", 99)], jobs: [job("j1")] }, prefs);
    expect(out[0]!.id).toBe("job:j1");
  });

  it("normalises a canonical OpenAlex URL id to a bare /paper/ route and cache key", () => {
    const out = buildUnifiedFeed(
      { papers: [paper("https://openalex.org/W7206172422")] },
      noPrefs,
    );
    const item = out.find((i) => i.kind === "paper")!;
    expect(item.href).toBe("/paper/W7206172422");
    expect(item.id).toBe("paper:W7206172422");
  });

  it("breaks runs of 3+ of the same kind", () => {
    const out = buildUnifiedFeed(
      {
        papers: [paper("W1"), paper("W2"), paper("W3"), paper("W4")],
        activity: [act("a1"), act("a2")],
        jobs: [job("j1"), job("j2")],
      },
      noPrefs,
    );
    for (let i = 2; i < out.length; i++) {
      const run = out[i]!.kind === out[i - 1]!.kind && out[i - 1]!.kind === out[i - 2]!.kind;
      expect(run).toBe(false);
    }
  });
});
