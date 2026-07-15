"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { motion } from "framer-motion";
import { Home, Search, FolderKanban, CircleUserRound } from "lucide-react";
import { cn } from "@/lib/utils";
import { useAuth } from "@/lib/hooks/AuthProvider";
import { ThemeToggle } from "@/components/ui/ThemeToggle";

const NAV_ITEMS = [
  { href: "/home", label: "Home", Icon: Home },
  { href: "/discovery", label: "Discovery", Icon: Search },
  { href: "/workspace", label: "CoLab", Icon: FolderKanban },
  { href: "/profile", label: "Profile", Icon: CircleUserRound },
] as const;

export function AppShell({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  const { user, signOut } = useAuth();

  return (
    <div className="flex h-dvh w-full overflow-hidden">
      <aside className="hidden w-60 shrink-0 flex-col border-r border-border bg-surface md:flex">
        <div className="flex h-16 items-center justify-between px-6">
          <span className="font-display text-[20px] font-bold text-text-primary">SkoLab</span>
          <ThemeToggle />
        </div>
        <nav className="flex flex-1 flex-col gap-1 px-3 py-2">
          {NAV_ITEMS.map((item) => {
            const active = pathname?.startsWith(item.href);
            return (
              <Link
                key={item.href}
                href={item.href}
                className={cn(
                  "relative flex items-center gap-3 rounded-md px-3 py-2.5 font-body text-[14px] font-medium transition-colors duration-[var(--motion-fast)]",
                  active ? "text-primary" : "text-text-muted hover:text-text-primary"
                )}
                style={{ transitionTimingFunction: "var(--ease-standard)" }}
              >
                {active && (
                  <motion.span
                    layoutId="nav-active-pill"
                    className="absolute inset-0 rounded-md bg-primary/10"
                    transition={{ type: "spring", stiffness: 400, damping: 32 }}
                  />
                )}
                <item.Icon size={19} strokeWidth={1.8} className="relative z-10" />
                <span className="relative z-10">{item.label}</span>
              </Link>
            );
          })}
        </nav>
        <div className="border-t border-border px-3 py-4">
          <div className="flex items-center gap-3 px-3">
            <div
              className="flex h-9 w-9 items-center justify-center rounded-full font-display text-[13px] font-bold text-white shadow-card"
              style={{ background: "var(--gradient-hero)" }}
            >
              {(user?.displayName ?? user?.email ?? "?").slice(0, 1).toUpperCase()}
            </div>
            <div className="min-w-0 flex-1">
              <p className="truncate font-body text-[13px] font-medium text-text-primary">
                {user?.displayName ?? "Researcher"}
              </p>
              <button
                onClick={() => signOut()}
                className="font-body text-[12px] text-text-muted hover:text-primary"
              >
                Sign out
              </button>
            </div>
          </div>
        </div>
      </aside>

      <div className="flex min-h-0 min-w-0 flex-1 flex-col">
        <header className="flex h-16 shrink-0 items-center justify-between border-b border-border bg-page-bg px-4 md:hidden">
          <span className="font-display text-[18px] font-bold text-text-primary">SkoLab</span>
          <ThemeToggle />
        </header>
        <main className="min-h-0 flex-1 overflow-y-auto bg-page-bg">{children}</main>
        <nav className="flex h-16 shrink-0 items-center justify-around border-t border-border bg-surface md:hidden">
          {NAV_ITEMS.map((item) => {
            const active = pathname?.startsWith(item.href);
            return (
              <Link
                key={item.href}
                href={item.href}
                className={cn(
                  "flex flex-col items-center gap-0.5 font-body text-[10px] font-medium",
                  active ? "text-primary" : "text-text-muted"
                )}
              >
                <item.Icon size={20} strokeWidth={1.8} />
                {item.label}
              </Link>
            );
          })}
        </nav>
      </div>
    </div>
  );
}
