/**
 * Small, pure display helpers shared by every surface that shows a
 * researcher's standing/velocity as plain text: `ResearcherCard`,
 * `CompareModal`, and the author page's Highlights layer. Kept in one place
 * so the honesty convention (never invent a number the data doesn't
 * support) is enforced once, not re-derived per call site.
 */

/**
 * "top N%" when a field h-index percentile is known, else a raw H-index
 * fallback — the same convention `ResearcherCard` has always used: no
 * percentile is ever invented when no distribution was computed for this
 * view.
 */
export function fieldStandingLabel(hIndex: number, standingPercentile: number | null): string {
  return standingPercentile != null ? `top ${Math.max(1, 100 - standingPercentile)}%` : `H-${hIndex}`;
}

/**
 * Most-recent-year value over the mean of the prior years in the same
 * citations-per-year series `MomentumSparkline` already draws — a real,
 * explainable ratio (not a fabricated confidence score). `null` when there
 * isn't enough history to compare against (fewer than 2 points, or a
 * zero-mean prior window would make the ratio meaningless).
 */
export function citationVelocity(series: number[]): number | null {
  if (series.length < 2) return null;
  const last = series[series.length - 1] ?? 0;
  const prior = series.slice(0, -1);
  const priorMean = prior.reduce((a, b) => a + b, 0) / prior.length;
  if (priorMean <= 0) return null;
  return Math.round((last / priorMean) * 10) / 10;
}
