"use client";

import { useRef, useState } from "react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { motion, AnimatePresence } from "framer-motion";
import {
  CircleUserRound,
  Settings,
  ShieldCheck,
  LogOut,
  ChevronDown,
  Sun,
  Moon,
  MonitorSmartphone,
} from "lucide-react";
import { useAuth } from "@/lib/hooks/AuthProvider";
import { useClickOutside } from "@/lib/hooks/useClickOutside";
import { applyTheme, initialTheme, nextTheme, type Theme } from "@/lib/theme";
import { cn, focusRing } from "@/lib/utils";

const LINKS = [
  { href: "/profile", label: "Your profile", Icon: CircleUserRound },
  { href: "/settings", label: "Settings", Icon: Settings },
  { href: "/settings#account", label: "Account & privacy", Icon: ShieldCheck },
] as const;

const THEME_ICON: Record<Theme, typeof Sun> = {
  light: Sun,
  dark: Moon,
  system: MonitorSmartphone,
};

export function ProfileMenu() {
  const { user, signOut } = useAuth();
  const pathname = usePathname();
  const [open, setOpen] = useState(false);
  const [theme, setTheme] = useState<Theme>(initialTheme);
  const ref = useRef<HTMLDivElement>(null);
  useClickOutside(ref, () => setOpen(false), open);

  const name = user?.displayName ?? "Researcher";
  const sub = user?.isAnonymous ? "Guest session" : (user?.email ?? "Researcher");
  const initial = (user?.displayName ?? user?.email ?? "?").slice(0, 1).toUpperCase();
  const ThemeIcon = THEME_ICON[theme];

  function cycleTheme() {
    const next = nextTheme(theme);
    setTheme(next);
    applyTheme(next);
  }

  return (
    <div ref={ref} className="relative">
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-label="Account menu"
        className={cn(
          "flex items-center gap-1 rounded-full py-0.5 pl-0.5 pr-1 transition-colors hover:bg-surface-subtle",
          open && "bg-surface-subtle",
          focusRing,
        )}
      >
        <span
          className="flex h-8 w-8 items-center justify-center rounded-full font-display text-[12px] font-bold text-white shadow-card"
          style={{ background: "var(--primary)" }}
        >
          {initial}
        </span>
        <ChevronDown size={14} className={cn("text-text-muted transition-transform", open && "rotate-180")} />
      </button>

      <AnimatePresence>
        {open && (
          <motion.div
            role="menu"
            initial={{ opacity: 0, y: -6, scale: 0.98 }}
            animate={{ opacity: 1, y: 0, scale: 1 }}
            exit={{ opacity: 0, y: -6, scale: 0.98 }}
            transition={{ duration: 0.14 }}
            className="absolute right-0 top-11 z-50 w-64 overflow-hidden rounded-[12px] border border-border bg-surface shadow-elevated"
          >
            <div className="flex items-center gap-2.5 border-b border-border px-3 py-3">
              <span
                className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full font-display text-[13px] font-bold text-white shadow-card"
                style={{ background: "var(--primary)" }}
              >
                {initial}
              </span>
              <span className="min-w-0">
                <span className="block truncate font-body text-[13px] font-semibold text-text-primary">{name}</span>
                <span className="block truncate font-body text-[11.5px] text-text-muted">{sub}</span>
              </span>
            </div>

            <div className="py-1">
              {LINKS.map((l) => {
                const active = pathname === l.href.split("#")[0];
                return (
                  <Link
                    key={l.href}
                    href={l.href}
                    role="menuitem"
                    onClick={() => setOpen(false)}
                    className={cn(
                      "flex items-center gap-2.5 px-3 py-2 font-body text-[13px] transition-colors hover:bg-surface-subtle",
                      active ? "text-primary" : "text-text-secondary hover:text-text-primary",
                    )}
                  >
                    <l.Icon size={15} strokeWidth={1.8} />
                    {l.label}
                  </Link>
                );
              })}
            </div>

            <div className="border-t border-border py-1">
              <button
                type="button"
                onClick={cycleTheme}
                role="menuitem"
                className="flex w-full items-center gap-2.5 px-3 py-2 font-body text-[13px] text-text-secondary transition-colors hover:bg-surface-subtle hover:text-text-primary"
              >
                <ThemeIcon size={15} strokeWidth={1.8} />
                <span className="flex-1 text-left">Theme</span>
                <span className="font-mono text-[11px] capitalize text-text-muted">{theme}</span>
              </button>
            </div>

            <div className="border-t border-border py-1">
              <button
                type="button"
                onClick={() => {
                  setOpen(false);
                  void signOut();
                }}
                role="menuitem"
                className="flex w-full items-center gap-2.5 px-3 py-2 font-body text-[13px] text-text-secondary transition-colors hover:bg-notification/10 hover:text-notification"
              >
                <LogOut size={15} strokeWidth={1.8} />
                Sign out
              </button>
            </div>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}
