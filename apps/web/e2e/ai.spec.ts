import { expect } from "@playwright/test";
import { test } from "./fixtures";

const B = String.fromCharCode(92);
const SOURCE = [`${B}documentclass{article}`, `${B}begin{document}`, `${B}section{Introduction}`, `We measured teh field at $T<T_c$ and the results ${B}cite{k} was clear and very much obvious to everyone.`, "", "Second paragraph stays.", `${B}end{document}`].join("\n");

// Selecting with the mouse shows the AI toolbar; one click improves the
// selection and the suggestion applies in the source. (Phones select by touch.)
test("selecting text offers AI improvements that apply in place", async ({ page, backend, isMobile }) => {
  test.skip(isMobile, "mouse selection");
  backend.setCompiler(() => ({ status: 200, body: { status: "failed", errors: ["preview"], log: "" } }));
  backend.setImprover((text) => {
    const at = text.indexOf("teh");
    return at >= 0 ? [{ id: "s1", original: "teh", replacement: "the", reason: "Spelling", category: "grammar", start: at, end: at + 3 }] : [];
  });
  const id = backend.seed("google-ada", "Spin waves", { source: SOURCE, templateId: null });
  await page.goto("/sign-in");
  await page.getByRole("button", { name: "Continue with Google" }).click();
  await page.getByRole("heading", { name: "Your documents" }).waitFor();
  await page.goto(`/editor/${id}`);
  const line = page.locator(".cm-line", { hasText: "We measured" });
  await line.waitFor();
  const box = await line.boundingBox();
  if (!box) throw new Error("no line");
  await page.mouse.move(box.x + 4, box.y + 10);
  await page.mouse.down();
  await page.mouse.move(box.x + box.width * 0.6, box.y + 10, { steps: 8 });
  await page.mouse.up();
  const bar = page.getByRole("toolbar", { name: "Improve the selection with AI" });
  await expect(bar).toBeVisible();
  await bar.getByRole("button", { name: "Grammar" }).click();
  await expect(page.getByText("1 suggestion")).toBeVisible();
  await page.getByRole("button", { name: "Accept" }).first().click();
  await expect(page.locator(".cm-content")).toContainText("We measured the field");
});
