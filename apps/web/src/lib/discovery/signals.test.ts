import { afterEach, describe, expect, it, vi } from "vitest";
import { deriveSignals } from "./signals";
import type { OpenAlexAuthorRaw } from "@/lib/types";

const NOW = 2026;

function author(over: Partial<OpenAlexAuthorRaw> = {}): OpenAlexAuthorRaw {
  return {
    id: "A1",
    display_name: "Test Author",
    orcid: "0000-0001-0000-0001",
    works_count: 40,
    cited_by_count: 900,
    h_index: 18,
    i10_index: 25,
    two_yr_mean_citedness: 3.1,
    institution: "Test University",
    country: "US",
    inst_type: "education",
    counts_by_year: [],
    affiliations: [],
    topics: [],
    ...over,
  };
}

/** works+citations rising each year through `end`. */
function risingCounts(start: number, end: number) {
  const out = [];
  for (let y = start, i = 1; y <= end; y++, i++) {
    out.push({ year: y, works_count: 2, cited_by_count: 20 * i });
  }
  return out;
}

describe("deriveSignals", () => {
  afterEach(() => {
    vi.unstubAllEnvs();
    vi.resetModules();
  });

  it("marks a recently-publishing early-career author active, rising, emerging", () => {
    const s = deriveSignals(
      author({
        h_index: 9,
        counts_by_year: risingCounts(2021, 2025),
        affiliations: [{ institution: "Test University", years: [2021, 2022, 2023, 2024, 2025] }],
      }),
      { now: NOW },
    );
    expect(s.activity).toBe("active");
    expect(s.momentum).toBe("rising");
    expect(s.careerStage).toBe("emerging");
    expect(s.yearsActiveVisible).toBe(5);
    expect(s.activeDecades).toEqual([2020]);
    expect(s.sparkline.length).toBe(5);
  });

  it("marks a long-quiet author dormant and senior", () => {
    const s = deriveSignals(
      author({
        h_index: 60,
        counts_by_year: [
          { year: 2005, works_count: 4, cited_by_count: 120 },
          { year: 2006, works_count: 3, cited_by_count: 110 },
          { year: 2007, works_count: 2, cited_by_count: 90 },
        ],
        affiliations: [{ institution: "Old Institute", years: [2001, 2002, 2003, 2004, 2005, 2006, 2007] }],
      }),
      { now: NOW },
    );
    expect(s.activity).toBe("dormant");
    expect(s.careerStage).toBe("senior");
    expect(s.activeDecades).toEqual([2000]);
  });

  it("treats a deceased-shaped author (no recent activity) as dormant", () => {
    const s = deriveSignals(
      author({
        counts_by_year: [
          { year: 2010, works_count: 1, cited_by_count: 300 },
          { year: 2011, works_count: 0, cited_by_count: 200 },
          { year: 2012, works_count: 0, cited_by_count: 100 },
        ],
      }),
      { now: NOW },
    );
    expect(s.activity).toBe("dormant");
    // citations decaying → cooling
    expect(s.momentum).toBe("cooling");
  });

  it("is total on sparse / empty counts_by_year", () => {
    const s = deriveSignals(author({ counts_by_year: [], affiliations: [], topics: [] }), { now: NOW });
    expect(s.momentum).toBe("steady");
    expect(s.activity).toBe("dormant");
    expect(s.sparkline).toEqual([]);
    expect(s.activeDecades).toEqual([]);
    expect(s.standingPercentile).toBeNull();
  });

  it("reads topic_share for the scoped subfield, else the max", () => {
    const a = author({
      topics: [
        { id: "T1", display_name: "Spin glasses", value: 0.62, subfield_id: "3104", field_id: "31" },
        { id: "T2", display_name: "Optimization", value: 0.20, subfield_id: "2207", field_id: "22" },
      ],
    });
    expect(deriveSignals(a, { now: NOW, scopedSubfieldId: "3104" }).topicalFocus).toBeCloseTo(0.62);
    expect(deriveSignals(a, { now: NOW, scopedSubfieldId: "9999" }).topicalFocus).toBeCloseTo(0.62); // max fallback
  });

  it("uses the injected percentile fn for standing", () => {
    const s = deriveSignals(author({ h_index: 30 }), {
      now: NOW,
      fieldHIndexPercentile: (h) => (h >= 30 ? 92 : 10),
    });
    expect(s.standingPercentile).toBe(92);
  });

  it("honours a DISCOVERY_ACTIVE_WITHIN_YEARS env override (no literal threshold)", async () => {
    vi.stubEnv("DISCOVERY_ACTIVE_WITHIN_YEARS", "4");
    vi.resetModules();
    const { deriveSignals: derive } = await import("./signals");
    // last active 2022, now 2026 → gap 4. Default (2) = winding_down; override (4) = active.
    const a = author({ counts_by_year: [{ year: 2022, works_count: 1, cited_by_count: 10 }] });
    expect(derive(a, { now: NOW }).activity).toBe("active");
  });
});
