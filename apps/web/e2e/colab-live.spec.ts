import { expect, test } from "@playwright/test";

/**
 * Authenticated visual proof for the deployed CoLab screen.
 *
 * Create the ignored state once with:
 *   npx playwright codegen --save-storage=playwright/.auth/user.json https://skolab-web.onrender.com/login
 * Then run with:
 *   PLAYWRIGHT_BASE_URL=https://skolab-web.onrender.com PLAYWRIGHT_STORAGE_STATE=playwright/.auth/user.json npm run test:e2e:colab
 */
const state = process.env.PLAYWRIGHT_STORAGE_STATE ?? "playwright/.auth/user.json";
const colabUrl = process.env.PLAYWRIGHT_COLAB_URL ?? "/workspace/Rv7KD3fpdFRS4IJShFn";

test.use({ storageState: state });

test("deployed CoLab matches the Penpot manuscript landmarks", async ({ page }) => {
  await page.goto(colabUrl);
  await page.waitForLoadState("domcontentloaded");
  await expect(page.getByRole("banner", { name: "SkoLab navigation" })).toBeVisible();
  await expect(page.getByText("Manuscript", { exact: true }).first()).toBeVisible();
  await expect(page.getByRole("button", { name: "Compile PDF" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Evidence & decisions" })).toBeVisible();
  await expect(page.getByText("Local constraints produce long-range signatures")).toBeVisible();
  await page.screenshot({ path: "e2e/__screens__/colab-penpot-live.png", fullPage: true });
});
