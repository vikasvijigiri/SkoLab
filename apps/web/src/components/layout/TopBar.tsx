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
    <header className="flex h-14 shrink-0 items-center gap-3 border-b border-border bg-surface px-3 md:px-5">
      {/* logo — top-left */}
      <Link
        href="/home"
        className={cn("flex shrink-0 items-center gap-2 rounded-md py-1 pr-1", focusRing)}
        aria-label="SkoLab home"
      >
        <span
          className="flex h-7 w-7 items-center justify-center rounded-[7px] font-display text-[14px] font-bold text-white"
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
          "flex h-9 min-w-0 flex-1 items-center gap-2 rounded-full border border-border bg-surface-subtle px-3 font-body text-[12.5px] text-text-muted transition-colors hover:border-primary/40 hover:text-text-primary md:max-w-[460px]",
          focusRing,
        )}
        style={{ transitionTimingFunction: "var(--ease-standard)" }}
      >
        <SearchIcon size={15} className="shrink-0" />
        <span className="flex-1 truncate text-left">Search papers, people, topics…</span>
        <kbd className="hidden rounded border border-border px-1 py-0.5 font-mono text-[10px] sm:inline">⌘K</kbd>
      </button>

      {/* nav + bell + account — right */}
      <nav aria-label="Primary" className="hidden shrink-0 items-center gap-0.5 md:flex">
        {BAR_NAV.map((item) => {
          const active = pathname?.startsWith(item.href);
          return (
            <Link
              key={item.href}
              href={item.href}
              aria-current={active ? "page" : undefined}
              title={item.label}
              className={cn(
                "relative flex h-12 w-[62px] flex-col items-center justify-center gap-0.5 rounded-md font-body text-[10.5px] font-medium transition-colors",
                active ? "text-primary" : "text-text-muted hover:bg-surface-subtle hover:text-text-primary",
                focusRing,
              )}
            >
              <item.Icon size={18} strokeWidth={1.8} />
              <span className="max-w-[60px] truncate lg:inline">{item.label}</span>
              {active && (
                <motion.span
                  layoutId="topnav-active"
                  className="absolute -bottom-[7px] h-[2px] w-7 rounded-full bg-primary"
                  transition={{ type: "spring", stiffness: 400, damping: 32 }}
                />
              )}
            </Link>
          );
        })}
      </nav>

      <div className="ml-auto flex shrink-0 items-center gap-1.5 md:ml-1">
        <NotificationsBell />
        <ProfileMenu />
      </div>
    </header>
  );
}
