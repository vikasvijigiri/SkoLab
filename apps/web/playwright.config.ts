import { defineConfig, devices } from "@playwright/test";

/**
 * E2E against a real `next dev` server with no Firebase env — the state the
 * README documents (app builds and runs; auth shows a clear "not configured"
 * message). Only public routes (`/`, `/login`) are exercised; authed routes
 * redirect to `/login`.
 */
export default defineConfig({
  testDir: "./e2e",
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  workers: process.env.CI ? 1 : undefined,
  reporter: process.env.CI ? "github" : "list",
  use: {
    baseURL: "http://localhost:3000",
    trace: "on-first-retry",
    // The landing page's HeroPreview card fades in via framer-motion
    // (MotionProvider already wires MotionConfig reducedMotion="user" for
    // exactly this). Without this, axe.spec.ts's color-contrast check can
    // capture a still-animating opacity blend mid-fade and fail on a
    // transient, in-between color that was never the real, settled one --
    // confirmed live: two different CI runs failed on two different
    // HeroPreview text nodes, at two different non-token color values
    // neither matching the actual --text-muted CSS variable, both inside
    // the same fading card. Forcing reduced motion makes every animation
    // complete instantly, so every scan sees the final DOM state -- the
    // same state a real user with "reduce motion" enabled always sees.
    reducedMotion: "reduce",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
  webServer: {
    command: "npm run dev",
    url: "http://localhost:3000",
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
  },
});
