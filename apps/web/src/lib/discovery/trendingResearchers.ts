/**
 * Trending researchers — promotes the momentum signal that already existed
 * inside the fit-grid's `momentum` sort into its own short highlight list,
 * per decisions/0015's "trending persons" follow-up. Pure: operates on the
 * researcher list the fit-grid already fetched, so it costs no extra
 * network call. Only "rising" researchers qualify — the same "not trending
 * unless it's actually growing" discipline `trendingTopics.ts` applies to
 * topics.
 */
import { DISCOVERY_CONFIG } from "./config";
import type { ResearcherResult } from "@/lib/types";

export function topRisingResearchers(
  researchers: ResearcherResult[],
  topN: number = DISCOVERY_CONFIG.trendingResearchersTopN,
): ResearcherResult[] {
  return researchers
    .filter((r) => r.signals.momentum === "rising")
    .slice()
    .sort((a, b) => b.signals.momentumScore - a.signals.momentumScore)
    .slice(0, topN);
}
