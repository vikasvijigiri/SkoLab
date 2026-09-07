import AxeBuilder from "@axe-core/playwright";
import { expect, test } from "@playwright/test";

/**
 * Round 1 identity sign-off: render the rebuilt Landing at every breakpoint and
 * both themes, and re-assert WCAG AA at each. Screenshots land in
 * `e2e/__screens__/` as artifacts (not committed baselines — there is no
 * visual-diff gate in Round 1). `#hero-preview` is excluded from axe for the
 * same reason `axe.spec.ts` excludes it (decorative, aria-hidden, fades in).
 */

const VIEWPORTS = [
  { name: "desktop", width: 1440, height: 900 },
  { name: "tablet", width: 834, height: 1112 },
  { name: "mobile", width: 390, height: 844 },
] as const;

for (const vp of VIEWPORTS) {
  for (const theme of ["light", "dark"] as const) {
    test(`landing — ${vp.name} ${theme} — renders and passes WCAG AA`, async ({ page }) => {
      await page.setViewportSize({ width: vp.width, height: vp.height });
      // Drive the theme through prefers-color-scheme (globals.css keeps the
      // @media block synced) — no localStorage, so ThemeToggle's SSR state
      // stays consistent and there is no hydration mismatch.
      await page.emulateMedia({ colorScheme: theme });
      await page.goto("/");
      await page.waitForLoadState("networkidle");
      await expect(page.getByRole("heading", { name: /real standing/i })).toBeVisible();

      // The mid-page sections animate in via framer-motion `whileInView`
      // (<Reveal>). Scroll the whole page so every one triggers, then return
      // to the top, so the screenshot and the axe scan see the settled DOM.
      await page.evaluate(async () => {
        for (let y = 0; y < document.body.scrollHeight; y += window.innerHeight) {
          window.scrollTo(0, y);
          await new Promise((r) => setTimeout(r, 120));
        }
        window.scrollTo(0, 0);
      });
      await page.waitForTimeout(300);

      await page.screenshot({
        path: `e2e/__screens__/landing-${vp.name}-${theme}.png`,
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
