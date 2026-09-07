import { describe, expect, it } from "vitest";
import { buildUnifiedFeed, type FeedPrefs } from "./unifiedFeed";
import type { ActivityItem, DailyFeedItem, IndustryOpportunity, ScienceNewsItem } from "@/lib/types";

const paper = (id: string, rel = 80): DailyFeedItem => ({
  id,
  title: `Paper ${id}`,
  authors: ["A"],
  journal: "J",
  year: 2026,
  relevance_score: rel,
  recommendation_reason: "",
});

const news = (url: string): ScienceNewsItem => ({
  title: `News ${url}`,
  url,
  source: "Quanta Magazine",
  published: "2026-09-06T00:00:00Z",
  summary: "s",
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
      { papers: [paper("W1")], news: [news("u1")], activity: [act("a1")], jobs: [job("j1")] },
      noPrefs,
    );
    expect(out.map((i) => i.kind).sort()).toEqual(["activity", "job", "news", "paper"]);
    expect(out.find((i) => i.kind === "paper")!.id).toBe("paper:W1");
    expect(out.every((i) => i.why.length > 0)).toBe(true);
  });

  it("removes dismissed ids and damps a repeatedly-dismissed kind", () => {
    const prefs: FeedPrefs = {
      savedIds: new Set(),
      dismissedIds: new Set(["news:u1"]),
      dismissedKinds: { news: 3 },
    };
    const out = buildUnifiedFeed(
      { papers: [paper("W1", 50)], news: [news("u1"), news("u2")] },
      prefs,
    );
    expect(out.find((i) => i.id === "news:u1")).toBeUndefined();
    // paper outranks the damped news item
    expect(out[0]!.kind).toBe("paper");
  });

  it("lifts a saved item to the top", () => {
    const prefs: FeedPrefs = {
      savedIds: new Set(["news:u1"]),
      dismissedIds: new Set(),
      dismissedKinds: {},
    };
    const out = buildUnifiedFeed({ papers: [paper("W1", 99)], news: [news("u1")] }, prefs);
    expect(out[0]!.id).toBe("news:u1");
  });

  it("breaks runs of 3+ of the same kind", () => {
    const out = buildUnifiedFeed(
      {
        papers: [paper("W1"), paper("W2"), paper("W3"), paper("W4")],
        news: [news("u1"), news("u2")],
      },
      noPrefs,
    );
    for (let i = 2; i < out.length; i++) {
      const run = out[i]!.kind === out[i - 1]!.kind && out[i - 1]!.kind === out[i - 2]!.kind;
      expect(run).toBe(false);
    }
  });
});
