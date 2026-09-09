import { expect, test } from "@playwright/test";

test("landing page renders with the primary calls to action", async ({ page }) => {
  await page.goto("/");
  await expect(page).toHaveTitle(/SkoLab/i);
  await expect(page.getByRole("link", { name: /sign in/i }).first()).toBeVisible();
  // Two "Get started free" CTAs now (hero + closing band) — assert the hero one.
  await expect(page.getByRole("button", { name: /get started free/i }).first()).toBeVisible();
  await expect(page.getByRole("link", { name: /i already have an account/i })).toBeVisible();
  await expect(page.getByText(/try it .*no sign-up/i)).toBeVisible();
});

test("login page surfaces the Firebase-not-configured notice", async ({ page }) => {
  await page.goto("/login");
  const configNotice = page.getByText(/Firebase isn.?t configured yet/i);
  const signIn = page.getByRole("button", { name: /continue with google|sign in with google/i });
  // The app intentionally supports both states: a clean setup has no web
  // Firebase credentials, while deployed/test environments may have them.
  if (await configNotice.count()) {
    await expect(configNotice).toBeVisible();
  } else {
    await expect(signIn).toBeVisible();
  }
});

test("an authed route redirects an unauthenticated visitor to /login", async ({ page }) => {
  await page.goto("/home");
  await expect(page).toHaveURL(/\/login$/);
});
