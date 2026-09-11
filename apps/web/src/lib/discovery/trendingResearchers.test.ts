import { describe, expect, it } from "vitest";
import { topRisingResearchers } from "./trendingResearchers";
import type { ResearcherResult, ResearcherSignals } from "@/lib/types";

const signals = (over: Partial<ResearcherSignals> = {}): ResearcherSignals => ({
  activity: "active",
  momentum: "steady",
  momentumScore: 0,
  yearsActiveVisible: 10,
  careerStage: "established",
  activeDecades: [2020],
  topicalFocus: 0.3,
  standingPercentile: 50,
  sparkline: [1, 2, 3],
  ...over,
});

const researcher = (id: string, over: Partial<ResearcherResult> = {}): ResearcherResult =>
  ({
    id,
    display_name: id,
    orcid: null,
    institution: "X",
    country: "US",
    instType: "education",
    hIndex: 10,
    i10: 5,
    worksCount: 30,
    citedBy: 200,
    twoYrMean: 2,
    topics: [],
    signals: signals(),
    ...over,
  }) as ResearcherResult;

describe("topRisingResearchers", () => {
  it("keeps only rising researchers, ranked by momentum magnitude, not alphabetically or by fit", () => {
    const rows = [
      researcher("steady-1", { signals: signals({ momentum: "steady", momentumScore: 0 }) }),
      researcher("rising-low", { signals: signals({ momentum: "rising", momentumScore: 0.2 }) }),
      researcher("cooling-1", { signals: signals({ momentum: "cooling", momentumScore: -0.5 }) }),
      researcher("rising-high", { signals: signals({ momentum: "rising", momentumScore: 0.9 }) }),
    ];
    const top = topRisingResearchers(rows, 5);
    expect(top.map((r) => r.id)).toEqual(["rising-high", "rising-low"]);
  });

  it("truncates to topN", () => {
    const rows = Array.from({ length: 8 }, (_, i) =>
      researcher(`r${i}`, { signals: signals({ momentum: "rising", momentumScore: i }) }),
    );
    const top = topRisingResearchers(rows, 3);
    expect(top).toHaveLength(3);
    expect(top.map((r) => r.id)).toEqual(["r7", "r6", "r5"]);
  });

  it("returns [] when nobody is rising", () => {
    const rows = [researcher("a", { signals: signals({ momentum: "steady" }) })];
    expect(topRisingResearchers(rows, 5)).toEqual([]);
  });

  it("does not mutate the input array", () => {
    const rows = [
      researcher("a", { signals: signals({ momentum: "rising", momentumScore: 0.1 }) }),
      researcher("b", { signals: signals({ momentum: "rising", momentumScore: 0.5 }) }),
    ];
    const copy = [...rows];
    topRisingResearchers(rows, 5);
    expect(rows).toEqual(copy);
  });
});
