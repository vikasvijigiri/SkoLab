import type { NextConfig } from "next";
import path from "path";
import { withSentryConfig } from "@sentry/nextjs";

const nextConfig: NextConfig = {
  // apps/web is an npm workspace member of the SkoLab monorepo root — pin the
  // Turbopack root explicitly so it doesn't have to guess from lockfile scans.
  turbopack: {
    root: path.join(__dirname, "../.."),
  },
  // `experimental.viewTransition` was removed here when next went 16.2.10 ->
  // 16.3.0 (the security bump for CVE-affected next/postcss/sharp). The key no
  // longer exists in 16.3's config schema, so it fails the typecheck, and
  // nothing depended on it: no `ViewTransition` usage anywhere in src/, and no
  // `view-transition-name` in the CSS. Cross-route card->hero morphs are still
  // unbuilt -- framer-motion `layoutId` only bridges simultaneously-mounted
  // elements. If they are built later, re-enable whatever 16.3 calls this.
};

export default withSentryConfig(nextConfig, {
  // No auth token in CI/local -> source-map upload is skipped silently; the
  // runtime SDK still works once NEXT_PUBLIC_SENTRY_DSN is set.
  silent: true,
  // Route Sentry traffic through the app's own origin so ad-blockers don't
  // drop events.
  tunnelRoute: "/monitoring",
  disableLogger: true,
});
