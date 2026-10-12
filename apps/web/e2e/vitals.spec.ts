import { expect, test } from "./fixtures";

// Real-user page speed: the sign-in page reports its Core Web Vitals to the
// API, under the shipped CSP, with nothing that identifies the visitor.
test("a page reports its Core Web Vitals when the visitor leaves", async ({ page, profileCalls, vitals }) => {
  await page.goto("/sign-in?next=%2Feditor%2Fprivate-id");
  await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
  // An interaction, so INP has something to measure.
  await page.getByLabel("Email").click();
  await page.keyboard.type("a");
  // Switching tabs hides the page, which makes LCP, CLS and INP final.
  await page.evaluate(() => {
    Object.defineProperty(document, "visibilityState", { configurable: true, get: () => "hidden" });
    document.dispatchEvent(new Event("visibilitychange"));
  });
  // INP needs an interaction slow enough to register, so it isn't required.
  const names = () => [...new Set(vitals.flatMap((report) => (report as { metrics: { name: string }[] }).metrics.map((m) => m.name)))];
  await expect.poll(names).toEqual(expect.arrayContaining(["CLS", "FCP", "LCP", "TTFB"]));
  for (const report of vitals) {
    expect(report).toEqual({ page: "/sign-in", metrics: expect.any(Array) as unknown });
    for (const metric of (report as { metrics: { name: string; value: number }[] }).metrics) {
      expect(Object.keys(metric).sort()).toEqual(["name", "value"]);
      expect(metric.value).toBeGreaterThanOrEqual(0);
    }
  }
  expect(profileCalls).toEqual([]);
});
