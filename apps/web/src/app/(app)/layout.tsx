"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/lib/hooks/AuthProvider";
import { AppShell } from "@/components/layout/AppShell";
import { CommandPaletteProvider } from "@/components/command/CommandPaletteProvider";

/** Neutral body skeleton shown while auth resolves — rendered *inside* AppShell
 *  so the top bar stays put and only the content area swaps (no full-page CLS). */
function AppBodySkeleton() {
  return (
    <div
      role="status"
      aria-label="Loading"
      className="mx-auto w-full max-w-[1400px] px-4 py-6 md:px-6"
    >
      <div className="h-7 w-56 animate-pulse rounded-xs bg-surface-subtle" />
      <div className="mt-4 flex flex-col gap-4">
        {[0, 1, 2, 3].map((i) => (
          <div key={i} className="h-32 animate-pulse rounded-md bg-surface-subtle" />
        ))}
      </div>
    </div>
  );
}

export default function AppGroupLayout({ children }: { children: React.ReactNode }) {
  const { user, loading } = useAuth();
  const router = useRouter();

  useEffect(() => {
    if (!loading && !user) router.replace("/login");
  }, [loading, user, router]);

  // Loaded but unauthenticated → the effect above redirects; render nothing
  // rather than flashing the app chrome.
  if (!loading && !user) return null;

  return (
    <CommandPaletteProvider>
      <AppShell>{loading ? <AppBodySkeleton /> : children}</AppShell>
    </CommandPaletteProvider>
  );
}
