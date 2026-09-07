"use client";

import Link from "next/link";
import { useQueryClient } from "@tanstack/react-query";
import {
  FileText,
  Newspaper,
  Users2,
  Briefcase,
  Bookmark,
  BookmarkCheck,
  X,
  ArrowUpRight,
  HelpCircle,
} from "lucide-react";
import { Card } from "@/components/ui/Card";
import { MathText } from "@/components/ui/MathText";
import { prefetchPaper } from "@/lib/api/prefetch";
import { cn, focusRing } from "@/lib/utils";
import type { FeedKind, UnifiedItem } from "@/lib/feed/unifiedFeed";

const KIND: Record<FeedKind, { Icon: typeof FileText; tint: string; label: string; action: string }> = {
  paper: { Icon: FileText, tint: "var(--primary)", label: "Paper", action: "Read" },
  news: { Icon: Newspaper, tint: "var(--accent-teal)", label: "News", action: "Read" },
  activity: { Icon: Users2, tint: "var(--accent-violet)", label: "Network", action: "View" },
  job: { Icon: Briefcase, tint: "var(--accent-orange)", label: "Role", action: "View" },
};

function ago(ts: number): string {
  if (!ts) return "";
  const s = Math.round((Date.now() - ts) / 1000);
  if (s < 3600) return `${Math.max(1, Math.round(s / 60))}m`;
  if (s < 86400) return `${Math.round(s / 3600)}h`;
  const d = Math.round(s / 86400);
  return d < 30 ? `${d}d` : new Date(ts).toLocaleDateString(undefined, { month: "short", day: "numeric" });
}

export function FeedItemCard({
  item,
  saved,
  onToggleSave,
  onDismiss,
}: {
  item: UnifiedItem;
  saved: boolean;
  onToggleSave: () => void;
  onDismiss: () => void;
}) {
  const qc = useQueryClient();
  const k = KIND[item.kind];
  const isPaper = item.kind === "paper";
  const warm = () => {
    if (isPaper) prefetchPaper(qc, item.id.replace(/^paper:/, ""));
  };

  const Primary = item.external ? (
    <a
      href={item.href}
      target="_blank"
      rel="noopener noreferrer"
      className="flex items-center gap-1 rounded-md border border-border px-2.5 py-1 font-body text-[12px] font-medium text-text-secondary transition-colors hover:border-primary/40 hover:text-text-primary"
    >
      {k.action} <ArrowUpRight size={12} />
    </a>
  ) : (
    <Link
      href={item.href}
      onMouseEnter={warm}
      onFocus={warm}
      className="rounded-md border border-border px-2.5 py-1 font-body text-[12px] font-medium text-text-secondary transition-colors hover:border-primary/40 hover:text-text-primary"
    >
      {k.action}
    </Link>
  );

  return (
    <Card interactive={false} className="flex flex-col gap-2">
      {/* kind chip + when */}
      <div className="flex items-center gap-1.5">
        <span
          className="flex items-center gap-1 rounded px-1.5 py-0.5 font-mono text-[9.5px] font-semibold uppercase tracking-wide"
          style={{ backgroundColor: `color-mix(in srgb, ${k.tint} 14%, transparent)`, color: k.tint }}
        >
          <k.Icon size={10} />
          {k.label}
        </span>
        {item.source && (
          <span className="truncate font-body text-[10.5px] text-text-muted">{item.source}</span>
        )}
        {item.ts > 0 && <span className="font-mono text-[10px] text-text-muted">· {ago(item.ts)}</span>}
      </div>

      {/* headline */}
      {item.external ? (
        <a href={item.href} target="_blank" rel="noopener noreferrer" className={cn("group rounded", focusRing)}>
          <h3 className="font-display text-[14.5px] font-semibold leading-snug text-text-primary group-hover:text-primary">
            <MathText text={item.title} />
          </h3>
        </a>
      ) : (
        <Link
          href={item.href}
          onMouseEnter={warm}
          onFocus={warm}
          className={cn("group rounded", focusRing)}
        >
          <h3 className="font-display text-[14.5px] font-semibold leading-snug text-text-primary group-hover:text-primary">
            <MathText text={item.title} />
          </h3>
        </Link>
      )}

      {item.meta && (
        <p className="line-clamp-2 font-body text-[12px] leading-relaxed text-text-secondary">
          <MathText text={item.meta} />
        </p>
      )}

      {/* why + actions */}
      <div className="mt-0.5 flex items-center justify-between gap-2">
        <p className="flex min-w-0 items-center gap-1 font-body text-[11px] text-text-muted">
          <HelpCircle size={11} className="shrink-0" />
          <span className="truncate">{item.why}</span>
        </p>
        <div className="flex shrink-0 items-center gap-1.5">
          {Primary}
          <button
            type="button"
            onClick={onToggleSave}
            aria-label={saved ? "Remove from saved" : "Save"}
            title={saved ? "Saved" : "Save"}
            className={cn(
              "flex h-7 w-7 items-center justify-center rounded-md transition-colors hover:bg-surface-subtle",
              saved ? "text-primary" : "text-text-muted hover:text-text-primary",
            )}
          >
            {saved ? <BookmarkCheck size={14} /> : <Bookmark size={14} />}
          </button>
          <button
            type="button"
            onClick={onDismiss}
            aria-label="Not relevant — hide and show fewer like this"
            title="Not relevant"
            className="flex h-7 w-7 items-center justify-center rounded-md text-text-muted transition-colors hover:bg-surface-subtle hover:text-text-primary"
          >
            <X size={14} />
          </button>
        </div>
      </div>
    </Card>
  );
}
