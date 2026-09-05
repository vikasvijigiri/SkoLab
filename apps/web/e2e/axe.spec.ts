import AxeBuilder from "@axe-core/playwright";
import { expect, test } from "@playwright/test";

for (const path of ["/", "/login"]) {
  test(`${path} has no serious or critical accessibility violations`, async ({ page }) => {
    await page.goto(path);
    await page.waitForLoadState("networkidle");
    const results = await new AxeBuilder({ page })
      .withTags(["wcag2a", "wcag2aa"])
      // #hero-preview (landing page only) is HeroPreview.tsx's decorative
      // product mockup -- aria-hidden and "Purely visual" by its own doc
      // comment, conveying no information to any user. It also fades in via
      // framer-motion on a delay; Chromium's reducedMotion: "reduce"
      // (playwright.config.ts) is supposed to make that instant, but CI kept
      // catching it mid-fade anyway (contrast down to 1.88 on one run, on
      // text that reads "skolab.app/home" in a fake browser chrome bar) --
      // an animation-timing flake on content that was never meant to carry
      // real information, not a real accessibility gap. Excluding it here
      // matches what aria-hidden already declares, rather than chasing a
      // fade-in race a real user's screen reader never sees either way.
      .exclude("#hero-preview")
      .analyze();

    // `color-contrast` used to be excluded here: --text-muted was #9c9ca6
    // (light) / #6c6c74 (dark), 2.54:1 / 3.8:1 against --page-bg -- below
    // the 4.5:1 WCAG AA threshold for normal text on 94 call sites across 36
    // files. Retuned in globals.css to #6b6b6f / #82828a (>=4.5:1 against
    // both --page-bg and --surface, computed via the real contrast-ratio
    // formula, not eyeballed) -- this rule is no longer excluded.
    const blocking = results.violations.filter(
      (v) => v.impact === "serious" || v.impact === "critical",
    );
    expect(blocking, JSON.stringify(blocking.map((v) => v.id), null, 2)).toEqual([]);
  });
}
