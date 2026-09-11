/**
 * Pure derivation of the collaboration signals shown on a researcher card, from
 * one widened OpenAlex `/authors` row. No network, no `Date` read (caller passes
 * `now`), no literal thresholds — every cut-off comes from `DISCOVERY_CONFIG`.
 */

import { DISCOVERY_CONFIG } from "./config";
import type {
  ActivityState,
  CareerStage,
  MomentumState,
  OpenAlexAuthorRaw,
  ResearcherSignals,
} from "@/lib/types";

export interface SignalContext {
  /** OpenAlex subfield id the grid is scoped to — picks which topic_share counts as "focus". */
  scopedSubfieldId?: string;
  /** OpenAlex field id — fallback scope when no subfield is selected. */
  scopedFieldId?: string;
  /** Maps an h-index to its 0–100 percentile within the field distribution. */
  fieldHIndexPercentile?: (h: number) => number;
  /** Current year; defaults to the real one. Injected so the fn stays pure/testable. */
  now?: number;
}

const clamp01 = (n: number): number => (n < 0 ? 0 : n > 1 ? 1 : n);

/** OLS slope of `ys` over evenly-spaced x (0..n-1), normalised by mean(ys).
 *  `0` when there are fewer than 3 points or the mean is 0 — not enough
 *  signal to say anything, not "no momentum". */
function momentumSlope(ys: number[]): number {
  if (ys.length < 3) return 0;
  const n = ys.length;
  const xMean = (n - 1) / 2;
  const yMean = ys.reduce((a, b) => a + b, 0) / n;
  if (yMean === 0) return 0;
  let num = 0;
  let den = 0;
  for (let i = 0; i < n; i++) {
    num += (i - xMean) * ((ys[i] ?? 0) - yMean);
    den += (i - xMean) ** 2;
  }
  if (den === 0) return 0;
  return num / den / yMean;
}

function classifyMomentum(slope: number, epsilon: number): MomentumState {
  if (slope > epsilon) return "rising";
  if (slope < -epsilon) return "cooling";
  return "steady";
}

export function deriveSignals(a: OpenAlexAuthorRaw, ctx: SignalContext = {}): ResearcherSignals {
  const now = ctx.now ?? new Date().getFullYear();

  const counts = [...(a.counts_by_year ?? [])].sort((x, y) => x.year - y.year);
  const activeYears = counts.filter((c) => (c.works_count ?? 0) > 0).map((c) => c.year);
  const affiliationYears = (a.affiliations ?? []).flatMap((f) => f.years ?? []);
  const allYears = [...activeYears, ...affiliationYears];

  // ── activity ──────────────────────────────────────────────────────────────
  const lastActive = activeYears.length ? Math.max(...activeYears) : null;
  const gap = lastActive == null ? Number.POSITIVE_INFINITY : now - lastActive;
  const activity: ActivityState =
    gap <= DISCOVERY_CONFIG.activeWithinYears
      ? "active"
      : gap <= DISCOVERY_CONFIG.windingDownYears
        ? "winding_down"
        : "dormant";

  // ── momentum ──────────────────────────────────────────────────────────────
  const sparkline = counts.map((c) => c.cited_by_count ?? 0);
  const momentumScore = momentumSlope(sparkline);
  const momentum = classifyMomentum(momentumScore, DISCOVERY_CONFIG.momentumEpsilon);

  // ── career stage ──────────────────────────────────────────────────────────
  const firstVisibleYear = allYears.length ? Math.min(...allYears) : now;
  const yearsActiveVisible = Math.max(0, now - firstVisibleYear);
  const careerStage: CareerStage =
    yearsActiveVisible <= DISCOVERY_CONFIG.emergingMaxYears
      ? "emerging"
      : yearsActiveVisible >= DISCOVERY_CONFIG.seniorMinYears
        ? "senior"
        : "established";

  // ── active decades ────────────────────────────────────────────────────────
  const decades = new Set<number>();
  for (const y of allYears) decades.add(Math.floor(y / 10) * 10);
  const activeDecades = [...decades].sort((x, y) => x - y);

  // ── topical focus ─────────────────────────────────────────────────────────
  const topics = a.topics ?? [];
  const scopedTopic = topics.find(
    (t) =>
      (ctx.scopedSubfieldId != null && t.subfield_id === ctx.scopedSubfieldId) ||
      (ctx.scopedFieldId != null && t.field_id === ctx.scopedFieldId),
  );
  const topicalFocus = clamp01(
    scopedTopic?.value ?? (topics.length ? Math.max(...topics.map((t) => t.value ?? 0)) : 0),
  );

  // ── standing ──────────────────────────────────────────────────────────────
  const standingPercentile = ctx.fieldHIndexPercentile
    ? ctx.fieldHIndexPercentile(a.h_index ?? 0)
    : null;

  return {
    activity,
    momentum,
    momentumScore,
    yearsActiveVisible,
    careerStage,
    activeDecades,
    topicalFocus,
    standingPercentile,
    sparkline,
  };
}
