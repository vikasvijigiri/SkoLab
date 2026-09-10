import { describe, expect, it } from "vitest";
import {
  attachStandingPercentiles,
  bareId,
  buildAuthorsFilter,
  mapAuthorRow,
  mapSort,
  type OaAuthorRow,
} from "./mapAuthor";
import type { ResearcherResult } from "@/lib/types";

describe("bareId", () => {
  it("reduces every id form to the last segment", () => {
    expect(bareId("https://openalex.org/subfields/1102")).toBe("1102");
    expect(bareId("subfields/1102")).toBe("1102");
    expect(bareId("T10888")).toBe("T10888");
    expect(bareId(null)).toBeNull();
  });
});

describe("buildAuthorsFilter", () => {
  it("OR-joins topic ids into one clause and comma-joins the rest", () => {
    expect(
      buildAuthorsFilter({ topicIds: ["T1", "T2"], hasOrcid: true, hIndexMax: 40 }),
    ).toBe("topics.id:T1|T2,summary_stats.h_index:<40,has_orcid:true");
  });
  it("lower-cases the country code and passes instType through", () => {
    expect(buildAuthorsFilter({ topicIds: ["T1"], country: "US", instType: "education" })).toBe(
      "topics.id:T1,last_known_institutions.country_code:us,last_known_institutions.type:education",
    );
  });
  it("emits worksMin as a > clause and omits absent params", () => {
    expect(buildAuthorsFilter({ topicIds: ["T1"], worksMin: 10 })).toBe(
      "topics.id:T1,works_count:>10",
    );
    expect(buildAuthorsFilter({ topicIds: ["T1"] })).toBe("topics.id:T1");
  });
});

describe("mapSort", () => {
  it("maps the server-orderable sorts and defaults the rest to citations", () => {
    expect(mapSort("standing")).toBe("summary_stats.h_index:desc");
    expect(mapSort("recent")).toBe("summary_stats.2yr_mean_citedness:desc");
    expect(mapSort("fit")).toBe("cited_by_count:desc");
    expect(mapSort("momentum")).toBe("cited_by_count:desc");
    expect(mapSort(undefined)).toBe("cited_by_count:desc");
  });
});

const row = (over: Partial<OaAuthorRow> = {}): OaAuthorRow => ({
  id: "https://openalex.org/A1",
  display_name: "Jane Roe",
  orcid: "https://orcid.org/0000-0002-0000-0002",
  works_count: 60,
  cited_by_count: 2000,
  summary_stats: { h_index: 22, i10_index: 40, "2yr_mean_citedness": 4.2 },
  last_known_institutions: [{ display_name: "MIT", country_code: "us", type: "education" }],
  affiliations: [{ institution: { display_name: "MIT" }, years: [2018, 2020, 2022] }],
  counts_by_year: [
    { year: 2022, works_count: 5, cited_by_count: 200 },
    { year: 2023, works_count: 6, cited_by_count: 240 },
    { year: 2024, works_count: 7, cited_by_count: 300 },
  ],
  topics: [
    { id: "https://openalex.org/T1", display_name: "Spin glasses", count: 30, subfield: { id: "https://openalex.org/subfields/3104" }, field: { id: "https://openalex.org/fields/31" } },
    { id: "https://openalex.org/T2", display_name: "Optimization", count: 10, subfield: { id: "https://openalex.org/subfields/2207" }, field: { id: "https://openalex.org/fields/22" } },
  ],
  ...over,
});

describe("mapAuthorRow", () => {
  it("normalises ids, derives topic share from count, upper-cases country", () => {
    const r = mapAuthorRow(row(), { now: 2026, scopedSubfieldId: "3104" });
    expect(r.id).toBe("A1");
    expect(r.orcid).toBe("0000-0002-0000-0002");
    expect(r.country).toBe("US");
    expect(r.instType).toBe("education");
    expect(r.hIndex).toBe(22);
    expect(r.topics).toEqual(["Spin glasses", "Optimization"]);
    // 30 / (30+10) = 0.75 for the scoped subfield
    expect(r.signals.topicalFocus).toBeCloseTo(0.75);
    expect(r.signals.activity).toBe("active");
  });

  it("tolerates a bare-minimum row (no summary_stats / topics / counts)", () => {
    const r = mapAuthorRow({ id: "A9", display_name: "Min", orcid: null }, { now: 2026 });
    expect(r.hIndex).toBe(0);
    expect(r.topics).toEqual([]);
    expect(r.country).toBeNull();
    expect(r.signals.standingPercentile).toBeNull();
  });
});

describe("attachStandingPercentiles", () => {
  it("assigns a higher percentile to a higher h-index across the page", () => {
    const base = mapAuthorRow(row(), { now: 2026 });
    const rows: ResearcherResult[] = [
      { ...base, id: "a", hIndex: 5 },
      { ...base, id: "b", hIndex: 20 },
      { ...base, id: "c", hIndex: 50 },
      { ...base, id: "d", hIndex: 80 },
    ];
    const out = attachStandingPercentiles(rows);
    const p = new Map(out.map((r) => [r.id, r.signals.standingPercentile ?? 0]));
    expect(p.get("a")!).toBeLessThan(p.get("c")!);
    expect(p.get("d")).toBe(100);
  });
  it("is a no-op on a single row", () => {
    const one = [mapAuthorRow(row(), { now: 2026 })];
    expect(attachStandingPercentiles(one)[0]?.signals.standingPercentile).toBeNull();
  });
});
