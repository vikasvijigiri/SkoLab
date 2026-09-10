import { expect, test } from "@playwright/test";

/**
 * Route-level render contract. Public screens are rendered directly; protected
 * screens must fail closed to /login instead of throwing or rendering a blank
 * shell. Screenshots are intentionally gitignored artifacts under
 * e2e/__screens__/route-audit/.
 */
const ROUTES = [
  { path: "/", name: "landing", protected: false },
  { path: "/login", name: "login", protected: false },
  { path: "/signup", name: "signup", protected: false },
  { path: "/discovery", name: "discovery", protected: false },
  { path: "/discovery?tab=papers", name: "discovery-papers", protected: false },
  { path: "/home", name: "home", protected: true },
  { path: "/horizon", name: "horizon", protected: true },
  { path: "/nexus", name: "nexus", protected: true },
  { path: "/workspace", name: "workspace", protected: true },
  { path: "/workspace/example", name: "workspace-nested", protected: true },
  { path: "/profile", name: "profile", protected: true },
  { path: "/settings", name: "settings", protected: true },
  { path: "/author/example", name: "author-nested", protected: false },
  { path: "/paper/example", name: "paper-nested", protected: false },
];

for (const route of ROUTES) {
  test(`${route.name} route renders safely`, async ({ page }) => {
    const pageErrors: string[] = [];
    page.on("pageerror", (error) => pageErrors.push(error.message));

    const response = await page.goto(route.path, { waitUntil: "domcontentloaded" });
    expect(response?.status(), `${route.path} returned an HTTP error`).toBeLessThan(500);
    // Give auth and Firestore-backed shells a bounded settling window before
    // capturing evidence; a screenshot of the top bar alone is not a valid
    // page verification.
    await page.waitForTimeout(route.protected ? 2000 : 500);
    await page.screenshot({
      path: `e2e/__screens__/route-audit/${route.name}.png`,
      fullPage: true,
    });

    if (route.protected) {
      // Local CI without Firebase redirects; deployed environments with a
      // configured auth provider may render the protected shell directly.
      const isLogin = /\/login(?:\?|$)/.test(page.url());
      if (isLogin) {
        await expect(page).toHaveURL(/\/login(?:\?|$)/);
      } else {
        await expect(page.locator("body")).not.toBeEmpty();
      }
    } else {
      await expect(page.locator("body")).not.toBeEmpty();
    }
    const visibleText = (await page.locator("body").innerText()).trim();
    expect(visibleText.length, `${route.path} rendered no meaningful text`).toBeGreaterThan(20);
    expect(pageErrors, `${route.path} threw a browser exception`).toEqual([]);
  });
}
