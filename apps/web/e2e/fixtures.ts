import AxeBuilder from "@axe-core/playwright";
import { expect, test as base, type Page } from "@playwright/test";

export interface ProfileCall {
  authorization: string | null;
  body: unknown;
}

export const test = base.extend<{ profileCalls: ProfileCall[] }>({
  profileCalls: async ({ page }, provide) => {
    const calls: ProfileCall[] = [];
    await page.route("**/__api/api/v1/users/profile/sync", async (route) => {
      const request = route.request();
      calls.push({ authorization: await request.headerValue("authorization"), body: request.postDataJSON() });
      await route.fulfill({ json: { status: "synced", uid: "fake" } });
    });
    const violations: string[] = [];
    page.on("console", (message) => {
      if (message.type() === "error") violations.push(message.text());
    });
    await provide(calls);
    // A CSP violation or React error in the console fails the test.
    expect(violations).toEqual([]);
  },
});

export { expect };

/** WCAG 2.2 AA, in the current color scheme. */
export async function expectAccessible(page: Page) {
  const results = await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa", "wcag22aa"]).analyze();
  expect(results.violations.map((v) => `${v.id}: ${v.nodes.map((n) => n.target.join(" ")).join(", ")}`)).toEqual([]);
}

export async function verifyEmail(page: Page, email: string) {
  await page.evaluate((address) => {
    (window as unknown as { __fakeAuth: { verify(email: string): void } }).__fakeAuth.verify(address);
  }, email);
}
