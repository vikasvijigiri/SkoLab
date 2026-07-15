import Link from "next/link";
import { ExternalLink, type LucideIcon } from "lucide-react";
import { Card } from "@/components/ui/Card";
import { MarkdownText } from "@/components/ui/MathText";

export interface BriefItem {
  key: string;
  icon: LucideIcon;
  color: string;
  label: string;
  /** Markdown-lite (supports **bold** and inline/display LaTeX) — rendered via MarkdownText. */
  text: string;
  /** Internal app route ("/paper/...") or an external URL ("https://..."). */
  href?: string;
}

function BriefRowInner({ item, external }: { item: BriefItem; external: boolean }) {
  return (
    <div className="flex items-start gap-2.5">
      <span
        className="mt-0.5 flex h-6 w-6 shrink-0 items-center justify-center rounded-full"
        style={{ backgroundColor: `color-mix(in srgb, ${item.color} 16%, transparent)`, color: item.color }}
      >
        <item.icon size={13} />
      </span>
      <div className="min-w-0 flex-1">
        <p className="font-mono text-[10px] font-semibold uppercase tracking-wide text-text-muted">
          {item.label}
        </p>
        <p className="mt-0.5 flex items-start gap-1 font-body text-[13px] leading-snug text-text-primary">
          <span className="min-w-0"><MarkdownText text={item.text} /></span>
          {external && <ExternalLink size={11} className="mt-0.5 shrink-0 text-text-muted" />}
        </p>
      </div>
    </div>
  );
}

function BriefRow({ item }: { item: BriefItem }) {
  if (!item.href) return <BriefRowInner item={item} external={false} />;

  const rowClass =
    "-mx-1 block rounded-md px-1 py-0.5 transition-colors duration-[var(--motion-fast)] hover:bg-surface-subtle";

  if (item.href.startsWith("http")) {
    return (
      <a href={item.href} target="_blank" rel="noopener noreferrer" className={rowClass}>
        <BriefRowInner item={item} external />
      </a>
    );
  }
  return (
    <Link href={item.href} className={rowClass}>
      <BriefRowInner item={item} external={false} />
    </Link>
  );
}

export function AIDailyBriefCard({ items, loading }: { items: BriefItem[]; loading: boolean }) {
  if (loading) {
    return (
      <Card className="animate-pulse">
        <div className="h-20 rounded-[8px] bg-surface-subtle" />
      </Card>
    );
  }

  return (
    <Card accentColor="var(--accent-violet)" className="relative overflow-hidden">
      <div className="flex items-center gap-2">
        <span
          className="flex h-6 w-6 items-center justify-center rounded-full text-[13px]"
          style={{ backgroundColor: "color-mix(in srgb, var(--accent-violet) 16%, transparent)" }}
        >
          ✨
        </span>
        <p className="font-mono text-[10px] font-semibold uppercase tracking-wide text-accent-violet">
          Your Daily Brief
        </p>
      </div>

      {items.length === 0 ? (
        <p className="mt-2.5 font-body text-[13.5px] leading-relaxed text-text-secondary">
          Building your personalized brief — check back shortly.
        </p>
      ) : (
        <div className="mt-3 flex flex-col gap-3">
          {items.map((item) => (
            <BriefRow key={item.key} item={item} />
          ))}
        </div>
      )}
    </Card>
  );
}
