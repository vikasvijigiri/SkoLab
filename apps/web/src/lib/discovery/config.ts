/**
 * Discovery fit-first collaborator finder — every tunable in one place.
 *
 * Numbers here are the ONLY place a threshold or weight is written; `signals.ts`
 * and `fit.ts` read them, never a literal. Each is `process.env`-overridable for
 * live tuning without a redeploy (mirrors the Go similarity engine's `SIM_W_*`,
 * `decisions/0011`). See `decisions/0014`.
 */

function readEnv(key: string, def: number): number {
  const raw = process.env[key];
  if (raw == null || raw.trim() === "") return def;
  const n = Number(raw);
  return Number.isFinite(n) ? n : def;
}

export const DISCOVERY_CONFIG = {
  /** Published within this many years → "active". */
  activeWithinYears: readEnv("DISCOVERY_ACTIVE_WITHIN_YEARS", 2),
  /** Last publication within this many years (but not "active") → "winding down"; beyond → "dormant". */
  windingDownYears: readEnv("DISCOVERY_WINDING_DOWN_YEARS", 5),
  /** |normalised citation slope| above this flips momentum off "steady". */
  momentumEpsilon: readEnv("DISCOVERY_MOMENTUM_EPSILON", 0.15),
  /** Visible years-active at or below this → "emerging". */
  emergingMaxYears: readEnv("DISCOVERY_EMERGING_MAX_YEARS", 8),
  /** Visible years-active at or above this → "senior". */
  seniorMinYears: readEnv("DISCOVERY_SENIOR_MIN_YEARS", 16),
  /** OpenAlex `/authors` per-page for the researcher grid. */
  pageSize: readEnv("DISCOVERY_PAGE_SIZE", 36),
  /** Max ORCIDs / ids sent to a single enrichment (deaths, collab-flags) call. */
  enrichPageSize: readEnv("DISCOVERY_ENRICH_PAGE_SIZE", 24),
  /** Fit blend weights — sum need not be 1; the score is min-max scaled to 0–100. */
  fit: {
    wConcept: readEnv("DISCOVERY_FIT_W_CONCEPT", 0.55),
    wInstitution: readEnv("DISCOVERY_FIT_W_INSTITUTION", 0.15),
    wCollab: readEnv("DISCOVERY_FIT_W_COLLAB", 0.15),
    wField: readEnv("DISCOVERY_FIT_W_FIELD", 0.15),
  },
  /** Trending topics: length (days) of the recent window and the equal-length
   *  prior window it's compared against. OpenAlex's `/topics` entity has no
   *  growth field (verified live) — trend is always this-window vs last-window,
   *  never a cumulative count. See `decisions/0015`. */
  trendingWindowDays: readEnv("DISCOVERY_TRENDING_WINDOW_DAYS", 90),
  /** A topic below this many works in the PRIOR window is dropped rather than
   *  ranked — 1→4 works reads as "300% growth" but is noise, not a trend. */
  trendingMinPriorWorks: readEnv("DISCOVERY_TRENDING_MIN_PRIOR_WORKS", 15),
  /** How many trending topics to show. */
  trendingTopN: readEnv("DISCOVERY_TRENDING_TOP_N", 8),
} as const;

/** Topical-focus filter stops — a 4-stop segmented control, not a range input
 *  (click-only UX rule, `decisions/0014`). */
export const TOPICAL_FOCUS_STOPS = [0, 0.25, 0.5, 0.75] as const;

export const DISCOVERY_SORTS = [
  { key: "fit", label: "Best fit" },
  { key: "momentum", label: "Momentum" },
  { key: "standing", label: "Standing" },
  { key: "recent", label: "Recent" },
] as const;

export const CAREER_STAGES = [
  { key: "emerging", label: "Emerging" },
  { key: "established", label: "Established" },
  { key: "senior", label: "Senior" },
] as const;

export const ACTIVITY_STATES = [
  { key: "active", label: "Active" },
  { key: "winding_down", label: "Winding down" },
  { key: "dormant", label: "Dormant" },
] as const;

export const MOMENTUM_STATES = [
  { key: "rising", label: "Rising" },
  { key: "steady", label: "Steady" },
  { key: "cooling", label: "Cooling" },
] as const;

/** Institution-type labels for the facet chips — keys are OpenAlex
 *  `last_known_institutions.type` values; only types present in the current
 *  result set are shown (`DiscoveryFilters` builds the list from `facets`). */
export const INSTITUTION_TYPE_LABELS: Record<string, string> = {
  education: "University",
  healthcare: "Hospital / clinic",
  company: "Company",
  archive: "Archive",
  nonprofit: "Non-profit",
  government: "Government",
  facility: "Facility",
  funder: "Funder",
  other: "Other",
};
