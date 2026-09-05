/**
 * The app's own canonical production URL — used by app/layout.tsx
 * (metadataBase), app/robots.ts, and app/sitemap.ts. No hardcoded domain:
 * NEXT_PUBLIC_SITE_URL wins if set (this is what render.yaml sets, to this
 * service's own predictable Render URL); otherwise Vercel's own
 * auto-injected VERCEL_PROJECT_PRODUCTION_URL (available at build time on
 * any Vercel deploy, preview or production) is used, so this still
 * self-configures correctly if the app ever moves host again; falls back to
 * localhost for `next dev`.
 */
export const siteUrl =
  process.env.NEXT_PUBLIC_SITE_URL ??
  (process.env.VERCEL_PROJECT_PRODUCTION_URL
    ? `https://${process.env.VERCEL_PROJECT_PRODUCTION_URL}`
    : "http://localhost:3000");
