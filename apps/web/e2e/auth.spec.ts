import { expect, expectAccessible, test, verifyEmail } from "./fixtures";

const PASSWORD = "correct horse 42";

test("a new user signs up, verifies, signs out and signs back in", async ({ page, profileCalls }) => {
  await page.goto("/sign-up");
  await page.getByLabel("Full name").fill("Grace Hopper");
  await page.getByLabel("Email").fill("grace@example.com");
  await page.getByLabel("Password", { exact: true }).fill(PASSWORD);
  await page.getByRole("button", { name: "Create account" }).click();

  await expect(page).toHaveURL("/verify-email");
  await expect(page.getByRole("heading", { name: "Verify your email" })).toBeVisible();
  await page.getByRole("button", { name: "I've verified my email" }).click();
  await expect(page.getByText("Not verified yet")).toBeVisible();

  await verifyEmail(page, "grace@example.com");
  await page.getByRole("button", { name: "I've verified my email" }).click();
  await expect(page.getByRole("heading", { name: "Welcome, Grace Hopper" })).toBeVisible();
  await expect(page.getByText("You're all set")).toBeVisible();
  expect(profileCalls).toEqual([
    { authorization: "Bearer fake-token:uid-grace@example.com", body: { uid: "uid-grace@example.com", name: "Grace Hopper" } },
  ]);

  await page.reload();
  await expect(page.getByRole("heading", { name: "Welcome, Grace Hopper" })).toBeVisible();

  await page.getByRole("button", { name: "Sign out" }).click();
  await expect(page).toHaveURL("/sign-in");
  await page.getByLabel("Email").fill("grace@example.com");
  await page.getByLabel("Password", { exact: true }).fill(PASSWORD);
  await page.getByLabel("Password", { exact: true }).press("Enter");
  await expect(page.getByRole("heading", { name: "Welcome, Grace Hopper" })).toBeVisible();
});

test("a deep link survives signing in", async ({ page, profileCalls }) => {
  await page.goto("/?tab=recent");
  await expect(page).toHaveURL("/sign-in?next=%2F%3Ftab%3Drecent");
  await page.getByRole("button", { name: "Continue with Google" }).click();
  await expect(page).toHaveURL("/?tab=recent");
  await expect(page.getByRole("heading", { name: "Welcome, Ada Lovelace" })).toBeVisible();
  await expect.poll(() => profileCalls.length).toBe(1);
});

test("wrong credentials get one clear message and an empty password", async ({ page, profileCalls }) => {
  await page.goto("/sign-in");
  await page.getByLabel("Email").fill("nobody@example.com");
  await page.getByLabel("Password", { exact: true }).fill("not the password");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.getByRole("alert")).toHaveText("That email and password don't match. Check them and try again.");
  await expect(page.getByLabel("Password", { exact: true })).toHaveValue("");
  expect(profileCalls).toEqual([]);
});

test("password reset never reveals whether an account exists", async ({ page, profileCalls }) => {
  await page.goto("/sign-in");
  await page.getByLabel("Email").fill("nobody@example.com");
  await page.getByRole("link", { name: "Forgot password?" }).click();
  await expect(page.getByLabel("Email")).toHaveValue("nobody@example.com");
  await page.getByRole("button", { name: "Send reset link" }).click();
  await expect(page.getByRole("status")).toContainText("If nobody@example.com has a SkoLab account");
  expect(profileCalls).toEqual([]);
});

test("the sign-in form works from the keyboard alone", async ({ page, profileCalls, isMobile }) => {
  test.skip(isMobile, "keyboard navigation is a desktop concern");
  await page.goto("/sign-in");
  await page.getByRole("button", { name: "Continue with Google" }).focus();
  await page.keyboard.press("Tab");
  await expect(page.getByLabel("Email")).toBeFocused();
  await page.keyboard.press("Enter");
  await expect(page.getByLabel("Email")).toBeFocused();
  await expect(page.getByLabel("Email")).toHaveAccessibleDescription("Enter your email address.");
  expect(profileCalls).toEqual([]);
});

test("pages fit a phone screen without sideways scrolling", async ({ page, isMobile, profileCalls }) => {
  test.skip(!isMobile, "phone layout only");
  for (const path of ["/sign-in", "/sign-up", "/forgot-password"]) {
    await page.goto(path);
    await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
    expect(overflow, path).toBeLessThanOrEqual(0);
  }
  expect(profileCalls).toEqual([]);
});

for (const scheme of ["light", "dark"] as const) {
  test(`every screen meets WCAG 2.2 AA in ${scheme} mode`, async ({ page, profileCalls }) => {
    // Fade-ins would be measured mid-animation; contrast is judged at rest.
    await page.emulateMedia({ colorScheme: scheme, reducedMotion: "reduce" });
    for (const path of ["/sign-in", "/sign-up", "/forgot-password", "/nope"]) {
      await page.goto(path);
      await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
      await expectAccessible(page);
    }
    // Error states too.
    await page.goto("/sign-in");
    await page.getByRole("button", { name: "Sign in" }).click();
    await expect(page.getByLabel("Email")).toHaveAttribute("aria-invalid", "true");
    await expectAccessible(page);

    await page.goto("/sign-up");
    await page.getByLabel("Full name").fill("Grace Hopper");
    await page.getByLabel("Email").fill(`grace-${scheme}@example.com`);
    await page.getByLabel("Password", { exact: true }).fill(PASSWORD);
    await page.getByRole("button", { name: "Create account" }).click();
    await expect(page.getByRole("heading", { name: "Verify your email" })).toBeVisible();
    await expectAccessible(page);

    await verifyEmail(page, `grace-${scheme}@example.com`);
    await page.getByRole("button", { name: "I've verified my email" }).click();
    await expect(page.getByText("You're all set")).toBeVisible();
    await expectAccessible(page);
    expect(profileCalls).toHaveLength(1);
  });
}
