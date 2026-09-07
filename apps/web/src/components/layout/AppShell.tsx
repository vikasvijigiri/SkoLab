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

  return (
    <div className="flex h-dvh w-full flex-col overflow-hidden">
      <TopBar />

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
  );
}
