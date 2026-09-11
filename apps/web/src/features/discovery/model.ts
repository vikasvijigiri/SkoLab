import type { OpenAlexTaxon } from "@/lib/types";

export type DiscoveryMode = "researchers" | "papers" | "topics";

export const MOMENTUM_RANK = { rising: 0, steady: 1, cooling: 2 } as const;

/** Word tokens used for forgiving profile-focus to OpenAlex taxonomy matching. */
const tokens = (value: string): string[] =>
  value.toLowerCase().replace(/[^a-z0-9 ]/g, " ").split(/\s+/).filter(Boolean);

/** Return the strongest taxonomy match above the intentionally weak floor. */
export function bestTaxonMatch(query: string, taxa: OpenAlexTaxon[]): OpenAlexTaxon | null {
  const queryTokens = new Set(tokens(query));
  if (queryTokens.size === 0) return null;

  let best: OpenAlexTaxon | null = null;
  let bestScore = 0;
  for (const taxon of taxa) {
    const words = tokens(taxon.display_name);
    const hits = words.filter((word) => queryTokens.has(word)).length;
    const score = hits / Math.max(words.length, 1);
    if (hits > 0 && score > bestScore) {
      best = taxon;
      bestScore = score;
    }
  }
  return bestScore >= 0.3 ? best : null;
}
