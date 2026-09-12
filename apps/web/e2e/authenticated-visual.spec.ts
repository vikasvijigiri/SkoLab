import AxeBuilder from "@axe-core/playwright";
import { expect, test } from "@playwright/test";

/**
 * Protected-shell visual proof. Run with PLAYWRIGHT_AUTH=1; the test server
 * injects a deterministic local user through AuthProvider without Firebase
 * credentials. It is intentionally skipped in the normal unauthenticated
 * suite so the redirect contract remains covered by route-audit.spec.ts.
 */
test.skip(process.env.PLAYWRIGHT_AUTH !== "1", "requires the local Playwright auth session");

const ROUTES = [
  { path: "/home", name: "home" },
  { path: "/discovery", name: "discovery-authenticated" },
  { path: "/horizon", name: "horizon" },
  { path: "/workspace", name: "colab" },
];

for (const route of ROUTES) {
  test(`${route.name} renders the protected shell`, async ({ page }) => {
    await page.goto(route.path);
    // The protected pages intentionally keep query/realtime connections open;
    // networkidle would therefore wait forever. DOM readiness plus the shell
    // assertion below is the stable visual gate.
    await page.waitForLoadState("domcontentloaded");
    await expect(page.locator("header").first()).toBeVisible();
    await expect(page.getByRole("navigation", { name: "Research categories" })).toBeVisible();
    await page.screenshot({ path: `e2e/__screens__/${route.name}-authenticated.png`, fullPage: true });

    const results = await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa"]).analyze();
    const blocking = results.violations.filter((v) => v.impact === "serious" || v.impact === "critical");
    expect(blocking, JSON.stringify(blocking.map((v) => v.id), null, 2)).toEqual([]);
  });
}
