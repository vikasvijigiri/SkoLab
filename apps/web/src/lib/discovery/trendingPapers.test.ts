import { describe, expect, it } from "vitest";
import { rankByVelocity } from "./trendingPapers";

const NOW = new Date("2026-09-11T00:00:00.000Z");
const daysAgo = (n: number) => new Date(NOW.getTime() - n * 86400000).toISOString().slice(0, 10);

describe("rankByVelocity", () => {
  it("ranks by citations-per-day, not raw citation count", () => {
    const papers = [
      // 100 citations over 100 days = 1/day
      { id: "old-popular", cited_by_count: 100, publication_date: daysAgo(100) },
      // 30 citations over 10 days = 3/day — fewer total citations but far faster
      { id: "new-hot", cited_by_count: 30, publication_date: daysAgo(10) },
    ];
    const ranked = rankByVelocity(papers, NOW, { minCitations: 3 });
    expect(ranked.map((p) => p.id)).toEqual(["new-hot", "old-popular"]);
  });

  it("drops a paper below the minimum-citations floor (avoids one-lucky-citation noise)", () => {
    const papers = [
      // 1 citation on a 1-day-old paper would score 1.0/day — implausibly "hot".
      { id: "fluke", cited_by_count: 1, publication_date: daysAgo(1) },
      { id: "real", cited_by_count: 10, publication_date: daysAgo(20) },
    ];
    const ranked = rankByVelocity(papers, NOW, { minCitations: 3 });
    expect(ranked.map((p) => p.id)).toEqual(["real"]);
  });

  it("drops a paper with no publication_date rather than crashing", () => {
    const papers = [
      { id: "no-date", cited_by_count: 50 },
      { id: "dated", cited_by_count: 10, publication_date: daysAgo(5) },
    ];
    expect(() => rankByVelocity(papers, NOW, { minCitations: 3 })).not.toThrow();
    expect(rankByVelocity(papers, NOW, { minCitations: 3 }).map((p) => p.id)).toEqual(["dated"]);
  });

  it("floors age at 1 day so a same-day paper doesn't divide by zero", () => {
    const papers = [{ id: "today", cited_by_count: 5, publication_date: daysAgo(0) }];
    expect(() => rankByVelocity(papers, NOW, { minCitations: 3 })).not.toThrow();
    const ranked = rankByVelocity(papers, NOW, { minCitations: 3 });
    expect(ranked).toEqual(papers);
  });

  it("truncates to topN after ranking", () => {
    const papers = Array.from({ length: 5 }, (_, i) => ({
      id: `p${i}`,
      cited_by_count: 10 + i,
      publication_date: daysAgo(10),
    }));
    const ranked = rankByVelocity(papers, NOW, { minCitations: 3, topN: 2 });
    expect(ranked).toHaveLength(2);
    expect(ranked.map((p) => p.id)).toEqual(["p4", "p3"]);
  });

  it("drops a non-article type — e.g. journal-issue paratext mis-attributed a bulk citation count", () => {
    const papers = [
      { id: "paratext", cited_by_count: 561, publication_date: daysAgo(4), type: "paratext" },
      { id: "real-article", cited_by_count: 20, publication_date: daysAgo(30), type: "article" },
    ];
    const ranked = rankByVelocity(papers, NOW, { minCitations: 3 });
    expect(ranked.map((p) => p.id)).toEqual(["real-article"]);
  });

  it("keeps a paper with no type field at all (the field is optional upstream)", () => {
    const papers = [{ id: "no-type", cited_by_count: 10, publication_date: daysAgo(5) }];
    expect(rankByVelocity(papers, NOW, { minCitations: 3 }).map((p) => p.id)).toEqual(["no-type"]);
  });

  it("preserves the full object, not just id/citation fields", () => {
    const papers = [
      { id: "a", cited_by_count: 10, publication_date: daysAgo(5), display_name: "Paper A" },
    ];
    const ranked = rankByVelocity(papers, NOW, { minCitations: 3 });
    expect(ranked[0]?.display_name).toBe("Paper A");
  });
});
