import * as Sentry from "@sentry/nextjs";

const dsn = process.env.NEXT_PUBLIC_SENTRY_DSN;

// Client-side Sentry. No-op until NEXT_PUBLIC_SENTRY_DSN is set.
Sentry.init({
  dsn,
  enabled: Boolean(dsn),
  tracesSampleRate: 0.1,
  environment: process.env.NODE_ENV,
  // Session Replay is deliberately off — it burns the free-tier quota fast.
});
