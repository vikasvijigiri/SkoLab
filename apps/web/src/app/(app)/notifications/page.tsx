"use client";

import { useMemo, useState } from "react";
import Link from "next/link";
import { Bell, Bookmark, Settings2 } from "lucide-react";
import { useAuth } from "@/lib/hooks/AuthProvider";
import { useMyProfile } from "@/lib/hooks/useMyProfile";
import { useNotifications, type Notification, type NotificationFilter } from "@/lib/hooks/useNotifications";
import { NOTIFICATION_VISUALS, FILTER_LABELS, agoVerbose } from "@/lib/notificationVisuals";
import { ErrorBanner } from "@/components/ui/ErrorBanner";
import { Chip } from "@/components/ui/Badge";
import { cn } from "@/lib/utils";

/** Groups newest-first items into the day buckets the mockup shows (Today /
 *  Yesterday / This week / Earlier), preserving feed order within a bucket. */
function dayBucket(iso: string): string {
  const d = new Date(iso);
  const now = new Date();
  const startOfDay = (x: Date) => new Date(x.getFullYear(), x.getMonth(), x.getDate()).getTime();
  const diffDays = Math.round((startOfDay(now) - startOfDay(d)) / 86_400_000);
  if (diffDays <= 0) return "Today";
  if (diffDays === 1) return "Yesterday";
  if (diffDays <= 7) return "This week";
  return "Earlier";
}

function groupByDay(items: Notification[]): [string, Notification[]][] {
  const map = new Map<string, Notification[]>();
  for (const n of items) {
    const bucket = dayBucket(n.ts);
    if (!map.has(bucket)) map.set(bucket, []);
    map.get(bucket)!.push(n);
  }
  return Array.from(map.entries());
}

function Row({ n }: { n: Notification }) {
  const { Icon, tint } = NOTIFICATION_VISUALS[n.kind];
  return (
    <Link
      href={n.href}
      className={cn(
        "flex items-start gap-3 border-t border-border px-4 py-3.5 transition-colors first:border-t-0 hover:bg-surface-subtle",
        n.unread && "bg-primary/[0.04]",
      )}
    >
      <span
        className="mt-0.5 flex h-8 w-8 shrink-0 items-center justify-center rounded-full"
        style={{ backgroundColor: `color-mix(in srgb, ${tint} 14%, transparent)`, color: tint }}
      >
        <Icon size={15} />
      </span>
      <span className="min-w-0 flex-1">
        <span className="block font-body text-body-s leading-snug text-text-primary">{n.text}</span>
        <span className="mt-1 block font-mono text-[10.5px] text-text-muted">{agoVerbose(n.ts)}</span>
      </span>
      {n.unread && <span className="mt-2 h-1.5 w-1.5 shrink-0 rounded-full bg-primary" />}
    </Link>
  );
}

function ListSkeleton() {
  return (
    <div className="flex flex-col gap-1 rounded-lg border border-border p-1">
      {[0, 1, 2, 3, 4].map((i) => (
        <div key={i} className="flex items-start gap-3 p-3">
          <div className="h-8 w-8 shrink-0 animate-pulse rounded-full bg-surface-subtle" />
          <div className="flex flex-1 flex-col gap-2 pt-0.5">
            <div className="h-3 w-4/5 animate-pulse rounded bg-surface-subtle" />
            <div className="h-2.5 w-1/4 animate-pulse rounded bg-surface-subtle" />
          </div>
        </div>
      ))}
    </div>
  );
}

export default function NotificationsPage() {
  const { user } = useAuth();
  const { author } = useMyProfile();
  const { items, unreadCount, markAllRead, loading, error, refetch } = useNotifications(author?.id, user?.uid, {
    limit: 40,
    cap: 40,
  });
  const [filter, setFilter] = useState<"all" | NotificationFilter>("all");

  const filtered = useMemo(
    () => (filter === "all" ? items : items.filter((n) => n.filter === filter)),
    [items, filter],
  );
  const groups = useMemo(() => groupByDay(filtered), [filtered]);

  return (
    <div className="mx-auto flex max-w-2xl flex-col gap-5 px-4 py-8 md:px-6">
      <div className="flex items-center justify-between">
        <h1 className="font-display text-display-m font-bold text-text-primary">Notifications</h1>
        <div className="flex items-center gap-4">
          {unreadCount > 0 && (
            <button
              type="button"
              onClick={markAllRead}
              className="font-body text-[12.5px] font-semibold text-primary hover:underline"
            >
              Mark all read
            </button>
          )}
          <Link
            href="/notifications/manage"
            className="flex items-center gap-1.5 font-body text-[12.5px] font-semibold text-text-secondary transition-colors hover:text-text-primary"
          >
            <Settings2 size={14} />
            Manage alerts
          </Link>
        </div>
      </div>

      <div className="flex flex-wrap gap-2">
        {FILTER_LABELS.map((f) => (
          <Chip key={f.key} selected={filter === f.key} onClick={() => setFilter(f.key)}>
            {f.label}
          </Chip>
        ))}
      </div>

      {loading ? (
        <ListSkeleton />
      ) : error ? (
        <ErrorBanner message="Couldn't load your notifications." onRetry={() => refetch()} />
      ) : items.length === 0 ? (
        <div className="flex flex-col items-center gap-3 rounded-lg border border-dashed border-border-strong bg-surface px-6 py-12 text-center">
          <span className="flex h-11 w-11 items-center justify-center rounded-md bg-surface-subtle text-text-muted">
            <Bell size={20} />
          </span>
          <div>
            <p className="font-body text-body-s font-semibold text-text-primary">Nothing here yet</p>
            <p className="mx-auto mt-1.5 max-w-sm font-body text-[12.5px] leading-relaxed text-text-secondary">
              Citations to your own papers will show up automatically. To hear about new work from specific
              people or topics, track a few in Discovery.
            </p>
          </div>
          <Link
            href="/discovery"
            className="mt-1 flex h-10 items-center gap-2 rounded-md bg-primary px-4 font-body text-[12.5px] font-bold text-text-on-primary transition-colors hover:bg-primary-dark"
          >
            <Bookmark size={14} />
            Track researchers in Discovery
          </Link>
        </div>
      ) : filtered.length === 0 ? (
        <p className="rounded-lg border border-border bg-surface px-4 py-8 text-center font-body text-body-s text-text-muted">
          Nothing in {FILTER_LABELS.find((f) => f.key === filter)?.label} yet.
        </p>
      ) : (
        groups.map(([bucket, rows]) => (
          <div key={bucket} className="flex flex-col gap-2">
            <p className="font-mono text-[11px] font-semibold uppercase tracking-wide text-text-muted">{bucket}</p>
            <div className="overflow-hidden rounded-lg border border-border">
              {rows.map((n) => (
                <Row key={n.id} n={n} />
              ))}
            </div>
          </div>
        ))
      )}
    </div>
  );
}
