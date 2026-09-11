import { describe, expect, it } from "vitest";
import { buildBriefItems } from "./model";

describe("buildBriefItems", () => {
  it("keeps the morning brief ordered by grant, opportunity and journal", () => {
    const items = buildBriefItems({
      topGrant: {
        title: "Grant",
        agency: "NIH",
        agency_color: "#fff",
        days_left: 30,
        field: "Physics",
        match_score: 92,
        amount: "$10k",
        rationale: "Fits",
        url: "https://grant.example",
      },
      topOpportunity: {
        id: "role-1",
        title: "Research role",
        companyOrFunder: "SkoLab",
        type: "JOB",
        tags: ["research"],
        description: "Research role",
        amount: "$120k",
        deadline: "2026-10-01",
        url: "https://role.example",
      },
      topJournal: {
        journal_name: "Nature",
        works_count: 100,
        is_oa: true,
        citation_impact: 9,
        match_score: 88,
        rationale: "Fits",
      },
    });

    expect(items.map((item) => item.key)).toEqual(["grant", "opportunity", "journal"]);
    expect(items[0]?.href).toBe("https://grant.example");
    expect(items[1]?.label).toBe("Role opened");
  });

  it("returns an empty brief when no sources are available", () => {
    expect(buildBriefItems({})).toEqual([]);
  });
});
