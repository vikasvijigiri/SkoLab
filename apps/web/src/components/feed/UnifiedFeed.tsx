"use client";

import { useMemo, useState } from "react";
import { motion } from "framer-motion";
import { Sparkles } from "lucide-react";
import { Card } from "@/components/ui/Card";
import { FeedItemCard } from "./FeedItemCard";
import { buildUnifiedFeed, type FeedKind } from "@/lib/feed/unifiedFeed";
import { useFeedPrefs } from "@/lib/hooks/useFeedPrefs";
import { cn } from "@/lib/utils";
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

  const items = useMemo(
    () => buildUnifiedFeed({ papers, news, activity, jobs }, prefs),
    [papers, news, activity, jobs, prefs],
  );
  const shown = lens === "all" ? items : items.filter((i) => i.kind === lens);

  return (
    <section className="flex flex-col gap-3">
      <div className="flex items-center gap-1 overflow-x-auto pb-0.5">
        {LENSES.map((l) => (
          <button
            key={l.key}
            type="button"
            onClick={() => setLens(l.key)}
            className={cn(
              "shrink-0 rounded-full px-3 py-1 font-body text-[12px] font-medium transition-colors",
              lens === l.key
                ? "bg-primary text-text-on-primary"
                : "text-text-muted hover:bg-surface-subtle hover:text-text-primary",
            )}
          >
            {l.label}
          </button>
        ))}
      </div>

      {loading && items.length === 0 ? (
        <div className="flex flex-col gap-3">
          {[0, 1, 2, 3].map((i) => (
            <div key={i} className="h-32 animate-pulse rounded-[8px] bg-surface-subtle" />
          ))}
        </div>
      ) : shown.length === 0 ? (
        <Card className="text-center">
          <Sparkles size={18} className="mx-auto text-text-muted" />
          <p className="mt-2 font-body text-[13px] font-medium text-text-primary">
            {lens === "all" ? "Your feed is warming up" : "Nothing here yet"}
          </p>
          <p className="mt-0.5 font-body text-[12px] leading-relaxed text-text-muted">
            {lens === "all"
              ? "Add a research focus and connect with a few researchers — papers, news and roles in your field land here."
              : "Try “For you”, or check back soon."}
          </p>
        </Card>
      ) : (
        <div className="flex flex-col gap-3">
          {shown.map((item, i) => (
            <motion.div
              key={item.id}
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
            </motion.div>
          ))}
        </div>
      )}
    </section>
  );
}
