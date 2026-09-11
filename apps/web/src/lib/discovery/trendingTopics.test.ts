import { describe, expect, it } from "vitest";
import { computeTopicGrowth } from "./trendingTopics";
import type { TopicActivityBucket } from "@/lib/types";

const bucket = (id: string, displayName: string, count: number): TopicActivityBucket => ({
  id,
  displayName,
  count,
});

describe("computeTopicGrowth", () => {
  it("ranks a topic by (recent - prior) / prior, not raw volume", () => {
    const recent = [bucket("T1", "Spin Liquids", 40), bucket("T2", "Superconductivity", 60)];
    const prior = [bucket("T1", "Spin Liquids", 20), bucket("T2", "Superconductivity", 55)];
    const rows = computeTopicGrowth(recent, prior, { minPriorWorks: 15 });
    // T1 doubled (100% growth) but has fewer total works than T2 (~9% growth) —
    // growth ranking must put the doubling topic first despite the smaller volume.
    expect(rows[0]?.id).toBe("T1");
    expect(rows[0]?.growth).toBeCloseTo(1.0, 5);
    expect(rows[1]?.id).toBe("T2");
  });

  it("drops a topic below the prior-window volume floor", () => {
    // 1 -> 4 works reads as 300% growth but is noise from a tiny base.
    const recent = [bucket("T1", "Tiny Topic", 4), bucket("T2", "Real Trend", 100)];
    const prior = [bucket("T1", "Tiny Topic", 1), bucket("T2", "Real Trend", 80)];
    const rows = computeTopicGrowth(recent, prior, { minPriorWorks: 15 });
    expect(rows.map((r) => r.id)).toEqual(["T2"]);
  });

  it("drops a topic with no prior-window presence at all (would divide by zero)", () => {
    const recent = [bucket("T1", "Brand New", 30)];
    const prior: TopicActivityBucket[] = [];
    expect(() => computeTopicGrowth(recent, prior, { minPriorWorks: 15 })).not.toThrow();
    expect(computeTopicGrowth(recent, prior, { minPriorWorks: 15 })).toEqual([]);
  });

  it("truncates to topN after sorting", () => {
    const recent = Array.from({ length: 5 }, (_, i) => bucket(`T${i}`, `Topic ${i}`, 20 + i * 10));
    const prior = Array.from({ length: 5 }, (_, i) => bucket(`T${i}`, `Topic ${i}`, 20));
    const rows = computeTopicGrowth(recent, prior, { minPriorWorks: 15, topN: 2 });
    expect(rows).toHaveLength(2);
    // Highest growth (T4: 60 vs 20) first.
    expect(rows[0]?.id).toBe("T4");
  });

  it("excludes a shrinking topic — it isn't trending by definition", () => {
    const recent = [bucket("T1", "Shrinking", 40), bucket("T2", "Growing", 60)];
    const prior = [bucket("T1", "Shrinking", 80), bucket("T2", "Growing", 40)];
    const rows = computeTopicGrowth(recent, prior, { minPriorWorks: 15 });
    expect(rows.map((r) => r.id)).toEqual(["T2"]);
  });

  it("returns [] for empty input without throwing", () => {
    expect(computeTopicGrowth([], [])).toEqual([]);
  });
});
