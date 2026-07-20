"use client";

import { useEffect } from "react";
import { TriangleAlert } from "lucide-react";
import { Card } from "@/components/ui/Card";
import { Button } from "@/components/ui/Button";

/**
 * Root-level error boundary — covers the public routes (landing, login,
 * signup, onboarding) that sit outside the (app) route group's own
 * error.tsx. Same reasoning: this app had zero error boundaries anywhere
 * before this pass.
 */
export default function RootError({
  error,
  unstable_retry,
}: {
  error: Error & { digest?: string };
  unstable_retry: () => void;
}) {
  useEffect(() => {
    console.error("[RootError]", error);
  }, [error]);

  return (
    <div className="mx-auto flex min-h-full max-w-2xl flex-col items-center justify-center px-4 py-16 md:px-8">
      <Card className="flex flex-col items-center gap-3 text-center">
        <span className="flex h-10 w-10 items-center justify-center rounded-full bg-notification/10 text-notification">
          <TriangleAlert size={18} />
        </span>
        <h1 className="font-display text-[17px] font-semibold text-text-primary">
          Something went wrong
        </h1>
        <p className="font-body text-[13px] leading-relaxed text-text-secondary">
          {error.message || "This page hit an unexpected error."}
        </p>
        <Button onClick={() => unstable_retry()}>Try again</Button>
      </Card>
    </div>
  );
}
