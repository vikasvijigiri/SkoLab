import { readFileSync } from "node:fs";
import AxeBuilder from "@axe-core/playwright";
import { expect, test as base, type Page } from "@playwright/test";
import { createFakeBackend, TEST_TEMPLATES, type FakeBackend, type FakeForm } from "../src/test/fakeBackend";

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
    const headers = request.headers();
    const raw = request.postDataBuffer();
    const type = headers["content-type"] ?? "";
    const answer = backend.handle({
      method: request.method(),
      url: url.pathname.replace(/^\/__api/, "") + url.search,
      headers,
      body: !raw ? null : type.startsWith("multipart/form-data") ? parseMultipart(raw, type) : (JSON.parse(raw.toString("utf8")) as unknown),
    });
    if (!answer) return route.fallback();
    if (ArrayBuffer.isView(answer.body)) {
      const raw = answer.body;
      await route.fulfill({ status: answer.status, ...(answer.headers ? { headers: answer.headers } : {}), body: Buffer.from(raw.buffer, raw.byteOffset, raw.byteLength) });
      return;
    }
    await route.fulfill({ status: answer.status, ...(answer.headers ? { headers: answer.headers } : {}), ...(answer.status === 204 ? {} : { json: answer.body }) });
  });
}

/** Decodes a multipart/form-data body into the fake backend's form. */
function parseMultipart(body: Buffer, contentType: string): FakeForm {
  const boundary = /boundary=(?:"([^"]+)"|([^;]+))/.exec(contentType);
  const form: FakeForm = { form: true, fields: {}, files: [], size: body.length };
  const delimiter = Buffer.from(`--${boundary?.[1] ?? boundary?.[2] ?? ""}`);
  let at = body.indexOf(delimiter);
  while (at >= 0) {
    const start = at + delimiter.length + 2; // past the boundary and its CRLF
    const next = body.indexOf(delimiter, start);
    if (next < 0) break;
    const part = body.subarray(start, next - 2); // without the CRLF before the next boundary
    const split = part.indexOf("\r\n\r\n");
    const head = part.subarray(0, split).toString("utf8");
    const content = part.subarray(split + 4);
    const field = /name="([^"]*)"/.exec(head)?.[1] ?? "";
    const filename = /filename="([^"]*)"/.exec(head)?.[1];
    if (filename === undefined) form.fields[field] = content.toString("utf8");
    else form.files.push({ field, name: filename, type: /content-type:\s*([^\r\n]+)/i.exec(head)?.[1] ?? "", bytes: new Uint8Array(content) });
    at = next;
  }
  return form;
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
