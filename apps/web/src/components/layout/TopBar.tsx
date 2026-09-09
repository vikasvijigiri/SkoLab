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
    <header className="h-16 shrink-0 border-b border-border bg-surface">
      <div className="mx-auto flex h-full w-full max-w-[1128px] items-center gap-3 px-4 md:px-6">
        {/* logo — top-left */}
        <Link
          href="/home"
          className={cn(
            "flex shrink-0 items-center gap-2 rounded-md py-1 pr-1",
            focusRing,
          )}
          aria-label="SkoLab home"
        >
          <span
            className="flex h-9 w-9 items-center justify-center rounded-[4px] font-display text-[22px] font-bold leading-none text-text-on-primary"
            style={{ background: "var(--primary)" }}
          >
            S
          </span>
          <span className="hidden font-display text-[18px] font-semibold tracking-[-0.02em] text-text-primary sm:inline">
            SkoLab
          </span>
        </Link>

        {/* search — centre */}
        <button
          onClick={toggleCommandPalette}
          aria-label="Search"
          title="Search (⌘K)"
          className={cn(
            "flex h-11 min-w-0 flex-1 items-center gap-2 rounded-full border border-border bg-surface-subtle px-4 font-body text-[14px] text-text-secondary transition-colors hover:border-primary hover:bg-surface md:max-w-[360px] lg:max-w-[380px]",
            focusRing,
          )}
          style={{ transitionTimingFunction: "var(--ease-standard)" }}
        >
          <SearchIcon size={15} className="shrink-0" />
          <span className="flex-1 truncate text-left">
            Search papers, people, topics…
          </span>
          <kbd className="hidden rounded border border-border px-1 py-0.5 font-mono text-[10px] sm:inline">
            ⌘K
          </kbd>
        </button>

        {/* primary nav — right-aligned, evenly spaced icon+label tabs */}
        <nav
          aria-label="Primary"
          className="ml-auto hidden shrink-0 items-center gap-1 md:flex lg:gap-2"
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
                  "relative flex h-14 w-[76px] flex-col items-center justify-center gap-1 font-body text-[12px] font-medium leading-none transition-colors",
                  active
                    ? "text-primary"
                    : "text-text-secondary hover:bg-surface-subtle hover:text-text-primary",
                  focusRing,
                )}
              >
                <item.Icon size={19} strokeWidth={1.8} />
                <span className="whitespace-nowrap">{item.label}</span>
                {active && (
                  <motion.span
                    layoutId="topnav-active"
                    className="absolute -bottom-[1px] h-[2px] w-full bg-primary"
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
      </div>
    </header>
  );
}
