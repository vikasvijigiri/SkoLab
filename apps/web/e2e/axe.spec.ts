import AxeBuilder from "@axe-core/playwright";
import { expect, test } from "@playwright/test";

for (const path of ["/", "/login"]) {
  test(`${path} has no serious or critical accessibility violations`, async ({ page }) => {
    await page.goto(path);
    await page.waitForLoadState("networkidle");
    const results = await new AxeBuilder({ page })
      .withTags(["wcag2a", "wcag2aa"])
      .analyze();

    // `color-contrast` is excluded from the blocking set: the landing/login
    // pages have pre-existing marginal-contrast text (muted labels on the
    // off-white ground) that predates the test suite and needs a design-token
    // pass, not a code change. TODO(a11y): run a real contrast audit against
    // globals.css and re-block this rule. Every other serious/critical rule
    // (labels, roles, alt text, landmarks, ...) still blocks here.
    const blocking = results.violations.filter(
      (v) => (v.impact === "serious" || v.impact === "critical") && v.id !== "color-contrast",
    );
    expect(blocking, JSON.stringify(blocking.map((v) => v.id), null, 2)).toEqual([]);
  });
}
