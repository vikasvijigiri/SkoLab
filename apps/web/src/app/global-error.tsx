"use client";

import { useEffect } from "react";
import * as Sentry from "@sentry/nextjs";

/**
 * Catches a crash in the root layout itself (a font loader, AuthProvider, or
 * MotionProvider throwing) — the one failure `app/error.tsx` cannot catch,
 * since error.tsx wraps everything *below* the root layout, not the layout
 * itself. Before this file existed, that class of failure fell through to
 * Next's built-in unstyled fallback.
 *
 * `global-error` replaces the root layout when active, so it must define its
 * own <html>/<body> and cannot rely on a `metadata` export (error boundaries
 * are Client Components) or the theme/fonts set up in layout.tsx. Kept
 * deliberately dependency-light — no Framer Motion, no icon package, inline
 * styles instead of Tailwind utilities — because this is the one screen that
 * must still render if something else in the bundle is already broken.
 */
export default function GlobalError({
  error,
  retry,
}: {
  error: Error & { digest?: string };
  retry: () => void;
}) {
  useEffect(() => {
    console.error("[GlobalError]", error);
    Sentry.captureException(error);
  }, [error]);

  return (
    <html lang="en">
      <body
        style={{
          margin: 0,
          minHeight: "100vh",
          display: "flex",
          flexDirection: "column",
          alignItems: "center",
          justifyContent: "center",
          gap: 12,
          padding: 24,
          textAlign: "center",
          fontFamily:
            "-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif",
          background: "#ffffff",
          color: "#171717",
        }}
      >
        <title>SkoLab — Something went wrong</title>
        <h1 style={{ fontSize: 17, fontWeight: 600, margin: 0 }}>
          Something went wrong
        </h1>
        <p style={{ fontSize: 13, lineHeight: 1.6, color: "#5b5b5b", maxWidth: "40ch", margin: 0 }}>
          {error.message || "The app hit an unexpected error loading this page."}
        </p>
        <button
          onClick={() => retry()}
          style={{
            height: 44,
            padding: "0 24px",
            borderRadius: 8,
            border: "none",
            background: "#0d9488",
            color: "#ffffff",
            fontSize: 13,
            fontWeight: 600,
            cursor: "pointer",
          }}
        >
          Try again
        </button>
      </body>
    </html>
  );
}
