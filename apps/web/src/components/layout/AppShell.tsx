"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { motion } from "framer-motion";
import { LogOut, Search as SearchIcon, PanelLeftClose, PanelLeft } from "lucide-react";
import { cn, focusRing } from "@/lib/utils";
import { useAuth } from "@/lib/hooks/AuthProvider";
import { useCommandPalette } from "@/components/command/CommandPaletteProvider";
import { useKeyboardShortcut } from "@/lib/hooks/useKeyboardShortcut";
import { useSidebarLayout } from "@/lib/hooks/useSidebarLayout";
import { ThemeToggle } from "@/components/ui/ThemeToggle";
import { NAV_ITEMS } from "@/lib/nav";

export function AppShell({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  const { user, signOut } = useAuth();
  const { toggle: toggleCommandPalette } = useCommandPalette();
  const { width, collapsed, dragging, toggleCollapsed, resizeHandleProps } = useSidebarLayout();

  useKeyboardShortcut({ key: "\\", meta: true }, toggleCollapsed);

  const initial = (user?.displayName ?? user?.email ?? "?").slice(0, 1).toUpperCase();

  return (
    <div className="flex h-dvh w-full overflow-hidden">
      <aside
        style={{ width }}
        className={cn(
          "relative hidden shrink-0 flex-col border-r border-border bg-surface md:flex",
          !dragging && "transition-[width] duration-200",
        )}
        data-collapsed={collapsed || undefined}
      >
        {/* ── Header: wordmark + controls ─────────────────────────────────── */}
        <div
          className={cn(
            "flex h-16 items-center",
            collapsed ? "flex-col justify-center gap-2" : "justify-between px-6",
          )}
        >
          {!collapsed && (
            <span className="font-display text-[20px] font-bold text-text-primary">SkoLab</span>
          )}
          <div className={cn("flex items-center gap-1", collapsed && "flex-col")}>
            {!collapsed && <ThemeToggle />}
            <button
              type="button"
              onClick={toggleCollapsed}
              aria-label={collapsed ? "Expand sidebar" : "Collapse sidebar"}
              aria-keyshortcuts="Meta+\ Control+\"
              title={collapsed ? "Expand sidebar (⌘\\)" : "Collapse sidebar (⌘\\)"}
              className={cn(
                "flex h-8 w-8 items-center justify-center rounded-md text-text-muted transition-colors hover:bg-surface-subtle hover:text-text-primary",
                focusRing,
              )}
            >
              {collapsed ? <PanelLeft size={17} /> : <PanelLeftClose size={17} />}
            </button>
          </div>
        </div>

        {/* ── Search ──────────────────────────────────────────────────────── */}
        <div className={cn("pb-2", collapsed ? "px-2" : "px-3")}>
          <button
            onClick={toggleCommandPalette}
            title="Search (⌘K)"
            aria-label="Search"
            className={cn(
              "flex w-full cursor-pointer items-center gap-2 rounded-md border border-border bg-surface-subtle font-body text-[12.5px] text-text-muted transition-colors duration-[var(--motion-fast)] hover:border-primary/40 hover:text-text-primary",
              collapsed ? "justify-center px-0 py-2" : "px-2.5 py-2",
              focusRing,
            )}
            style={{ transitionTimingFunction: "var(--ease-standard)" }}
          >
            <SearchIcon size={14} />
            {!collapsed && (
              <>
                <span className="flex-1 text-left">Search...</span>
                <kbd className="rounded border border-border px-1 py-0.5 font-mono text-[10px]">⌘K</kbd>
              </>
            )}
          </button>
        </div>

        {/* ── Primary nav ─────────────────────────────────────────────────── */}
        <nav
          aria-label="Primary"
          className={cn("flex flex-1 flex-col gap-1 py-2", collapsed ? "px-2" : "px-3")}
        >
          {NAV_ITEMS.filter((item) => item.href !== "/profile").map((item) => {
            const active = pathname?.startsWith(item.href);
            return (
              <Link
                key={item.href}
                href={item.href}
                aria-current={active ? "page" : undefined}
                title={collapsed ? item.label : undefined}
                className={cn(
                  "relative flex items-center gap-3 rounded-md py-2.5 font-body text-[14px] font-medium transition-colors duration-[var(--motion-fast)]",
                  collapsed ? "justify-center px-0" : "px-3",
                  active ? "text-primary" : "text-text-muted hover:bg-surface-subtle hover:text-text-primary",
                  focusRing,
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
                {!collapsed && <span className="relative z-10">{item.label}</span>}
              </Link>
            );
          })}
        </nav>

        {/* ── Profile / sign out ──────────────────────────────────────────── */}
        <div className={cn("border-t border-border py-3", collapsed ? "px-2" : "px-3")}>
          {collapsed ? (
            <div className="flex flex-col items-center gap-1.5">
              <Link
                href="/profile"
                aria-current={pathname?.startsWith("/profile") ? "page" : undefined}
                title={user?.displayName ?? "Profile"}
                className={cn(
                  "flex h-9 w-9 items-center justify-center rounded-full font-display text-[13px] font-bold text-white shadow-card",
                  focusRing,
                )}
                style={{ background: "var(--primary)" }}
              >
                {initial}
              </Link>
              <button
                onClick={() => signOut()}
                aria-label="Sign out"
                title="Sign out"
                className={cn(
                  "flex h-8 w-8 items-center justify-center rounded-md text-text-muted transition-colors hover:bg-surface-subtle hover:text-notification",
                  focusRing,
                )}
              >
                <LogOut size={15} strokeWidth={1.8} />
              </button>
            </div>
          ) : (
            <div
              className={cn(
                "flex items-center gap-1 rounded-md pl-1 pr-1.5 transition-colors duration-[var(--motion-fast)]",
                pathname?.startsWith("/profile") ? "bg-primary/10" : "hover:bg-surface-subtle",
              )}
              style={{ transitionTimingFunction: "var(--ease-standard)" }}
            >
              {/* Link and the Sign out button are siblings, not nested — an <a> can't contain another interactive element. */}
              <Link
                href="/profile"
                aria-current={pathname?.startsWith("/profile") ? "page" : undefined}
                className={cn("flex min-w-0 flex-1 items-center gap-2.5 rounded-md py-2", focusRing)}
              >
                <div
                  className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full font-display text-[13px] font-bold text-white shadow-card"
                  style={{ background: "var(--primary)" }}
                >
                  {initial}
                </div>
                <div className="min-w-0 flex-1">
                  <p className="truncate font-body text-[13px] font-medium text-text-primary">
                    {user?.displayName ?? "Researcher"}
                  </p>
                  <p className="truncate font-body text-[11.5px] text-text-muted">
                    {user?.isAnonymous ? "Guest session" : (user?.email ?? "Researcher")}
                  </p>
                </div>
              </Link>
              <button
                onClick={() => signOut()}
                aria-label="Sign out"
                title="Sign out"
                className={cn(
                  "flex h-8 w-8 shrink-0 cursor-pointer items-center justify-center rounded-md text-text-muted transition-colors duration-[var(--motion-fast)] hover:bg-surface hover:text-notification",
                  focusRing,
                )}
                style={{ transitionTimingFunction: "var(--ease-standard)" }}
              >
                <LogOut size={16} strokeWidth={1.8} />
              </button>
            </div>
          )}
        </div>

        {/* ── Drag-to-resize handle (expanded only) ───────────────────────── */}
        {!collapsed && (
          <div
            {...resizeHandleProps}
            className={cn(
              "group absolute -right-1 top-0 z-20 flex h-full w-2 cursor-col-resize touch-none items-stretch justify-center",
              focusRing,
            )}
          >
            <span
              className={cn(
                "w-px bg-transparent transition-colors group-hover:bg-primary/50",
                dragging && "bg-primary/60",
              )}
            />
          </div>
        )}
      </aside>

      <div className="flex min-h-0 min-w-0 flex-1 flex-col">
        <header className="flex h-16 shrink-0 items-center justify-between border-b border-border bg-page-bg px-4 md:hidden">
          <span className="font-display text-[18px] font-bold text-text-primary">SkoLab</span>
          <ThemeToggle />
        </header>
        <main className="min-h-0 flex-1 overflow-y-auto bg-page-bg">{children}</main>
        <nav
          aria-label="Primary"
          className="flex h-16 shrink-0 items-center justify-around border-t border-border bg-surface md:hidden"
        >
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
                  focusRing,
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
