"use client";

import { useId, useMemo, useState } from "react";
import { motion } from "framer-motion";
import { Sparkles } from "lucide-react";
import { Card } from "@/components/ui/Card";
import { FeedItemCard } from "./FeedItemCard";
import { buildUnifiedFeed, type FeedKind } from "@/lib/feed/unifiedFeed";
import { useFeedPrefs } from "@/lib/hooks/useFeedPrefs";
import { cn, focusRing } from "@/lib/utils";
import { DURATION_SLOW, EASE_STANDARD } from "@/lib/motion";
import type {
  ActivityItem,
  DailyFeedItem,
  IndustryOpportunity,
  ScienceNewsItem,
} from "@/lib/types";

const LENSES: { key: "all" | FeedKind; label: string }[] = [
  { key: "all", label: "For you" },
  { key: "paper", label: "Papers" },
  { key: "news", label: "News" },
  { key: "job", label: "Roles" },
  { key: "activity", label: "Network" },
];

/**
 * One blended, self-labelling research feed — papers, science news, network
 * activity and roles ranked together (recency + relevance + your Save /
 * Not-relevant feedback). An optional lens filters by kind; "For you" is the
 * default and hides nothing.
 *
 * Marked up per the ARIA APG Feed pattern: a labelled `role="feed"` region,
 * `aria-busy` while loading, one `<article>` per item.
 */
export function UnifiedFeed({
  papers,
  news,
  activity,
  jobs,
  loading,
}: {
  papers: DailyFeedItem[];
  news: ScienceNewsItem[];
  activity: ActivityItem[];
  jobs: IndustryOpportunity[];
  loading: boolean;
}) {
  const { prefs, isSaved, toggleSave, dismiss } = useFeedPrefs();
  const [lens, setLens] = useState<"all" | FeedKind>("all");
  const headingId = useId();

  const items = useMemo(
    () => buildUnifiedFeed({ papers, news, activity, jobs }, prefs),
    [papers, news, activity, jobs, prefs],
  );
  const shown = lens === "all" ? items : items.filter((i) => i.kind === lens);
  const busy = loading && items.length === 0;

  return (
    <section className="flex flex-col gap-3" aria-labelledby={headingId}>
      <div className="flex min-h-9 flex-wrap items-center justify-between gap-2">
        <h2 id={headingId} className="font-display text-[15px] font-semibold text-text-primary">
          Your feed
        </h2>
        <div
          role="group"
          aria-label="Filter the feed"
          className="-mr-1 flex h-9 items-center gap-1 overflow-x-auto pr-1 [mask-image:linear-gradient(to_right,#000_92%,transparent)]"
        >
          {LENSES.map((l) => (
            <button
              key={l.key}
              type="button"
              onClick={() => setLens(l.key)}
              aria-pressed={lens === l.key}
              className={cn(
                "shrink-0 rounded-full px-3 py-1.5 font-body text-[12px] font-medium transition-colors",
                lens === l.key
                  ? "bg-primary text-text-on-primary"
                  : "text-text-muted hover:bg-surface-subtle hover:text-text-primary",
                focusRing,
              )}
            >
              {l.label}
            </button>
          ))}
        </div>
      </div>

      <div
        // role="feed" only once there are real <article> children — a feed
        // holding a lone status message is "bad ARIA".
        role={shown.length > 0 ? "feed" : undefined}
        aria-busy={busy || undefined}
        aria-label={shown.length > 0 ? "Research feed" : undefined}
        // Reserve ~2 cards of height during the load so the skeleton->content
        // swap doesn't shrink the column and shift the page (CLS).
        className={cn("flex flex-col gap-3", (busy || shown.length === 0) && "min-h-[320px]")}
      >
        {busy ? (
          [0, 1, 2].map((i) => (
            <div
              key={i}
              className="h-[150px] animate-pulse rounded-md bg-surface-subtle"
              aria-hidden="true"
            />
          ))
        ) : shown.length === 0 ? (
          <Card className="text-center">
            <Sparkles size={18} className="mx-auto text-text-muted" />
            <p className="mt-2 font-body text-body-s font-medium text-text-primary">
              {lens === "all" ? "Your feed is warming up" : "Nothing here yet"}
            </p>
            <p className="mt-1 font-body text-[12px] leading-relaxed text-text-muted">
              {lens === "all"
                ? "Add a research focus and connect with a few researchers — papers, news and roles in your field land here."
                : "Try “For you”, or check back soon."}
            </p>
          </Card>
        ) : (
          shown.map((item, i) => (
            <motion.article
              key={item.id}
              aria-label={item.title}
              initial={{ opacity: 0, y: 12 }}
              animate={{ opacity: 1, y: 0 }}
              transition={{ duration: DURATION_SLOW, delay: Math.min(i, 8) * 0.04, ease: EASE_STANDARD }}
            >
              <FeedItemCard
                item={item}
                saved={isSaved(item.id)}
                onToggleSave={() => toggleSave(item.id)}
                onDismiss={() => dismiss(item.id, item.kind)}
              />
            </motion.article>
          ))
        )}
      </div>
    </section>
  );
}
