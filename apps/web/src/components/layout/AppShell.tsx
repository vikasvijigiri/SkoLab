"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { motion } from "framer-motion";
import { Search as SearchIcon } from "lucide-react";
import { cn, focusRing } from "@/lib/utils";
import { useAuth } from "@/lib/hooks/AuthProvider";
import { useCommandPalette } from "@/components/command/CommandPaletteProvider";
import { ThemeToggle } from "@/components/ui/ThemeToggle";
import { NAV_ITEMS } from "@/lib/nav";

export function AppShell({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  const { user, signOut } = useAuth();
  const { toggle: toggleCommandPalette } = useCommandPalette();

  return (
    <div className="flex h-dvh w-full overflow-hidden">
      <aside className="hidden w-60 shrink-0 flex-col border-r border-border bg-surface md:flex">
        <div className="flex h-16 items-center justify-between px-6">
          <span className="font-display text-[20px] font-bold text-text-primary">SkoLab</span>
          <ThemeToggle />
        </div>
        <div className="px-3 pb-2">
          <button
            onClick={toggleCommandPalette}
            className={cn(
              "flex w-full cursor-pointer items-center gap-2 rounded-md border border-border bg-surface-subtle px-2.5 py-2 font-body text-[12.5px] text-text-muted transition-colors duration-[var(--motion-fast)] hover:border-primary/40 hover:text-text-primary",
              focusRing
            )}
            style={{ transitionTimingFunction: "var(--ease-standard)" }}
          >
            <SearchIcon size={14} />
            <span className="flex-1 text-left">Search...</span>
            <kbd className="rounded border border-border px-1 py-0.5 font-mono text-[10px]">⌘K</kbd>
          </button>
        </div>
        <nav aria-label="Primary" className="flex flex-1 flex-col gap-1 px-3 py-2">
          {NAV_ITEMS.filter((item) => item.href !== "/profile").map((item) => {
            const active = pathname?.startsWith(item.href);
            return (
              <Link
                key={item.href}
                href={item.href}
                aria-current={active ? "page" : undefined}
                className={cn(
                  "relative flex items-center gap-3 rounded-md px-3 py-2.5 font-body text-[14px] font-medium transition-colors duration-[var(--motion-fast)]",
                  active ? "text-primary" : "text-text-muted hover:bg-surface-subtle hover:text-text-primary",
                  focusRing
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
          <div
            className={cn(
              "rounded-md px-3 py-2 transition-colors duration-[var(--motion-fast)]",
              pathname?.startsWith("/profile") ? "bg-primary/10" : "hover:bg-surface-subtle"
            )}
            style={{ transitionTimingFunction: "var(--ease-standard)" }}
          >
            {/* Link and the Sign out button are siblings, not nested — an <a> can't contain another interactive element. */}
            <Link
              href="/profile"
              aria-current={pathname?.startsWith("/profile") ? "page" : undefined}
              className={cn("flex items-center gap-3 rounded-md", focusRing)}
            >
              <div
                className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full font-display text-[13px] font-bold text-white shadow-card"
                style={{ background: "var(--gradient-hero)" }}
              >
                {(user?.displayName ?? user?.email ?? "?").slice(0, 1).toUpperCase()}
              </div>
              <div className="min-w-0 flex-1">
                <p className="truncate font-body text-[13px] font-medium text-text-primary">
                  {user?.displayName ?? "Researcher"}
                </p>
              </div>
            </Link>
            <button
              onClick={() => signOut()}
              className={cn(
                "ml-12 cursor-pointer rounded-sm font-body text-[12px] text-text-muted transition-colors duration-[var(--motion-fast)] hover:text-primary",
                focusRing
              )}
              style={{ transitionTimingFunction: "var(--ease-standard)" }}
            >
              Sign out
            </button>
          </div>
        </div>
      </aside>

      <div className="flex min-h-0 min-w-0 flex-1 flex-col">
        <header className="flex h-16 shrink-0 items-center justify-between border-b border-border bg-page-bg px-4 md:hidden">
          <span className="font-display text-[18px] font-bold text-text-primary">SkoLab</span>
          <ThemeToggle />
        </header>
        <main className="min-h-0 flex-1 overflow-y-auto bg-page-bg">{children}</main>
        <nav aria-label="Primary" className="flex h-16 shrink-0 items-center justify-around border-t border-border bg-surface md:hidden">
          {NAV_ITEMS.map((item) => {
            const active = pathname?.startsWith(item.href);
            return (
              <Link
                key={item.href}
                href={item.href}
                aria-current={active ? "page" : undefined}
                className={cn(
                  "flex flex-col items-center gap-0.5 rounded-md px-2 py-1 font-body text-[10px] font-medium transition-[color,transform] duration-[var(--motion-fast)] active:scale-90",
                  active ? "text-primary" : "text-text-muted hover:text-text-primary",
                  focusRing
                )}
                style={{ transitionTimingFunction: "var(--ease-standard)" }}
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
