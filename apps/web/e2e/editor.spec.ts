import { readFileSync } from "node:fs";
import type { Page } from "@playwright/test";
import { expect, expectAccessible, serveBackend, test, verifyEmail } from "./fixtures";

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

test("a journal template opens in the editor, compiles, previews and saves to the account", async ({ page, profileCalls, backend, isMobile }) => {
  const calls = await stubCompile(page);
  await signIn(page);
  await page.getByRole("button", { name: "Mathematics" }).click();
  await page.getByRole("button", { name: "Use the AMS Journal Article (amsart) template" }).click();

  await expect(page).toHaveURL(/\/editor\/[0-9a-f-]{36}$/);
  await expect(page.getByLabel("Document title")).toHaveValue("AMS Journal Article (amsart)");
  // pdf.js drew the page on a canvas.
  await expect(page.getByRole("img", { name: "Page 1 of 1" })).toBeVisible();
  expect(calls).toHaveLength(1);
  expect(calls[0]?.authorization).toBe("Bearer fake-token:google-ada");
  expect(calls[0]?.body.latex_source).toContain("\\documentclass{amsart}");
  const id = new URL(page.url()).pathname.split("/").pop() ?? "";
  expect(backend.document(id)).toMatchObject({ template_id: "ams-article", version: 1 });

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

  // The edit is saved to the account, survives a reload, and the document is listed at home.
  await expect.poll(() => backend.document(id)?.source ?? "").toContain("% edited in the browser test");
  await expect(page.getByText("All changes saved")).toBeAttached();
  await page.reload();
  if (isMobile) await page.getByRole("tab", { name: "Source" }).click();
  await expect(page.getByText("% edited in the browser test")).toBeVisible();
  await page.getByRole("link", { name: "Back to your documents" }).click();
  await expect(page.getByRole("link", { name: "AMS Journal Article (amsart)" })).toBeVisible();
  expect(profileCalls.length).toBeGreaterThan(0);
});

test("a co-author's save is not overwritten", async ({ page, profileCalls, backend, isMobile }) => {
  await stubCompile(page);
  await signIn(page);
  await page.getByRole("button", { name: "Use the Nature template" }).click();
  await expect(page.getByRole("img", { name: "Page 1 of 1" })).toBeVisible();
  const id = new URL(page.url()).pathname.split("/").pop() ?? "";
  backend.saveAs("grace", id, "\\documentclass{nature}\n% grace's version\n");

  if (isMobile) await page.getByRole("tab", { name: "Source" }).click();
  await page.getByRole("textbox", { name: "LaTeX source" }).click();
  await page.keyboard.type("% mine");
  await expect(page.getByText("Someone else saved this document while you were editing.")).toBeVisible();
  expect(backend.document(id)?.source).toContain("% grace's version");
  await page.getByRole("button", { name: "Load their version" }).click();
  await expect(page.getByText("% grace's version")).toBeAttached();
  await expect(page.getByText(/Someone else saved/)).toHaveCount(0);
  expect(profileCalls.length).toBeGreaterThan(0);
});

test("a shared document opens read-only for a viewer", async ({ page, profileCalls, backend, isMobile }) => {
  await stubCompile(page);
  const id = backend.seed("grace", "Grace's paper", { source: "\\documentclass{amsart}\n\\begin{document}\nShared\n\\end{document}\n", templateId: "ams-article", members: { "google-ada": "viewer" } });
  await signIn(page);
  await expect(page.getByText("Can view · created")).toBeVisible();
  await page.getByRole("link", { name: "Grace's paper" }).click();
  await expect(page.getByRole("heading", { name: "Grace's paper" })).toBeVisible();
  await expect(page.getByText(/Ask its owner for edit access/)).toBeVisible();
  if (isMobile) await page.getByRole("tab", { name: "Source" }).click();
  await page.getByRole("textbox", { name: "LaTeX source" }).click();
  await page.keyboard.type("nope");
  await expect(page.getByText("nope")).toHaveCount(0);
  expect(backend.document(id)?.version).toBe(1);
  expect(profileCalls.length).toBeGreaterThan(0);
});

test("compile errors point at their line", async ({ page, profileCalls, isMobile }) => {
  await stubCompile(page, (call) =>
    call === 1
      ? { status: "compiled", pdf_base64: PDF, log: "" }
      : { status: "error", errors: ["line 2: Undefined control sequence."], log: "./main.tex:2: Undefined control sequence." },
  );
  await signIn(page);
  await page.getByRole("button", { name: "Use the ACS Journal (achemso) template" }).click();
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
    await expect(page.getByRole("button", { name: /^Use the / })).toHaveCount(4);
    await expectAccessible(page);
    await page.getByRole("button", { name: "Use the APS Physical Review (REVTeX 4.2) template" }).click();
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
  await page.getByRole("button", { name: "Use the Nature template" }).click();
  await expect(page.getByRole("img", { name: "Page 1 of 1" })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  expect(profileCalls.length).toBeGreaterThan(0);
});

const MANUSCRIPT = `\\documentclass{article}
\\title{Spin waves in thin films}
\\author{Ada Lovelace}
\\begin{document}
\\maketitle
\\section{Introduction}
Magnons carry spin~\\cite{kittel}.
\\begin{thebibliography}{1}
\\bibitem{kittel} C. Kittel, Introduction to Solid State Physics.
\\end{thebibliography}
\\end{document}
`;

test("the overview shows progress, and a co-author joins through an invite link", async ({ page, backend, browser, isMobile }) => {
  await stubCompile(page);
  const id = backend.seed("google-ada", "Spin waves", { source: MANUSCRIPT, templateId: null });
  backend.setName("google-ada", "Ada Lovelace");
  await signIn(page);
  await page.goto(`/editor/${id}`);

  await page.getByRole("button", { name: /^Progress \d+%/ }).click();
  const overview = page.getByRole("dialog", { name: "Overview" });
  await expect(overview.getByRole("heading", { name: "Progress" })).toBeVisible();
  await expect(overview.getByText("Every citation has a reference")).toBeVisible();
  await expectAccessible(page);
  await page.keyboard.press("Escape");
  await expect(overview).toBeHidden();

  await page.getByRole("button", { name: /Share/ }).click();
  const share = page.getByRole("dialog", { name: "Share" });
  await expect(share.getByText("Ada Lovelace")).toBeVisible();
  await share.getByRole("button", { name: "Create invite link" }).click();
  const link = await share.getByLabel("Send this link to your co-author").inputValue();
  expect(link).toMatch(/\/invite#inv_/);
  await expectAccessible(page);

  // Grace opens the link in her own browser, already signed in.
  const graceContext = await browser.newContext(isMobile ? { viewport: { width: 412, height: 839 }, isMobile: true, hasTouch: true } : {});
  const grace = await graceContext.newPage();
  await serveBackend(grace, backend);
  await grace.route("**/__api/api/v1/users/profile/sync", (route) => route.fulfill({ json: { status: "synced", uid: "fake" } }));
  await grace.route("**/__api/api/v1/colab/compile", (route) => route.fulfill({ json: { status: "compiled", pdf_base64: PDF, log: "" } }));
  await grace.goto("/sign-up");
  await grace.getByLabel("Full name").fill("Grace Hopper");
  await grace.getByLabel("Email").fill("grace@example.com");
  await grace.getByLabel("Password", { exact: true }).fill("correct horse 42");
  await grace.getByRole("button", { name: "Create account" }).click();
  await verifyEmail(grace, "grace@example.com");
  await grace.getByRole("button", { name: "I've verified my email" }).click();
  await expect(grace.getByRole("heading", { name: "Welcome, Grace Hopper" })).toBeVisible();

  await grace.goto(link);
  await expect(grace.getByRole("heading", { name: "Spin waves" })).toBeVisible();
  await expect(grace).toHaveURL("/invite"); // the token left the address bar
  await expectAccessible(grace);
  await grace.getByRole("button", { name: "Open the document" }).click();
  await expect(grace).toHaveURL(`/editor/${id}`);
  // Editors see the title (only owners rename) and can save.
  await expect(grace.getByRole("heading", { name: "Spin waves" })).toBeVisible();
  await expect(grace.getByText("All changes saved")).toBeAttached();
  expect(backend.members(id)["uid-grace@example.com"]).toBe("editor");
  await graceContext.close();
});
