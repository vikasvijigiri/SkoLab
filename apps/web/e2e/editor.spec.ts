import { readFileSync } from "node:fs";
import type { Page } from "@playwright/test";
import { expect, expectAccessible, test } from "./fixtures";

// A real pdflatex output (the AMS article template, compiled by the production image).
const PDF = readFileSync(new URL("./assets/ams-article.pdf", import.meta.url)).toString("base64");

interface CompileCall {
  authorization: string | null;
  body: { latex_source: string; engine: string };
}

async function stubCompile(page: Page, answer: (call: number) => object = () => ({ status: "compiled", pdf_base64: PDF, log: "" })) {
  const calls: CompileCall[] = [];
  await page.route("**/__api/api/v1/colab/compile", async (route) => {
    const request = route.request();
    calls.push({ authorization: await request.headerValue("authorization"), body: request.postDataJSON() as CompileCall["body"] });
    await route.fulfill({ json: answer(calls.length) });
  });
  return calls;
}

async function signIn(page: Page) {
  await page.goto("/sign-in");
  await page.getByRole("button", { name: "Continue with Google" }).click();
  await expect(page.getByRole("heading", { name: "Welcome, Ada Lovelace" })).toBeVisible();
}

test("a template opens in the editor, compiles and previews under the production CSP", async ({ page, profileCalls, isMobile }) => {
  const calls = await stubCompile(page);
  await signIn(page);
  await page.getByRole("button", { name: "Use the IEEE Conference Paper template" }).click();

  await expect(page).toHaveURL(/\/editor\/[0-9a-f-]{36}$/);
  await expect(page.getByLabel("Document title")).toHaveValue("IEEE Conference Paper");
  // pdf.js drew the page on a canvas.
  await expect(page.getByRole("img", { name: "Page 1 of 1" })).toBeVisible();
  expect(calls).toHaveLength(1);
  expect(calls[0]?.authorization).toBe("Bearer fake-token:google-ada");
  expect(calls[0]?.body.latex_source).toContain("\\documentclass[conference]{IEEEtran}");

  // Edit the source, then compile.
  if (isMobile) await page.getByRole("tab", { name: "Source" }).click();
  const source = page.getByRole("textbox", { name: "LaTeX source" });
  await source.click();
  await page.keyboard.press("ControlOrMeta+Home");
  await page.keyboard.type("% edited in the browser test\n");
  // Phones have no Ctrl key; they use the button.
  if (isMobile) await page.getByRole("button", { name: "Compile", exact: true }).click();
  else await page.keyboard.press("ControlOrMeta+Enter");
  await expect.poll(() => calls.length).toBe(2);
  expect(calls[1]?.body.latex_source).toContain("% edited in the browser test");
  await expect(page.getByRole("img", { name: "Page 1 of 1" })).toBeVisible();

  // The edit survives a reload, and the document is listed at home.
  await expect(page.getByText("Saved in this browser")).toBeAttached();
  await page.reload();
  if (isMobile) await page.getByRole("tab", { name: "Source" }).click();
  await expect(page.getByText("% edited in the browser test")).toBeVisible();
  await page.getByRole("link", { name: "Back to your documents" }).click();
  await expect(page.getByRole("link", { name: "IEEE Conference Paper" })).toBeVisible();
  expect(profileCalls.length).toBeGreaterThan(0);
});

test("compile errors point at their line", async ({ page, profileCalls, isMobile }) => {
  await stubCompile(page, (call) =>
    call === 1
      ? { status: "compiled", pdf_base64: PDF, log: "" }
      : { status: "error", errors: ["line 2: Undefined control sequence."], log: "./main.tex:2: Undefined control sequence." },
  );
  await signIn(page);
  await page.getByRole("button", { name: "Use the Blank Article template" }).click();
  await expect(page.getByRole("img", { name: "Page 1 of 1" })).toBeVisible();
  await page.getByRole("button", { name: "Compile", exact: true }).click();
  await expect(page.getByText("Compilation failed; showing the last good PDF.")).toBeVisible();
  await page.getByRole("button", { name: "line 2: Undefined control sequence." }).click();
  if (isMobile) await expect(page.getByRole("tab", { name: "Source" })).toHaveAttribute("aria-selected", "true");
  await expect(page.getByRole("textbox", { name: "LaTeX source" })).toBeFocused();
  expect(profileCalls.length).toBeGreaterThan(0);
});

for (const scheme of ["light", "dark"] as const) {
  test(`the home page and editor meet WCAG 2.2 AA in ${scheme} mode`, async ({ page, profileCalls }) => {
    await stubCompile(page);
    await page.emulateMedia({ colorScheme: scheme, reducedMotion: "reduce" });
    await signIn(page);
    await expect(page.getByText("You're all set")).toBeVisible();
    await expectAccessible(page);
    await page.getByRole("button", { name: "Use the Elsevier Journal Article template" }).click();
    await expect(page.getByRole("img", { name: "Page 1 of 1" })).toBeVisible();
    await expectAccessible(page);
    expect(profileCalls.length).toBeGreaterThan(0);
  });
}

test("the editor fits a phone screen", async ({ page, isMobile, profileCalls }) => {
  test.skip(!isMobile, "phone layout only");
  await stubCompile(page);
  await signIn(page);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await page.getByRole("button", { name: "Use the Beamer Conference Talk template" }).click();
  await expect(page.getByRole("img", { name: "Page 1 of 1" })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  expect(profileCalls.length).toBeGreaterThan(0);
});
