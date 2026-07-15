"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/lib/hooks/AuthProvider";
import { AppShell } from "@/components/layout/AppShell";
import { CommandPaletteProvider } from "@/components/command/CommandPaletteProvider";

export default function AppGroupLayout({ children }: { children: React.ReactNode }) {
  const { user, loading } = useAuth();
  const router = useRouter();

  useEffect(() => {
    if (!loading && !user) router.replace("/login");
  }, [loading, user, router]);

  if (loading || !user) {
    return (
      <div className="flex min-h-full flex-1 items-center justify-center bg-page-bg">
        <span
          className="inline-block h-8 w-8 animate-spin rounded-full border-2 border-primary border-t-transparent"
          aria-label="Loading"
        />
      </div>
    );
  }

  return (
    <CommandPaletteProvider>
      <AppShell>{children}</AppShell>
    </CommandPaletteProvider>
  );
}
