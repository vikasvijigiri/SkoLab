/**
 * Trending topics — a topic's growth is (recent window count) vs the
 * equal-length prior window, never a raw cumulative count. OpenAlex's own
 * `/topics/{id}` entity exposes only cumulative `works_count`/`cited_by_count`
 * (verified live) — no growth field exists there, so trend is always derived
 * client-side from two `/works?group_by=topics.id` calls. See `decisions/0015`.
 *
 * A topic below `trendingMinPriorWorks` is dropped rather than ranked: 1 → 4
 * works reads as "300% growth" but is noise from a tiny base, not a trend —
 * the same discipline `signals.ts` already applies to author momentum.
 */
import { DISCOVERY_CONFIG } from "./config";
import type { TopicActivityBucket, TrendingTopic } from "@/lib/types";

export function computeTopicGrowth(
  recent: TopicActivityBucket[],
  prior: TopicActivityBucket[],
  opts: { minPriorWorks?: number; topN?: number } = {},
): TrendingTopic[] {
  const minPriorWorks = opts.minPriorWorks ?? DISCOVERY_CONFIG.trendingMinPriorWorks;
  const topN = opts.topN ?? DISCOVERY_CONFIG.trendingTopN;
  const priorById = new Map(prior.map((b) => [b.id, b]));

  const rows: TrendingTopic[] = [];
  for (const r of recent) {
    const priorCount = priorById.get(r.id)?.count ?? 0;
    // Also guards the division below — a topic with no prior presence has an
    // undefined growth rate, not an infinite one.
    if (priorCount < minPriorWorks) continue;
    // A shrinking topic isn't "trending" by definition — filtered below.
    rows.push({
      id: r.id,
      displayName: r.displayName,
      recentCount: r.count,
      priorCount,
      growth: (r.count - priorCount) / priorCount,
    });
  }

  const growing = rows.filter((r) => r.growth > 0);
  growing.sort((a, b) => b.growth - a.growth);
  return growing.slice(0, topN);
}
