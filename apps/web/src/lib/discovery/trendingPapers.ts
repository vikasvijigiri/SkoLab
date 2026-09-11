/**
 * Trending papers — citations-per-day-since-publication, never a raw or
 * cumulative citation count. Matches the methodology Semantic Scholar
 * discloses for its own "Trending Papers" (citations per month) — the finer
 * per-day unit here is just because OpenAlex gives an exact `publication_date`.
 *
 * A paper below `trendingPapersMinCitations` is dropped rather than ranked:
 * a single early citation on a day-old paper divides by a tiny age and reads
 * as "hot" by fluke, not a real signal — the same noise-floor discipline
 * `trendingTopics.ts` applies to topic growth (decisions/0015).
 *
 * A non-`article` work (`type`) is dropped too: verification surfaced a
 * journal-issue "paratext" entry with 561 citations attributed to it four
 * days after its listed date — OpenAlex's per-work citation count can land
 * on front matter, not a real paper. The route also filters `type:article`
 * server-side (cheaper — no point fetching what gets thrown away), but the
 * check is repeated here as the actual guarantee, not just an optimisation.
 */
import { DISCOVERY_CONFIG } from "./config";

export interface VelocityRankable {
  id: string;
  cited_by_count?: number;
  publication_date?: string;
  type?: string;
}

export function rankByVelocity<T extends VelocityRankable>(
  papers: T[],
  now: Date,
  opts: { minCitations?: number; topN?: number } = {},
): T[] {
  const minCitations = opts.minCitations ?? DISCOVERY_CONFIG.trendingPapersMinCitations;
  const topN = opts.topN ?? DISCOVERY_CONFIG.trendingPapersTopN;

  const scored = papers
    .filter(
      (p) =>
        (p.cited_by_count ?? 0) >= minCitations &&
        Boolean(p.publication_date) &&
        (p.type == null || p.type === "article"),
    )
    .map((p) => {
      const ageDays = Math.max(
        1,
        (now.getTime() - new Date(p.publication_date!).getTime()) / 86400000,
      );
      return { p, velocity: (p.cited_by_count ?? 0) / ageDays };
    });

  scored.sort((a, b) => b.velocity - a.velocity);
  return scored.slice(0, topN).map((s) => s.p);
}
