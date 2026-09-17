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

  // CoLab owns its own Penpot-defined command chrome. Keeping the global bar
  // here would duplicate navigation and consume the writing viewport.
  if (pathname?.startsWith("/workspace/")) return null;

  return (
    <header
      className={cn("h-12 shrink-0", pathname?.startsWith("/workspace") && "workspace-topbar")}
      style={{ background: pathname?.startsWith("/workspace") ? "var(--page-bg)" : "var(--primary)" }}
    >
      <div className="mx-auto flex h-full w-full max-w-[1240px] items-center gap-4 px-5 md:px-8">
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
            className="flex h-7 w-7 items-center justify-center rounded-[4px] bg-primary font-display text-[15px] font-bold leading-none"
            style={{ color: pathname?.startsWith("/workspace") ? "var(--text-on-primary)" : "var(--primary)" }}
          >
            S
          </span>
          <span className={cn("hidden font-display text-[15px] font-semibold tracking-[-0.02em] sm:inline", pathname?.startsWith("/workspace") ? "text-text-primary" : "text-white")}>
            SkoLab
          </span>
        </Link>

        {/* search — centre */}
        <button
          onClick={toggleCommandPalette}
          aria-label="Search"
          title="Search (⌘K)"
          className={cn(
            "flex h-8 min-w-0 flex-1 items-center gap-2 rounded-lg px-3.5 font-body text-[13px] transition-colors md:max-w-[360px] lg:max-w-[400px]",
            focusRing,
            pathname?.startsWith("/workspace") ? "text-text-secondary hover:text-text-primary" : "text-white/85 hover:text-white",
          )}
          style={{
            background: pathname?.startsWith("/workspace") ? "var(--surface)" : "color-mix(in srgb, white 18%, var(--primary))",
            border: pathname?.startsWith("/workspace") ? "1px solid var(--border-color)" : undefined,
            transitionTimingFunction: "var(--ease-standard)",
          }}
        >
          <SearchIcon size={13} className="shrink-0" />
          <span className="flex-1 truncate text-left">
            Search papers, people, topics…
          </span>
          <kbd className={cn("hidden rounded border px-1 py-0.5 font-mono text-[9px] sm:inline", pathname?.startsWith("/workspace") ? "border-border text-text-muted" : "border-white/35 text-white/80")}>
            ⌘K
          </kbd>
        </button>

        {/* primary nav — right-aligned, evenly spaced icon+label tabs */}
        <nav
          aria-label="Primary"
          className="ml-auto hidden shrink-0 items-center gap-1 md:flex"
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
                  "relative flex h-10 w-[66px] flex-col items-center justify-center gap-[3px] rounded-md font-body text-[10px] font-medium leading-none transition-colors",
                  focusRing,
                )}
              >
                <item.Icon
                  size={18}
                  strokeWidth={active ? 2 : 1.8}
                  className="transition-colors duration-[var(--motion-fast)]"
                  style={{ color: pathname?.startsWith("/workspace") ? (active ? "var(--primary)" : "var(--text-muted)") : active ? "#ffffff" : "rgba(255,255,255,0.72)" }}
                />
                <span
                  className="whitespace-nowrap"
                  style={{ color: pathname?.startsWith("/workspace") ? (active ? "var(--primary)" : "var(--text-muted)") : active ? "#ffffff" : "rgba(255,255,255,0.72)" }}
                >
                  {item.label}
                </span>
                {active && (
                  <motion.span
                    layoutId="topnav-active"
                    className={cn("absolute -bottom-[1px] left-[14px] right-[14px] h-[2px] rounded-full", pathname?.startsWith("/workspace") ? "bg-primary" : "bg-white")}
                    transition={{ type: "spring", stiffness: 400, damping: 32 }}
                  />
                )}
              </Link>
            );
          })}
        </nav>

        {/* bell + account — kept apart from the nav by a hairline divider */}
        <div className={cn("flex shrink-0 items-center gap-2 md:ml-3 md:border-l md:pl-3", pathname?.startsWith("/workspace") ? "border-border" : "border-white/25")}>
          <NotificationsBell />
          <ProfileMenu />
        </div>
      </div>
    </header>
  );
}
