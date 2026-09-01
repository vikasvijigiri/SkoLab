import * as Sentry from "@sentry/nextjs";

const dsn = process.env.NEXT_PUBLIC_SENTRY_DSN;

// No DSN -> `enabled: false` -> the SDK is a no-op. Set NEXT_PUBLIC_SENTRY_DSN
// (see .env.local.example) to turn it on.
Sentry.init({
  dsn,
  enabled: Boolean(dsn),
  tracesSampleRate: 0.1,
  environment: process.env.NODE_ENV,
});
