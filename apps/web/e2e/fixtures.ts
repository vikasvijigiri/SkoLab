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
  /** The editor and sharing endpoints (workspaces, templates, documents, invites), served in memory. */
  backend: [
    async ({ page }, provide) => {
      const backend = createFakeBackend(TEMPLATES);
      await serveBackend(page, backend);
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

/** Answers the editor and sharing endpoints for page from backend (one backend can serve several browsers). */
export async function serveBackend(page: Page, backend: FakeBackend) {
  await page.route(/\/__api\/api\/v1\/(workspaces|templates|invites)/, async (route) => {
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
}

/** WCAG 2.2 AA, in the current color scheme. */
export async function expectAccessible(page: Page) {
  // Let entrance animations finish: text caught mid-fade reads as low contrast.
  await page.evaluate(() =>
    Promise.all(
      document
        .getAnimations()
        .filter((animation) => animation.effect?.getComputedTiming().endTime !== Infinity)
        .map((animation) => animation.finished.catch(() => undefined)),
    ),
  );
  const results = await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa", "wcag22aa"]).analyze();
  expect(results.violations.map((v) => `${v.id}: ${v.nodes.map((n) => n.target.join(" ")).join(", ")}`)).toEqual([]);
}

export async function verifyEmail(page: Page, email: string) {
  await page.evaluate((address) => {
    (window as unknown as { __fakeAuth: { verify(email: string): void } }).__fakeAuth.verify(address);
  }, email);
}
