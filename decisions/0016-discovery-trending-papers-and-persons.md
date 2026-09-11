# 0016. Discovery — trending papers (velocity) and trending persons

**Date:** 2026-09-11
**Status:** Accepted

## Context

`decisions/0015` shipped trending topics and named two deliberate follow-ups
it did not build: fixing the Papers-tab's raw-citation-count default to a
velocity score, and promoting the researcher fit-grid's `momentum` sort into
its own highlight. This decision covers both.

## Decision

### Trending papers: citations-per-day, personalized

The Papers-tab default view (`app/api/openalex/works/route.ts`, no `q`)
previously fetched "papers from the last two years, `sort=cited_by_count:desc`"
— global, unpersonalized, and ranked by a raw cumulative count. It now:

- Scopes to the viewer's resolved field/subfield (`trendSubfield`/
  `trendField` — new, distinct params from the existing `subfield`/`field`/
  `topic` used by the manual taxonomy drilldown, which keeps its own
  all-time "top papers for this node" behavior unchanged) via the same
  `topicsScope` Discovery's page already computes for trending topics.
- Ranks a candidate pool (OpenAlex's own `cited_by_count:desc` as a
  prefilter — never the final order) by **citations per day since
  publication** (`rankByVelocity`, `lib/discovery/trendingPapers.ts`),
  matching the methodology Semantic Scholar discloses for its own
  "Trending Papers" (citations per month).
- Drops a paper below `DISCOVERY_CONFIG.trendingPapersMinCitations`
  (default 3) — a single early citation on a day-old paper divides by a
  near-zero age and reads as "hot" by fluke.

**Live verification surfaced a second data-quality bug**, not anticipated
going in: the raw-count query's top result was a journal-issue **paratext**
entry (`type: "paratext"`) with 561 citations attributed to it four days
after its listed date — front matter, not a paper, with OpenAlex citation
counts apparently landing on the issue rather than an article. Fixed with
`type:article` in the OpenAlex filter (server-side, so it's never fetched)
**and** repeated as a client-side check in `rankByVelocity` itself — the
route's filter is an optimization, the pure function is the actual
guarantee, so a future caller that skips the route-level filter still can't
surface a non-article.

Re-verified live after both fixes (Computer Science / Artificial
Intelligence subfield): 12 results, every one `type: article`, ranked by
velocity — the top result (72 citations, 5.43/day, 13 days old) legitimately
outranks a higher-total-citation paper (179 citations, but 1.92/day, 93 days
old) — the exact reordering the fix exists to produce.

### Trending persons: promoted, not duplicated

`TrendingResearchersStrip` — a short horizontal highlight above the
fit-grid, showing only researchers whose `momentum` is `rising`, ranked by
magnitude. This needed one small signals change: `deriveSignals` (`signals.ts`)
previously computed the OLS slope only to classify it into `rising`/`steady`/
`cooling`; the continuous value is now also exposed as
`ResearcherSignals.momentumScore`, so a ranked list can order *how much*
someone is rising, not just the bucket. `classifyMomentum` still exists,
now a thin wrapper over the shared slope calculation (`momentumSlope`) —
one computation, two consumers, not two.

No new network call: the strip reads the same researcher list the fit-grid
already fetched and computed `fit`/`signals` for. Renders nothing when
nobody in the current scope is rising, rather than padding with a
`steady` researcher to fill the row.

## Alternatives considered

- **A `trendingResearchersQuery` server route mirroring trending topics.**
  Rejected — the data (`momentum`, already scoped to the viewer's field) is
  already client-side from the fit-grid's own fetch; a second network call
  would be pure duplication.
- **A separate `momentumSlope` field left unexported, computing it twice**
  (once for the category, once for a hypothetical ranked list). Rejected in
  favor of the refactor — one calculation, `classifyMomentum` and
  `deriveSignals` both read from it.
- **Filtering paratext only client-side, not in the OpenAlex query.**
  Rejected — fetching entries that get thrown away anyway wastes part of
  the `trendingPapersPoolSize` candidate budget for no benefit; both layers
  stay, for different reasons (cost vs. guarantee).

## Consequences

- **Better:** Discovery's Papers tab no longer needs a click into a specific
  topic to see something personalized and honestly ranked — the same
  standard already applied to topics and researchers.
- **New, general finding:** OpenAlex's `cited_by_count` can attribute
  citations to non-article entries (paratext observed; other `type` values
  are plausible). Any future feature sorting `/works` by citation count
  should filter `type:article` unless it has a specific reason not to.
- **Cost:** the trending-papers query is unchanged in call count (still one
  `/works` request); velocity ranking is a client-side re-sort of the
  already-fetched candidate pool, not an extra round trip.
- **Watch:** `trendingPapersMinCitations` (3) and `trendingPapersPoolSize`
  (40) are starting points, not validated thresholds — the same caveat
  `decisions/0015` already recorded for its own volume floor.
