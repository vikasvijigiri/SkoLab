"use client";

import { useRef, useState } from "react";
import Link from "next/link";
import { motion, AnimatePresence } from "framer-motion";
import { Bell, UserPlus, FileText } from "lucide-react";
import { useAuth } from "@/lib/hooks/AuthProvider";
import { useMyProfile } from "@/lib/hooks/useMyProfile";
import { useNotifications, type Notification } from "@/lib/hooks/useNotifications";
import { useClickOutside } from "@/lib/hooks/useClickOutside";
import { cn, focusRing } from "@/lib/utils";

function ago(iso: string): string {
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) return "";
  const h = Math.round((Date.now() - then) / 3_600_000);
  if (h < 1) return "just now";
  if (h < 24) return `${h}h`;
  const d = Math.round(h / 24);
  return d < 30 ? `${d}d` : new Date(iso).toLocaleDateString(undefined, { month: "short", day: "numeric" });
}

function Row({ n, onNavigate }: { n: Notification; onNavigate: () => void }) {
  const Icon = n.kind === "connection" ? UserPlus : FileText;
  const tint = n.kind === "connection" ? "var(--accent-teal)" : "var(--primary)";
  return (
    <Link
      href={n.href}
      onClick={onNavigate}
      className={cn(
        "flex items-start gap-3 px-3 py-3 transition-colors hover:bg-surface-subtle",
        n.unread && "bg-primary/[0.04]",
      )}
    >
      <span
        className="mt-1 flex h-7 w-7 shrink-0 items-center justify-center rounded-full"
        style={{ backgroundColor: `color-mix(in srgb, ${tint} 14%, transparent)`, color: tint }}
      >
        <Icon size={13} />
      </span>
      <span className="min-w-0 flex-1">
        <span className="block font-body text-body-s leading-snug text-text-primary">{n.text}</span>
        <span className="mt-1 block font-mono text-[10px] text-text-muted">{ago(n.ts)}</span>
      </span>
      {n.unread && <span className="mt-2 h-1.5 w-1.5 shrink-0 rounded-full bg-primary" />}
    </Link>
  );
}

export function NotificationsBell() {
  const { user } = useAuth();
  const { author } = useMyProfile();
  const { items, unreadCount, markAllRead, loading } = useNotifications(author?.id, user?.uid);
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  useClickOutside(ref, () => setOpen(false), open);

  function toggle() {
    const next = !open;
    setOpen(next);
    if (next && unreadCount > 0) markAllRead();
  }

  return (
    <div ref={ref} className="relative">
      <button
        type="button"
        onClick={toggle}
        aria-label={unreadCount > 0 ? `Notifications, ${unreadCount} unread` : "Notifications"}
        aria-haspopup="menu"
        aria-expanded={open}
        className={cn(
          "relative flex h-9 w-9 items-center justify-center rounded-full text-text-muted transition-colors hover:bg-surface-subtle hover:text-text-primary",
          open && "bg-surface-subtle text-text-primary",
          focusRing,
        )}
      >
        <Bell size={18} strokeWidth={1.8} />
        {unreadCount > 0 && (
          <span className="absolute -right-0.5 -top-0.5 flex h-4 min-w-4 items-center justify-center rounded-full bg-notification px-1 font-mono text-[9px] font-bold text-white">
            {unreadCount > 9 ? "9+" : unreadCount}
          </span>
        )}
      </button>

      <AnimatePresence>
        {open && (
          <motion.div
            role="menu"
            initial={{ opacity: 0, y: -6 }}
            animate={{ opacity: 1, y: 0 }}
            exit={{ opacity: 0, y: -6 }}
            transition={{ duration: 0.14 }}
            className="absolute right-0 top-11 z-50 w-[340px] overflow-hidden rounded-md border border-border bg-surface shadow-elevated"
          >
            <div className="flex items-center justify-between border-b border-border px-3 py-3">
              <span className="font-display text-body-s font-semibold text-text-primary">Notifications</span>
              {unreadCount > 0 && (
                <button
                  type="button"
                  onClick={markAllRead}
                  className="font-body text-[11.5px] font-medium text-primary hover:underline"
                >
                  Mark all read
                </button>
              )}
            </div>

            <div className="max-h-[380px] divide-y divide-border overflow-y-auto">
              {loading ? (
                <div className="flex flex-col gap-2 p-3">
                  {[0, 1, 2].map((i) => (
                    <div key={i} className="h-10 animate-pulse rounded bg-surface-subtle" />
                  ))}
                </div>
              ) : items.length === 0 ? (
                <div className="px-3 py-8 text-center">
                  <Bell size={18} className="mx-auto text-text-muted" />
                  <p className="mt-2 font-body text-[12px] text-text-muted">
                    You&apos;re all caught up. New connections and papers from your network land here.
                  </p>
                </div>
              ) : (
                items.map((n) => <Row key={n.id} n={n} onNavigate={() => setOpen(false)} />)
              )}
            </div>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}
