import { describe, expect, it } from "vitest";
import { computeProfileStrength, type ProfileStrengthContext } from "./strength";

const FULL: ProfileStrengthContext = {
  name: "Ada Lovelace",
  hasLinkedAuthor: true,
  researchFocus: "Analytical Engines",
  academicStatus: "Professor",
  about: "First programmer.",
  expertiseCount: 3,
  skillsCount: 2,
};

describe("computeProfileStrength", () => {
  it("scores an empty profile at 0 / Beginner with every step remaining", () => {
    const r = computeProfileStrength({ hasLinkedAuthor: false });
    expect(r.percent).toBe(0);
    expect(r.tier).toBe("Beginner");
    expect(r.remaining).toHaveLength(6);
    // highest-weight step first
    expect(r.remaining[0]?.key).toBe("link");
  });

  it("scores a fully populated profile at 100 / All-Star with nothing remaining", () => {
    const r = computeProfileStrength(FULL);
    expect(r.percent).toBe(100);
    expect(r.tier).toBe("All-Star");
    expect(r.remaining).toHaveLength(0);
  });

  it("sums only the completed steps' weights", () => {
    // link (30) + focus (20) = 50
    const r = computeProfileStrength({
      hasLinkedAuthor: true,
      researchFocus: "Physics",
    });
    expect(r.percent).toBe(50);
    expect(r.tier).toBe("Intermediate");
  });

  it("treats the sign-up default academic status as unset", () => {
    const r = computeProfileStrength({ ...FULL, academicStatus: "Researcher" });
    expect(r.steps.find((s) => s.key === "status")?.done).toBe(false);
    expect(r.percent).toBe(85);
  });

  it("treats whitespace-only fields as unset", () => {
    const r = computeProfileStrength({ ...FULL, about: "   ", researchFocus: "" });
    expect(r.percent).toBe(100 - 15 - 20);
  });

  it("puts the tier boundary at 70 (Advanced) and 40 (Intermediate)", () => {
    // link 30 + status 15 + about 15 + name 10 = 70
    const at70 = computeProfileStrength({
      hasLinkedAuthor: true,
      academicStatus: "Postdoc",
      about: "x",
      name: "y",
    });
    expect(at70.percent).toBe(70);
    expect(at70.tier).toBe("Advanced");
    // focus 20 + status 15 + name 10 = 45 -> still Intermediate; drop name -> 35 Beginner
    const at45 = computeProfileStrength({
      hasLinkedAuthor: false,
      researchFocus: "z",
      academicStatus: "Postdoc",
      name: "y",
    });
    expect(at45.tier).toBe("Intermediate");
    const at35 = computeProfileStrength({ ...at45Ctx(), name: "" });
    expect(at35.percent).toBe(35);
    expect(at35.tier).toBe("Beginner");
  });

  it("counts research areas from expertise OR skills alone", () => {
    expect(
      computeProfileStrength({ hasLinkedAuthor: false, expertiseCount: 1 }).steps.find(
        (s) => s.key === "areas",
      )?.done,
    ).toBe(true);
    expect(
      computeProfileStrength({ hasLinkedAuthor: false, skillsCount: 2 }).steps.find(
        (s) => s.key === "areas",
      )?.done,
    ).toBe(true);
  });
});

function at45Ctx(): ProfileStrengthContext {
  return {
    hasLinkedAuthor: false,
    researchFocus: "z",
    academicStatus: "Postdoc",
    name: "y",
  };
}
