import Link from "next/link";
import { Compass } from "lucide-react";
import { Card } from "@/components/ui/Card";

/**
 * Whole-app 404: renders for any URL that matches no route (Next.js has
 * handled unmatched URLs via the root `not-found` since v13.3.0 — no
 * `notFound()` call site needed for that case). Before this, an unmatched
 * URL fell through to Next's generic unbranded 404 — a jarring exit for a
 * mistyped link or a stale bookmark on an otherwise on-brand app.
 *
 * Rendered inside the root layout, so it inherits the real theme/fonts —
 * unlike `global-error.tsx`, which bypasses the layout entirely.
 */
export default function NotFound() {
  return (
    <div className="mx-auto flex min-h-full max-w-2xl flex-col items-center justify-center px-4 py-16 md:px-8">
      <Card className="flex flex-col items-center gap-3 text-center">
        <span className="flex h-10 w-10 items-center justify-center rounded-full bg-primary/10 text-primary">
          <Compass size={18} />
        </span>
        <h1 className="font-display text-[17px] font-semibold text-text-primary">
          Page not found
        </h1>
        <p className="font-body text-[13px] leading-relaxed text-text-secondary">
          Nothing lives at this address — the link may be out of date, or the
          page may have moved.
        </p>
        <Link
          href="/"
          className="inline-flex h-11 w-full items-center justify-center rounded-md bg-primary px-6 text-[13px] font-semibold text-text-on-primary transition-opacity hover:opacity-90 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary"
        >
          Back to home
        </Link>
      </Card>
    </div>
  );
}
