"use client";

import { Newspaper, ArrowUpRight } from "lucide-react";
import { Card } from "@/components/ui/Card";
import type { ScienceNewsItem } from "@/lib/types";

const SOURCE_TINT: Record<string, string> = {
  "Quanta Magazine": "var(--accent-violet)",
  "Phys.org": "var(--accent-teal)",
  ScienceDaily: "var(--accent-orange)",
  Nature: "var(--primary)",
};

function ago(iso: string): string {
  if (!iso) return "";
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) return "";
  const h = Math.round((Date.now() - then) / 3_600_000);
  if (h < 1) return "just now";
  if (h < 24) return `${h}h ago`;
  const d = Math.round(h / 24);
  if (d < 30) return `${d}d ago`;
  return new Date(iso).toLocaleDateString(undefined, { month: "short", day: "numeric" });
}

/**
 * "In the news" — curated science headlines from Quanta / Phys.org /
 * ScienceDaily / Nature, aggregated non-LLM on the gateway. Headline + source +
 * one-line summary, always a link out.
 */
export function ScienceNewsCard({
  items,
  loading,
}: {
  items: ScienceNewsItem[];
  loading: boolean;
}) {
  if (loading) {
    return (
      <div className="flex flex-col gap-2">
        {[0, 1, 2].map((i) => (
          <div key={i} className="h-16 animate-pulse rounded-[8px] bg-surface-subtle" />
        ))}
      </div>
    );
  }

  if (items.length === 0) {
    return (
      <Card className="text-center">
        <Newspaper size={18} className="mx-auto text-text-muted" />
        <p className="mt-2 font-body text-[12.5px] text-text-muted">
          Science headlines will appear here shortly.
        </p>
      </Card>
    );
  }

  return (
    <div className="flex flex-col gap-2">
      {items.map((n) => {
        const tint = SOURCE_TINT[n.source] ?? "var(--text-muted)";
        return (
          <a
            key={n.url}
            href={n.url}
            target="_blank"
            rel="noopener noreferrer"
            className="group flex flex-col gap-1 rounded-[8px] border border-border bg-surface p-3 transition-colors hover:border-primary/40"
          >
            <div className="flex items-center gap-1.5">
              <span
                className="rounded px-1.5 py-0.5 font-mono text-[9.5px] font-semibold uppercase tracking-wide"
                style={{ backgroundColor: `color-mix(in srgb, ${tint} 14%, transparent)`, color: tint }}
              >
                {n.source}
              </span>
              {n.published && (
                <span className="font-mono text-[10px] text-text-muted">{ago(n.published)}</span>
              )}
              <ArrowUpRight
                size={13}
                className="ml-auto shrink-0 text-text-muted opacity-0 transition-opacity group-hover:opacity-100"
              />
            </div>
            <p className="font-body text-[13px] font-semibold leading-snug text-text-primary group-hover:text-primary">
              {n.title}
            </p>
            {n.summary && (
              <p className="line-clamp-2 font-body text-[12px] leading-relaxed text-text-secondary">
                {n.summary}
              </p>
            )}
          </a>
        );
      })}
    </div>
  );
}
