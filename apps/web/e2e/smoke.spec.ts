import { expect, test } from "@playwright/test";

test("landing page renders with the primary calls to action", async ({ page }) => {
  await page.goto("/");
  await expect(page).toHaveTitle(/SkoLab/i);
  await expect(page.getByRole("link", { name: /sign in/i }).first()).toBeVisible();
  await expect(page.getByText(/get started free/i)).toBeVisible();
  await expect(page.getByRole("link", { name: /i already have an account/i })).toBeVisible();
});

test("login page surfaces the Firebase-not-configured notice", async ({ page }) => {
  await page.goto("/login");
  await expect(page.getByText(/Firebase isn.?t configured yet/i)).toBeVisible();
});

test("an authed route redirects an unauthenticated visitor to /login", async ({ page }) => {
  await page.goto("/home");
  await expect(page).toHaveURL(/\/login$/);
});
