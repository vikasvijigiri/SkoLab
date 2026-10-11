import { readFileSync } from "node:fs";
import type { Page } from "@playwright/test";
import type { FakeBackend } from "../src/test/fakeBackend";
import { expect, expectAccessible, test } from "./fixtures";

// The editor's keyboard tools, math preview, checks and layouts in a real browser, under the site's CSP.
const PDF = readFileSync(new URL("./assets/ams-article.pdf", import.meta.url)).toString("base64");
const PNG = Buffer.from("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==", "base64");

const PAPER = `\\documentclass{article}
\\newcommand{\\R}{\\mathbb{R}}
\\begin{document}
\\section{Introduction}
\\label{sec:intro}
Fields in $\\R^3$ obey a law, see Section~\\ref{sec:intro} and Figure~\\ref{fig:missing}.
\\section{Methods}
We cite \\cite{knuth84}.
\\end{document}
`;

async function openPaper(page: Page, backend: FakeBackend, files: Record<string, string | Uint8Array> = {}) {
  backend.setCompiler(() => ({ status: 200, body: { status: "compiled", pdf_base64: PDF, log: "" } }));
  const id = backend.seed("google-ada", "Fields", { source: PAPER, templateId: null });
  for (const [path, content] of Object.entries(files)) backend.addFile(id, path, content);
  await page.goto("/sign-in");
  await page.getByRole("button", { name: "Continue with Google" }).click();
  await expect(page.getByRole("heading", { name: /^Welcome/ })).toBeVisible();
  await page.goto(`/editor/${id}`);
  await expect(page.getByRole("img", { name: "Page 1 of 1" })).toBeVisible();
  return { id, source: page.getByRole("textbox", { name: "LaTeX source" }) };
}

/** Puts the cursor at the start of the \end{document} line (the source ends with a newline). */
async function beforeEnd(page: Page) {
  await page.getByRole("textbox", { name: "LaTeX source" }).click();
  await page.keyboard.press("ControlOrMeta+End");
  await page.keyboard.press("ArrowUp");
  await page.keyboard.press("Home");
}

/** Puts the cursor on a 1-based line of the source, after some characters (lines above must not wrap). */
async function cursorAt(page: Page, line: number, column = 0) {
  await page.getByRole("textbox", { name: "LaTeX source" }).click();
  await page.keyboard.press("ControlOrMeta+Home");
  for (let i = 1; i < line; i += 1) await page.keyboard.press("ArrowDown");
  await page.keyboard.press("Home");
  for (let i = 0; i < column; i += 1) await page.keyboard.press("ArrowRight");
}

test.describe("editor tools", () => {
  test.skip(({ isMobile }) => isMobile, "keyboard tools are for desktop; phones use the command bar button");

  test("the command bar runs actions and inserts blocks with fields to Tab through", async ({ page, backend, profileCalls }) => {
    const { source } = await openPaper(page, backend);
    await source.click();
    await page.keyboard.press("ControlOrMeta+End");
    await page.keyboard.press("ControlOrMeta+k");
    const bar = page.getByRole("dialog", { name: "Command bar" });
    await expect(bar).toBeVisible();
    await expectAccessible(page);
    await page.keyboard.type("insert figure");
    await page.keyboard.press("Enter");
    await expect(bar).toBeHidden();
    await expect(source).toContainText("\\includegraphics[width=0.8\\linewidth]{figures/file}");
    // The first field is selected: typing replaces it, Tab moves to the caption.
    await page.keyboard.type("figures/field");
    await page.keyboard.press("Tab");
    await page.keyboard.type("Field lines");
    await expect(source).toContainText("{figures/field}");
    await expect(source).toContainText("\\caption{Field lines}");
    expect(profileCalls.length).toBeGreaterThan(0);
  });

  test("slash commands, formatting keys and the citation picker", async ({ page, backend, profileCalls }) => {
    const { source } = await openPaper(page, backend, { "refs.bib": "@book{knuth84, title={The TeXbook}, author={Knuth, Donald}, year=1984}\n@article{curie1898, title={Radium}, author={Curie, Marie}, year=1898}" });
    await beforeEnd(page);
    await page.keyboard.type("/tab");
    await expect(page.getByRole("option", { name: /^Table/ })).toBeVisible();
    // CodeMirror ignores Enter for a moment after the list changes, so a fast typist doesn't pick by accident.
    await page.waitForTimeout(150);
    await page.keyboard.press("Enter");
    await expect(source).toContainText("\\begin{tabular}{lcc}");
    await expect(source).not.toContainText("/tab");

    await page.keyboard.press("ControlOrMeta+End");
    await page.keyboard.type("bold words");
    await page.keyboard.press("Shift+Home");
    await page.keyboard.press("ControlOrMeta+b");
    await expect(source).toContainText("\\textbf{bold words}");

    await page.keyboard.press("End");
    await page.keyboard.press("Enter");
    await page.keyboard.press("ControlOrMeta+Shift+L");
    await page.keyboard.type("radium");
    await expect(page.getByRole("option", { name: /curie1898/ })).toBeVisible();
    await page.waitForTimeout(150);
    await page.keyboard.press("Enter");
    await expect(source).toContainText("\\cite{curie1898}");
    expect(profileCalls.length).toBeGreaterThan(0);
  });

  test("math previews as you write it, with the document's macros", async ({ page, backend, profileCalls }) => {
    await openPaper(page, backend);
    // Inside $\R^3$ on line 6.
    await cursorAt(page, 6, 14);
    const preview = page.getByRole("note", { name: "Math preview" });
    await expect(preview).toBeVisible();
    await expect(preview.locator(".katex")).toBeVisible();
    await expect(preview.locator("annotation")).toHaveText("\\R^3");
    await page.keyboard.press("ControlOrMeta+End");
    await expect(preview).toBeHidden();
    expect(profileCalls.length).toBeGreaterThan(0);
  });

  test("problems are underlined, counted and fixed in one click", async ({ page, backend, profileCalls }) => {
    const { source } = await openPaper(page, backend, { "refs.bib": "@book{knuth84, title={The TeXbook}}" });
    const status = page.getByRole("contentinfo", { name: "Status" });
    await expect(status.getByRole("button", { name: "1 warning" })).toBeVisible();
    await source.click();
    await page.keyboard.press("ControlOrMeta+Home");
    await page.keyboard.press("F8");
    await expect(page.getByText("Nothing in the project has \\label{fig:missing}.")).toBeVisible();

    // A typo'd citation gets a one-click fix.
    await page.keyboard.press("ControlOrMeta+End");
    await page.keyboard.type("\\cite{knuht84}");
    await expect(status.getByRole("button", { name: "2 warnings" })).toBeVisible();
    await page.keyboard.press("ControlOrMeta+Home");
    await page.keyboard.press("F8");
    await page.keyboard.press("F8");
    await page.getByRole("button", { name: "Change to knuth84" }).click();
    await expect(source).toContainText("\\end{document}\\cite{knuth84}");
    await expect(status.getByRole("button", { name: "1 warning" })).toBeVisible();
    expect(profileCalls.length).toBeGreaterThan(0);
  });

  test("the outline follows the text, and Ctrl-click opens a citation's .bib", async ({ page, backend, profileCalls }) => {
    await openPaper(page, backend, { "refs.bib": "@book{knuth84, title={The TeXbook}}" });
    await page.keyboard.press("ControlOrMeta+Shift+O");
    const outline = page.getByRole("navigation", { name: "Outline" });
    await expect(outline.getByRole("button")).toHaveText([/^Introduction/, /^Methods/]);
    await outline.getByRole("button", { name: /^Methods/ }).click();
    await expect(page.getByRole("contentinfo", { name: "Status" }).getByTitle("Current section")).toHaveText("Methods");

    await page.getByText("knuth84").click({ modifiers: ["ControlOrMeta"] });
    await expect(page.getByText("refs.bib", { exact: true }).first()).toBeVisible();
    await expect(page.getByRole("textbox", { name: "Source of refs.bib" })).toBeVisible();
    expect(profileCalls.length).toBeGreaterThan(0);
  });

  test("layouts, the divider and focus mode", async ({ page, backend, profileCalls }) => {
    const { source, id } = await openPaper(page, backend);
    const pdf = page.getByRole("region", { name: "PDF preview" });
    await source.click();
    await page.keyboard.press("Alt+2");
    await expect(pdf).toBeHidden();
    await page.keyboard.press("Alt+3");
    await expect(source).toBeHidden();
    await page.keyboard.press("Alt+1");
    await expect(pdf).toBeVisible();

    const pane = page.getByRole("region", { name: "LaTeX source" });
    const before = (await pane.boundingBox())?.width ?? 0;
    const divider = page.getByRole("separator", { name: "Resize the source and PDF panes" });
    const box = await divider.boundingBox();
    if (!box) throw new Error("no divider");
    await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
    await page.mouse.down();
    await page.mouse.move(box.x - 150, box.y + box.height / 2, { steps: 5 });
    await page.mouse.up();
    expect((await pane.boundingBox())?.width ?? 0).toBeLessThan(before - 100);
    expect(await page.evaluate((key) => localStorage.getItem(key), `skolab.split.${id}`)).not.toBeNull();

    await source.click();
    await page.keyboard.press("ControlOrMeta+.");
    await expect(page.getByRole("button", { name: /Leave focus/ })).toBeVisible();
    await expect(pdf).toBeHidden();
    await expect(page.getByRole("button", { name: "Search or run a command" })).toBeHidden();
    await expectAccessible(page);
    await page.keyboard.press("Escape");
    await expect(pdf).toBeVisible();
    expect(profileCalls.length).toBeGreaterThan(0);
  });

  test("a dropped image becomes a figure", async ({ page, backend, profileCalls }) => {
    const { source, id } = await openPaper(page, backend);
    await beforeEnd(page);
    const at = await source.boundingBox();
    if (!at) throw new Error("no editor");
    const transfer = await page.evaluateHandle((bytes) => {
      const data = new DataTransfer();
      data.items.add(new File([new Uint8Array(bytes)], "field map.png", { type: "image/png" }));
      return data;
    }, [...PNG]);
    await source.dispatchEvent("dragover", { dataTransfer: transfer });
    await source.dispatchEvent("drop", { dataTransfer: transfer, clientX: at.x + 40, clientY: at.y + 40 });
    await expect(page.getByText("Added figures/field-map.png as a figure")).toBeVisible();
    await expect(source).toContainText("\\includegraphics[width=0.8\\linewidth]{figures/field-map}");
    await expect(source).toContainText("\\usepackage{graphicx}");
    expect(Object.keys(backend.files(id))).toContain("figures/field-map.png");
    expect(profileCalls.length).toBeGreaterThan(0);
  });

  for (const scheme of ["light", "dark"] as const) {
    test(`the shortcut list and status bar meet WCAG 2.2 AA in ${scheme} mode`, async ({ page, backend, profileCalls }) => {
      await page.emulateMedia({ colorScheme: scheme, reducedMotion: "reduce" });
      await openPaper(page, backend);
      await expectAccessible(page);
      await page.getByRole("button", { name: /^Shortcuts/ }).click();
      await expect(page.getByRole("dialog", { name: "Keyboard shortcuts" })).toBeVisible();
      await expectAccessible(page);
      expect(profileCalls.length).toBeGreaterThan(0);
    });
  }

  test("the theme can be set from the command bar, and is remembered", async ({ page, backend, profileCalls }) => {
    await page.emulateMedia({ colorScheme: "light", reducedMotion: "reduce" });
    await openPaper(page, backend);
    await page.getByRole("button", { name: "Search or run a command" }).click();
    await page.keyboard.type("theme dark");
    await page.keyboard.press("Enter");
    await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
    await page.reload();
    await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
    await expect(page.getByRole("img", { name: "Page 1 of 1" })).toBeVisible();
    await expectAccessible(page);
    expect(profileCalls.length).toBeGreaterThan(0);
  });
});

test("phones reach every tool through the command bar button", async ({ page, backend, isMobile, profileCalls }) => {
  test.skip(!isMobile, "phone layout only");
  await openPaper(page, backend);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await page.getByRole("button", { name: "Search or run a command" }).click();
  await page.keyboard.type("outline");
  await page.keyboard.press("Enter");
  const sheet = page.getByRole("dialog", { name: "Outline" });
  await expect(sheet.getByRole("button", { name: /^Methods/ })).toBeVisible();
  await sheet.getByRole("button", { name: /^Methods/ }).click();
  await expect(sheet).toBeHidden();
  await expect(page.getByRole("tab", { name: "Source" })).toHaveAttribute("aria-selected", "true");
  expect(profileCalls.length).toBeGreaterThan(0);
});
