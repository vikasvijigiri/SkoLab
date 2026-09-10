"use client";

import { useId } from "react";
import Link from "next/link";
import { ExternalLink, type LucideIcon } from "lucide-react";
import { Card } from "@/components/ui/Card";
import { MarkdownText } from "@/components/ui/MathText";
import { cn, focusRing } from "@/lib/utils";

export interface BriefItem {
  key: string;
  icon: LucideIcon;
  /** A design token (`var(--accent-*)`) — one hue per service so grant, role
   * and journal briefs stay visually distinct across the rail. */
  color: string;
  label: string;
  /** Markdown-lite (supports **bold** and inline/display LaTeX) — rendered via MarkdownText. */
  text: string;
  /** Internal app route ("/paper/...") or an external URL ("https://..."). */
  href?: string;
}

function BriefCardInner({ item, external }: { item: BriefItem; external: boolean }) {
  return (
    <>
      <div className="flex items-center gap-2">
        <span
          aria-hidden="true"
          className="flex h-6 w-6 shrink-0 items-center justify-center rounded-full"
          style={{ backgroundColor: `color-mix(in srgb, ${item.color} 16%, transparent)`, color: item.color }}
        >
          <item.icon size={13} />
        </span>
        <p
          className="min-w-0 flex-1 truncate font-mono text-[11px] font-semibold uppercase tracking-wide"
          style={{ color: item.color }}
        >
          {item.label}
        </p>
        {external && <ExternalLink size={11} className="shrink-0 text-text-muted" />}
      </div>
      <p className="mt-2 line-clamp-4 font-body text-body-s leading-snug text-text-primary">
        <MarkdownText text={item.text} />
      </p>
    </>
  );
}

/** "rail" = horizontal scroller (default, sits at the top of a wide column);
 * "stack" = vertical list that fills its column's width (the narrow left
 * column on Home, which owns its own scrollbar). */
type BriefLayout = "rail" | "stack";

/** One service = one colour-accented card. A left accent bar reads as a ledger
 * row (Card's `accentSide="left"` convention for data cards). */
function BriefCard({ item, layout }: { item: BriefItem; layout: BriefLayout }) {
  const external = !!item.href && item.href.startsWith("http");
  const card = (
    <Card
      accentColor={item.color}
      accentSide="left"
      interactive={!!item.href}
      className={cn("flex h-full flex-col", layout === "stack" ? "w-full" : "w-64")}
    >
      <BriefCardInner item={item} external={external} />
    </Card>
  );

  const liClass = layout === "stack" ? undefined : "shrink-0 snap-start";

  if (!item.href) {
    return <li className={liClass}>{card}</li>;
  }

  const linkClass = cn("block h-full rounded-sm", focusRing);
  return (
    <li className={liClass}>
      {external ? (
        <a href={item.href} target="_blank" rel="noopener noreferrer" className={linkClass}>
          {card}
        </a>
      ) : (
        <Link href={item.href} className={linkClass}>
          {card}
        </Link>
      )}
    </li>
  );
}

export function AIDailyBriefCard({
  items,
  loading,
  layout = "rail",
}: {
  items: BriefItem[];
  loading: boolean;
  layout?: BriefLayout;
}) {
  const headingId = useId();
  const stack = layout === "stack";

  return (
    <section aria-labelledby={headingId} className="flex flex-col gap-3">
      <div className="flex items-center gap-2">
        <span
          aria-hidden="true"
          className="flex h-6 w-6 items-center justify-center rounded-full text-body-s"
          style={{ backgroundColor: "color-mix(in srgb, var(--accent-violet) 16%, transparent)" }}
        >
          ✨
        </span>
        <h2
          id={headingId}
          className="font-mono text-[11px] font-semibold uppercase tracking-wide text-accent-violet"
        >
          Your Daily Brief
        </h2>
      </div>

      {loading ? (
        <div className={cn("flex gap-3", stack ? "flex-col" : "overflow-hidden")}>
          {[0, 1, 2].map((i) => (
            <div
              key={i}
              className={cn(
                "h-[132px] shrink-0 animate-pulse rounded-sm bg-surface-subtle",
                stack ? "w-full" : "w-64",
              )}
            />
          ))}
        </div>
      ) : items.length === 0 ? (
        <p className="font-body text-body-s leading-relaxed text-text-secondary">
          Building your personalized brief — check back shortly.
        </p>
      ) : (
        <ul
          // "rail": horizontally scrollable, a right-edge fade hints at the
          // overflow (matches UnifiedFeed's lens row). "stack": a plain vertical
          // list — the column it lives in owns the scroll. Keyboard users reach
          // each card through its own link either way.
          aria-label="Daily brief"
          className={cn(
            "flex gap-3",
            stack
              ? "flex-col"
              : "-mx-1 snap-x snap-mandatory overflow-x-auto px-1 pb-1 [scrollbar-width:thin] [mask-image:linear-gradient(to_right,#000_92%,transparent)]",
          )}
        >
          {items.map((item) => (
            <BriefCard key={item.key} item={item} layout={layout} />
          ))}
        </ul>
      )}
    </section>
  );
}
