"use client";

import Link from "next/link";
import { useQueryClient } from "@tanstack/react-query";
import { FileText, UserPlus, TrendingUp, Quote } from "lucide-react";
import { Card } from "@/components/ui/Card";
import { MathText } from "@/components/ui/MathText";
import { prefetchPaper } from "@/lib/api/prefetch";
import { cn, focusRing } from "@/lib/utils";
import type { ActivityItem } from "@/lib/types";

const KIND: Record<
  ActivityItem["type"],
  { Icon: typeof FileText; tint: string; label: string }
> = {
  paper_published: { Icon: FileText, tint: "var(--primary)", label: "New paper" },
  connection_made: { Icon: UserPlus, tint: "var(--accent-teal)", label: "Connection" },
  trending: { Icon: TrendingUp, tint: "var(--accent-orange)", label: "Trending" },
};

/** Short "3d ago" style stamp; falls back to a date past ~30 days. */
function ago(iso: string): string {
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) return "";
  const s = Math.round((Date.now() - then) / 1000);
  if (s < 60) return "just now";
  if (s < 3600) return `${Math.round(s / 60)}m ago`;
  if (s < 86400) return `${Math.round(s / 3600)}h ago`;
  const d = Math.round(s / 86400);
  if (d < 30) return `${d}d ago`;
  return new Date(iso).toLocaleDateString(undefined, { month: "short", day: "numeric" });
}

function initials(name: string): string {
  return (name.trim()[0] ?? "?").toUpperCase();
}

/**
 * One row of the Home activity stream. Three shapes share a card:
 *  - paper_published: a connected researcher's new work
 *  - connection_made: a new accepted connection
 *  - trending:        a highly-cited recent paper in the user's field
 * The "why" chip is the deliberate "why am I seeing this?" affordance.
 */
export function ActivityFeedItem({ item }: { item: ActivityItem }) {
  const qc = useQueryClient();
  const k = KIND[item.type];
  const actorName = item.actor?.display_name ?? "";
  const isWork = item.object?.kind === "work";
  const warm = () => {
    if (isWork && item.object) prefetchPaper(qc, item.object.id);
  };

  return (
    <Card interactive={false} className="flex flex-col gap-2.5">
      {/* actor / context line */}
      <div className="flex items-center gap-2.5">
        {actorName ? (
          <span
            className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full font-display text-[12px] font-bold text-white"
            style={{ background: "var(--primary)" }}
          >
            {initials(actorName)}
          </span>
        ) : (
          <span
            className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full"
            style={{ background: `color-mix(in srgb, ${k.tint} 16%, transparent)`, color: k.tint }}
          >
            <k.Icon size={15} />
          </span>
        )}
        <div className="min-w-0 flex-1">
          <p className="truncate font-body text-[13px] text-text-primary">
            {actorName ? (
              <>
                <span className="font-semibold">{actorName}</span>{" "}
                <span className="text-text-secondary">{item.verb}</span>
              </>
            ) : (
              <span className="text-text-secondary">
                A paper {item.verb}
              </span>
            )}
          </p>
          <p className="flex items-center gap-1.5 font-mono text-[10px] uppercase tracking-wide text-text-muted">
            <k.Icon size={10} style={{ color: k.tint }} />
            {k.label}
            <span aria-hidden>·</span>
            <span className="normal-case">{ago(item.ts)}</span>
          </p>
        </div>
      </div>

      {/* object — a work card body, or nothing for a bare connection event */}
      {isWork && item.object && (
        <Link
          href={item.href}
          onMouseEnter={warm}
          onFocus={warm}
          className={cn("group rounded-lg border border-border bg-surface-subtle/50 p-3", focusRing)}
        >
          <h3 className="font-display text-[14px] font-semibold leading-snug text-text-primary group-hover:text-primary">
            <MathText text={item.object.title} />
          </h3>
          {(item.object.authors?.length || item.object.venue) && (
            <p className="mt-1 truncate font-body text-[12px] text-text-secondary">
              {(item.object.authors ?? []).slice(0, 3).join(", ")}
              {item.object.venue ? ` · ${item.object.venue}` : ""}
              {item.object.year ? ` · ${item.object.year}` : ""}
            </p>
          )}
        </Link>
      )}

      {!isWork && (
        <Link
          href={item.href}
          className={cn(
            "self-start rounded-md font-body text-[12.5px] font-medium text-primary hover:underline",
            focusRing,
          )}
        >
          View profile
        </Link>
      )}

      {/* why chip — the "why am I seeing this?" line */}
      {item.why && (
        <p className="flex items-center gap-1 font-body text-[11px] text-text-muted">
          <Quote size={10} className="shrink-0" />
          {item.why}
        </p>
      )}
    </Card>
  );
}
