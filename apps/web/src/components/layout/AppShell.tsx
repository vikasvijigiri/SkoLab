"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { cn, focusRing } from "@/lib/utils";
import { TopBar } from "./TopBar";
import { ResearchCategoryRail } from "./ResearchCategoryRail";
import { NAV_ITEMS } from "@/lib/nav";

/**
 * App chrome: a global top bar (logo · search · primary nav · notifications ·
 * account menu) with the page below it. Below `md` the primary nav drops to a
 * bottom tab bar; the top bar keeps logo · search · bell · avatar.
 */
/**
 * The category rail's pills (For you/Papers/People/…) are Discovery filters
 * — they never highlight outside Home/Discovery, so showing them elsewhere
 * was permanently-inert clutter (decision 0019's addendum). Hidden on any
 * route under these prefixes; shown everywhere else, including /paper/[id]
 * (reached from Discovery, no pill lights up there, matching how the rail
 * already behaves with no matching href).
 */
const RAIL_HIDDEN_PREFIXES = ["/workspace", "/profile", "/settings"];

export function AppShell({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  const showRail = !RAIL_HIDDEN_PREFIXES.some((p) => pathname?.startsWith(p));

  return (
    <div className="flex h-dvh w-full flex-col overflow-hidden">
      {/* WCAG 2.4.1 — skip the ~8 top-bar tab stops. Visible only on focus. */}
      <a
        href="#main-content"
        className={cn(
          "sr-only z-50 rounded-md bg-primary px-4 py-2 font-body text-body-s font-semibold text-text-on-primary",
          "focus:not-sr-only focus:absolute focus:left-3 focus:top-3 focus:shadow-elevated",
          focusRing,
        )}
      >
        Skip to content
      </a>

      <TopBar />
      {showRail && <ResearchCategoryRail />}

      <main id="main-content" className="surface-atmosphere min-h-0 flex-1 overflow-y-auto bg-page-bg">
        {children}
      </main>

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
                "flex flex-col items-center gap-0.5 rounded-md px-2 py-1 font-body text-[10px] font-medium transition-colors duration-[var(--motion-fast)] active:opacity-60",
                active ? "text-primary" : "text-text-muted hover:text-text-primary",
                focusRing,
              )}
              style={{ transitionTimingFunction: "var(--ease-standard)" }}
            >
              <item.Icon
                size={20}
                strokeWidth={active ? 2 : 1.8}
                className={active ? "text-primary" : item.iconTone}
              />
              {item.label}
            </Link>
          );
        })}
      </nav>
    </div>
  );
}
