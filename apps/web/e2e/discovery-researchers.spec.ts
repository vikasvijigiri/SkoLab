import AxeBuilder from "@axe-core/playwright";
import { expect, test } from "@playwright/test";

/**
 * Fit-first researcher surface — render + WCAG AA in both themes.
 *
 * Covers the public `/discovery` route (an unresolved viewer → leaderboard +
 * "explore another area" drilldown). The authed fit-ranked grid can't be reached
 * without Firebase, so it is verified by the unit suite
 * (`src/components/discovery/*`, `src/app/(app)/discovery/page.test.tsx`) plus
 * one-off screenshots taken from a temporary `/discovery-preview` route during
 * implementation (route not committed).
 *
 * Screenshots → e2e/__screens__/ (gitignored artifacts).
 */

for (const theme of ["light", "dark"] as const) {
  test(`discovery — ${theme} — renders, WCAG AA`, async ({ page }) => {
    const errors: string[] = [];
    page.on("pageerror", (e) => errors.push(e.message));

    await page.emulateMedia({ colorScheme: theme });
    // globals.css keys dark off BOTH prefers-color-scheme and a stored choice;
    // the inline <head> script reads localStorage first, so set it too.
    await page.addInitScript((t) => {
      try {
        localStorage.setItem("skolab-theme", t as string);
      } catch {
        /* ignore */
      }
    }, theme);
    await page.goto("/discovery", { waitUntil: "domcontentloaded" });
    await page.waitForLoadState("networkidle").catch(() => {});
    await page.waitForTimeout(1200);
    await page.evaluate(async () => {
      for (let y = 0; y < document.body.scrollHeight; y += window.innerHeight) {
        window.scrollTo(0, y);
        await new Promise((r) => setTimeout(r, 100));
      }
      window.scrollTo(0, 0);
    });
    await page.waitForTimeout(250);

    await page.screenshot({ path: `e2e/__screens__/discovery-fitfirst-${theme}.png`, fullPage: true });

    expect(errors, errors.join("\n")).toEqual([]);
    await expect(page.locator("body")).not.toBeEmpty();

    const results = await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa"]).analyze();
    const blocking = results.violations.filter(
      (v) => v.impact === "serious" || v.impact === "critical",
    );
    expect(blocking, JSON.stringify(blocking.map((v) => v.id), null, 2)).toEqual([]);
  });
}
