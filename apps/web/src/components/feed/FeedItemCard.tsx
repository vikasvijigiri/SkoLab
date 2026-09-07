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
  Info,
} from "lucide-react";
import { Card } from "@/components/ui/Card";
import { MathText } from "@/components/ui/MathText";
import { prefetchPaper } from "@/lib/api/prefetch";
import { cn, focusRing } from "@/lib/utils";
import type { FeedKind, UnifiedItem } from "@/lib/feed/unifiedFeed";

const KIND: Record<FeedKind, { Icon: typeof FileText; tint: string; label: string }> = {
  paper: { Icon: FileText, tint: "var(--primary)", label: "Paper" },
  news: { Icon: Newspaper, tint: "var(--accent-teal)", label: "News" },
  activity: { Icon: Users2, tint: "var(--accent-violet)", label: "Network" },
  job: { Icon: Briefcase, tint: "var(--accent-orange)", label: "Role" },
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

  const headline = (
    <h3 className="font-display text-[14.5px] font-semibold leading-snug text-text-primary group-hover:text-primary">
      <MathText text={item.title} />
      {item.external && (
        <ArrowUpRight size={13} className="ml-1 inline-block -translate-y-px text-text-muted" />
      )}
    </h3>
  );

  return (
    <Card interactive={false} className="flex flex-col gap-2">
      {/* kind chip + source + when */}
      <div className="flex items-center gap-2">
        <span
          className="flex items-center gap-1 rounded px-1.5 py-0.5 font-mono text-[11px] font-semibold uppercase tracking-wide"
          style={{
            backgroundColor: `color-mix(in srgb, ${k.tint} 14%, transparent)`,
            // Blend toward ink so the small chip label clears WCAG AA on the
            // tinted background (raw accent-orange measured ~4.4:1).
            color: `color-mix(in srgb, ${k.tint} 62%, var(--text-primary))`,
          }}
        >
          <k.Icon size={11} aria-hidden="true" />
          {k.label}
        </span>
        {item.source && (
          <span className="truncate font-body text-[11.5px] text-text-muted">{item.source}</span>
        )}
        {item.ts > 0 && (
          <time dateTime={new Date(item.ts).toISOString()} className="font-mono text-[11px] text-text-muted">
            · {ago(item.ts)}
          </time>
        )}
      </div>

      {/* headline — the single primary target for the card */}
      {item.external ? (
        <a href={item.href} target="_blank" rel="noopener noreferrer" className={cn("group rounded", focusRing)}>
          {headline}
        </a>
      ) : (
        <Link href={item.href} onMouseEnter={warm} onFocus={warm} className={cn("group rounded", focusRing)}>
          {headline}
        </Link>
      )}

      {item.meta && (
        <p className="line-clamp-2 max-w-[68ch] font-body text-[12px] leading-relaxed text-text-secondary">
          <MathText text={item.meta} />
        </p>
      )}

      {/* why + save / dismiss */}
      <div className="mt-1 flex items-center justify-between gap-2">
        <p className="flex min-w-0 items-center gap-1 font-body text-[11.5px] text-text-muted">
          <Info size={11} className="shrink-0" aria-hidden="true" />
          <span className="truncate">{item.why}</span>
        </p>
        <div className="flex shrink-0 items-center gap-1">
          <button
            type="button"
            onClick={onToggleSave}
            aria-pressed={saved}
            aria-label={saved ? "Saved — tap to remove" : "Save this"}
            title={saved ? "Saved" : "Save"}
            className={cn(
              "flex h-8 w-8 items-center justify-center rounded-md transition-colors hover:bg-surface-subtle",
              saved ? "text-primary" : "text-text-muted hover:text-text-primary",
              focusRing,
            )}
          >
            {saved ? <BookmarkCheck size={15} /> : <Bookmark size={15} />}
          </button>
          <button
            type="button"
            onClick={onDismiss}
            aria-label="Not relevant — hide this and show fewer like it"
            title="Not relevant"
            className={cn(
              "flex h-8 w-8 items-center justify-center rounded-md text-text-muted transition-colors hover:bg-surface-subtle hover:text-text-primary",
              focusRing,
            )}
          >
            <X size={15} />
          </button>
        </div>
      </div>
    </Card>
  );
}
