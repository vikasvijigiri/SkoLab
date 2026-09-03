import { chromium } from "@playwright/test";

const BASE = "http://localhost:3100";
const OUT = process.argv[2] || ".";
const routes = ["/", "/login", "/signup", "/onboarding", "/home"];
const viewports = [
  { name: "desktop", width: 1440, height: 900 },
  { name: "mobile", width: 390, height: 844 },
];
const themes = ["light", "dark"];

const browser = await chromium.launch();
for (const theme of themes) {
  for (const vp of viewports) {
    const ctx = await browser.newContext({
      viewport: { width: vp.width, height: vp.height },
      colorScheme: theme,
      deviceScaleFactor: 2,
    });
    const page = await ctx.newPage();
    for (const route of routes) {
      const slug = route === "/" ? "root" : route.replaceAll("/", "");
      try {
        await page.goto(BASE + route, { waitUntil: "networkidle", timeout: 20000 });
      } catch {
        await page.goto(BASE + route, { waitUntil: "domcontentloaded", timeout: 20000 });
      }
      await page.waitForTimeout(1500);
      const file = `${OUT}/${theme}-${vp.name}-${slug}.png`;
      await page.screenshot({ path: file, fullPage: true });
      console.log("saved", file, "->", page.url());
    }
    await ctx.close();
  }
}
await browser.close();
console.log("done");
