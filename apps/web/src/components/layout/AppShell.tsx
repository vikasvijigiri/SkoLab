"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { cn, focusRing } from "@/lib/utils";
import { TopBar } from "./TopBar";
import { NAV_ITEMS } from "@/lib/nav";

/**
 * App chrome: a global top bar (logo · search · primary nav · notifications ·
 * account menu) with the page below it. Below `md` the primary nav drops to a
 * bottom tab bar; the top bar keeps logo · search · bell · avatar.
 */
export function AppShell({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  // A document workspace has its own SkoLab ribbon and a deliberately scoped
  // manuscript scroll region. Keeping the global shell chrome here would
  // duplicate navigation and introduce a second vertical scroll container.
  const isWorkspaceDetail = /^\/workspace\/[^/]+$/.test(pathname ?? "");

  return (
    <div className="flex h-dvh w-full flex-col overflow-hidden bg-page-bg">
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

      <main
        id="main-content"
        className={cn(
          "surface-atmosphere min-h-0 flex-1 bg-page-bg p-3 sm:p-4 lg:p-6",
          "flex",
          isWorkspaceDetail ? "overflow-hidden" : "overflow-y-auto",
        )}
      >
        <div className="hasamex-page-frame mx-auto flex h-full min-h-0 min-w-0 flex-1 flex-col overflow-hidden">
          {!isWorkspaceDetail && <TopBar />}
          <div className={cn("min-h-0 flex-1", isWorkspaceDetail ? "overflow-hidden" : "overflow-y-auto")}>
            {children}
          </div>
        </div>
      </main>

      {!isWorkspaceDetail && <nav
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
      </nav>}
    </div>
  );
}
