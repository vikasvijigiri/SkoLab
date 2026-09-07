import AxeBuilder from "@axe-core/playwright";
import { expect, test } from "@playwright/test";

/**
 * Post-rollout render + WCAG AA check for the public routes the design contract
 * now covers. Authed routes (home, author, workspace…) redirect to /login
 * without Firebase, so they are verified by the unit suite + build instead.
 * Screenshots -> e2e/__screens__/ (gitignored artifacts).
 */
const ROUTES = [
  { path: "/", name: "landing" },
  { path: "/login", name: "login" },
  { path: "/signup", name: "signup" },
  { path: "/discovery", name: "discovery" },
];

for (const route of ROUTES) {
  for (const theme of ["light", "dark"] as const) {
    test(`${route.name} ${theme} — renders, WCAG AA`, async ({ page }) => {
      await page.emulateMedia({ colorScheme: theme });
      await page.goto(route.path);
      await page.waitForLoadState("networkidle");
      await page.evaluate(async () => {
        for (let y = 0; y < document.body.scrollHeight; y += window.innerHeight) {
          window.scrollTo(0, y);
          await new Promise((r) => setTimeout(r, 100));
        }
        window.scrollTo(0, 0);
      });
      await page.waitForTimeout(250);
      await page.screenshot({
        path: `e2e/__screens__/${route.name}-${theme}.png`,
        fullPage: true,
      });
      const results = await new AxeBuilder({ page })
        .withTags(["wcag2a", "wcag2aa"])
        .exclude("#hero-preview")
        .analyze();
      const blocking = results.violations.filter(
        (v) => v.impact === "serious" || v.impact === "critical",
      );
      expect(blocking, JSON.stringify(blocking.map((v) => v.id), null, 2)).toEqual([]);
    });
  }
}
