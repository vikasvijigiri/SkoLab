import { readFileSync } from "node:fs";
import AxeBuilder from "@axe-core/playwright";
import { expect, test as base, type Page } from "@playwright/test";
import { createFakeBackend, TEST_TEMPLATES, type FakeBackend } from "../src/test/fakeBackend";

export interface ProfileCall {
  authorization: string | null;
  body: unknown;
}

// The real template sources the gateway serves (services/backend-go/internal/templates/files).
const TEMPLATES = TEST_TEMPLATES.map((template) => ({
  ...template,
  source: readFileSync(new URL(`../../../services/backend-go/internal/templates/files/${template.id}.tex`, import.meta.url), "utf8"),
}));

export const test = base.extend<{ profileCalls: ProfileCall[]; backend: FakeBackend }>({
  /** The editor endpoints (workspaces, templates, documents), served in memory. */
  backend: [
    async ({ page }, provide) => {
    const backend = createFakeBackend(TEMPLATES);
    await page.route(/\/__api\/api\/v1\/(workspaces|templates)/, async (route) => {
      const request = route.request();
      const url = new URL(request.url());
      const answer = backend.handle({
        method: request.method(),
        url: url.pathname.replace(/^\/__api/, "") + url.search,
        headers: request.headers(),
        body: request.postData() ? (request.postDataJSON() as unknown) : null,
      });
      if (!answer) return route.fallback();
      await route.fulfill({ status: answer.status, ...(answer.headers ? { headers: answer.headers } : {}), ...(answer.status === 204 ? {} : { json: answer.body }) });
    });
    await provide(backend);
    },
    // Every signed-in page reads documents and templates.
    { auto: true },
  ],
  profileCalls: async ({ page }, provide) => {
    const calls: ProfileCall[] = [];
    await page.route("**/__api/api/v1/users/profile/sync", async (route) => {
      const request = route.request();
      calls.push({ authorization: await request.headerValue("authorization"), body: request.postDataJSON() });
      await route.fulfill({ json: { status: "synced", uid: "fake" } });
    });
    const violations: string[] = [];
    page.on("console", (message) => {
      // A 409 version_conflict is an answer the editor handles, but Chromium still logs it.
      if (message.type() === "error" && !/status of 409 \(Conflict\)/.test(message.text())) violations.push(message.text());
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
