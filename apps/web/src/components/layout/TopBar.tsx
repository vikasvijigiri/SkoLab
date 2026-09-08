"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { motion } from "framer-motion";
import { Search as SearchIcon } from "lucide-react";
import { cn, focusRing } from "@/lib/utils";
import { useCommandPalette } from "@/components/command/CommandPaletteProvider";
import { NotificationsBell } from "./NotificationsBell";
import { ProfileMenu } from "./ProfileMenu";
import { NAV_ITEMS } from "@/lib/nav";

/** Primary nav destinations (Profile lives in the account menu, not the bar). */
const BAR_NAV = NAV_ITEMS.filter((i) => i.href !== "/profile");

export function TopBar() {
  const pathname = usePathname();
  const { toggle: toggleCommandPalette } = useCommandPalette();

  return (
    <header className="flex h-14 shrink-0 items-center gap-4 border-b border-border bg-surface px-4 md:px-6">
      {/* logo — top-left */}
      <Link
        href="/home"
        className={cn("flex shrink-0 items-center gap-2 rounded-md py-1 pr-1", focusRing)}
        aria-label="SkoLab home"
      >
        <span
          className="flex h-7 w-7 items-center justify-center rounded-[7px] font-display text-body font-bold text-text-on-primary"
          style={{ background: "var(--primary)" }}
        >
          S
        </span>
        <span className="hidden font-display text-[17px] font-bold text-text-primary sm:inline">SkoLab</span>
      </Link>

      {/* search — centre */}
      <button
        onClick={toggleCommandPalette}
        aria-label="Search"
        title="Search (⌘K)"
        className={cn(
          "flex h-9 min-w-0 flex-1 items-center gap-2 rounded-full border border-border bg-surface-subtle px-3 font-body text-body-s text-text-muted transition-colors hover:border-primary/40 hover:text-text-primary md:max-w-[420px] lg:max-w-[480px]",
          focusRing,
        )}
        style={{ transitionTimingFunction: "var(--ease-standard)" }}
      >
        <SearchIcon size={15} className="shrink-0" />
        <span className="flex-1 truncate text-left">Search papers, people, topics…</span>
        <kbd className="hidden rounded border border-border px-1 py-0.5 font-mono text-[10px] sm:inline">⌘K</kbd>
      </button>

      {/* primary nav — right-aligned, evenly spaced icon+label tabs */}
      <nav
        aria-label="Primary"
        className="ml-auto hidden shrink-0 items-center gap-2 md:flex lg:gap-2"
      >
        {BAR_NAV.map((item) => {
          const active = pathname?.startsWith(item.href);
          return (
            <Link
              key={item.href}
              href={item.href}
              aria-current={active ? "page" : undefined}
              title={item.label}
              className={cn(
                "relative flex h-12 w-[76px] flex-col items-center justify-center gap-1 rounded-lg font-body text-[11px] font-medium leading-none transition-colors",
                active ? "text-primary" : "text-text-muted hover:bg-surface-subtle hover:text-text-primary",
                focusRing,
              )}
            >
              <item.Icon size={19} strokeWidth={1.8} />
              <span className="whitespace-nowrap">{item.label}</span>
              {active && (
                <motion.span
                  layoutId="topnav-active"
                  className="absolute -bottom-[7px] h-[2px] w-8 rounded-full bg-primary"
                  transition={{ type: "spring", stiffness: 400, damping: 32 }}
                />
              )}
            </Link>
          );
        })}
      </nav>

      {/* bell + account — kept apart from the nav by a hairline divider */}
      <div className="flex shrink-0 items-center gap-2 md:ml-3 md:border-l md:border-border md:pl-3">
        <NotificationsBell />
        <ProfileMenu />
      </div>
    </header>
  );
}
