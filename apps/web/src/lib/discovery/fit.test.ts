import { describe, expect, it } from "vitest";
import { jaccardSim, scoreFit, type FitViewer } from "./fit";
import type { ResearcherResult, ResearcherSignals } from "@/lib/types";

const signals = (over: Partial<ResearcherSignals> = {}): ResearcherSignals => ({
  activity: "active",
  momentum: "steady",
  yearsActiveVisible: 10,
  careerStage: "established",
  activeDecades: [2010, 2020],
  topicalFocus: 0.5,
  standingPercentile: 50,
  sparkline: [1, 2, 3],
  ...over,
});

const cand = (over: Partial<ResearcherResult> = {}): ResearcherResult => ({
  id: "A2",
  display_name: "Candidate",
  orcid: null,
  institution: "MIT",
  country: "US",
  instType: "education",
  hIndex: 20,
  i10: 30,
  worksCount: 50,
  citedBy: 1200,
  twoYrMean: 3,
  topics: ["Spin glasses", "Replica theory", "Optimization"],
  signals: signals(),
  ...over,
});

describe("jaccardSim", () => {
  it("is 0 when either side is empty", () => {
    expect(jaccardSim([], ["a"])).toBe(0);
    expect(jaccardSim(["a"], [])).toBe(0);
  });
  it("rewards exact and partial (substring) overlap", () => {
    expect(jaccardSim(["spin glasses"], ["spin glasses"])).toBe(1);
    expect(jaccardSim(["spin"], ["spin glasses"])).toBeGreaterThan(0);
  });
});

describe("scoreFit", () => {
  const viewer: FitViewer = { expertise: ["spin glasses", "replica theory"], institution: "MIT" };

  it("ranks a high-overlap same-institution candidate above a no-overlap one", () => {
    const hi = scoreFit(viewer, cand());
    const lo = scoreFit(viewer, cand({ institution: "Elsewhere", topics: ["Marine biology", "Coral reefs"] }));
    expect(hi.score).toBeGreaterThan(lo.score);
    expect(hi.why).toMatch(/shared topic/);
    expect(hi.why).toMatch(/same institution/);
  });

  it("clamps to 0–100 and never throws on a cold viewer", () => {
    const cold = scoreFit({ expertise: [] }, cand({ signals: signals({ topicalFocus: 0.9, standingPercentile: 80 }) }));
    expect(cold.score).toBeGreaterThanOrEqual(0);
    expect(cold.score).toBeLessThanOrEqual(100);
    expect(cold.why.length).toBeGreaterThan(0);
  });

  it("cold viewer: a more field-focused candidate outranks a less-focused one", () => {
    const focused = scoreFit({ expertise: [] }, cand({ signals: signals({ topicalFocus: 0.85 }) }));
    const broad = scoreFit({ expertise: [] }, cand({ signals: signals({ topicalFocus: 0.15 }) }));
    expect(focused.score).toBeGreaterThan(broad.score);
  });

  it("handles missing institution on both sides", () => {
    expect(() =>
      scoreFit({ expertise: ["x"] }, cand({ institution: "" })),
    ).not.toThrow();
  });
});
