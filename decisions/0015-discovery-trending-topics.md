# 0015. Discovery — trending topics (growth, not raw count)

**Date:** 2026-09-11
**Status:** Accepted

## Context

The user asked whether, instead of investing further in Horizon AI's manually-typed
LLM prediction tool, SkoLab could build a personalized "trending topics / papers /
persons" surface from data it already has (the viewer's resolved field, via the
same taxonomy work `decisions/0014` already did for the researcher grid).

A competitive review of the tools the user's own research named
(Semantic Scholar, ResearchRabbit/Litmaps, Scite, Elicit, OSF) found:

- Semantic Scholar's "Trending Papers" is explicitly citations-per-month, not a
  cumulative count, and requires manual library curation to personalize.
- ResearchRabbit/Litmaps map a chosen paper's citation network over time; neither
  computes an automatic, field-scoped topic-growth signal.
- Nobody in the set does personalized topic-level trending with zero curation.

Live verification against OpenAlex during design confirmed two things worth
recording, because both contradict the naive approach:

1. `/topics/{id}` (the topic entity itself) exposes only cumulative
   `works_count`/`cited_by_count` — **no growth field exists anywhere on it.**
   Any "trending" signal has to be derived, not read.
2. A raw "papers from the last N days, sorted by citations" query (the pattern
   Discovery's existing Papers-tab default already used) surfaced a paper
   published five weeks prior with 135 citations — almost certainly an
   ingestion artifact, not real velocity. A raw-count sort is exactly the
   Einstein-first failure mode `decisions/0014` already rejected for
   researchers, reproduced at the paper level.

## Decision

Ship the first "trending" slice as **trending topics inside Discovery** — a
third mode (`researchers` / `papers` / `topics`) alongside the existing two,
not a Horizon AI replacement and not a new tab. Horizon AI is untouched.

### How growth is computed

Two `GET /works?filter={scope}.id:{id},{date-range}&group_by=topics.id` calls
— one for the last `DISCOVERY_CONFIG.trendingWindowDays` (default 90), one for
the equal-length prior window — merged client-side (`computeTopicGrowth`,
`lib/discovery/trendingTopics.ts`, pure and unit-tested):

    growth = (recentCount - priorCount) / priorCount

- A topic below `DISCOVERY_CONFIG.trendingMinPriorWorks` (default 15) prior-window
  works is dropped rather than ranked — 1 → 4 works reads as "300% growth" but
  is noise from a tiny base, the same discipline `signals.ts` already applies
  to author momentum.
- A topic with `growth <= 0` is dropped — a shrinking topic isn't "trending."
- Both OpenAlex calls are `$0.0001` each and cacheable 12h server-side
  (`next.revalidate`) — topic growth doesn't move faster than that.
- Every card shows the two real counts behind the percentage
  (`TrendingTopicCard`), not just the number — the same "show your work"
  principle as the researcher cards' raw h-index-on-hover.

### Scope resolution

Reuses `decisions/0014`'s viewer-subfield resolution and the existing
`gridSubfield` (manual drilldown wins, else the viewer's own subfield),
falling back to a bare field-level scope if only a field is picked. No new
resolution logic.

### Selecting a trending topic

Sets the existing `topic` drilldown state and switches to `papers` mode — the
same `nodeWorks` query the manual breadcrumb drilldown already uses shows the
papers actually driving the trend. If the scope came from the viewer's
auto-resolved field/subfield rather than a manual pick, that gets promoted
into the drilldown state too, so the breadcrumb stays coherent.

## Alternatives considered

- **Trending papers first** (fix the existing raw-count sort to a citation-velocity
  score). Rejected as the first slice per the user's own choice — topics is the
  clearer competitive gap and the cheaper build; the papers-tab fix is a
  follow-up, not blocked by this work (`counts_by_year` is already selected on
  every work, so the fix is a sort-function change, not a new data path).
- **Trending persons as a standalone list.** Rejected as the first slice —
  the momentum signal already exists inside the researcher fit-grid's `momentum`
  sort (`decisions/0014`); surfacing it as its own view is small and deferred,
  not because it's infeasible.
- **Replacing Horizon AI's tab.** Rejected — the user chose to fold trending
  into Discovery and leave Horizon as-is, at least for this slice.
- **A single `group_by=topics.id,publication_year` call** instead of two
  windowed calls. Not available — OpenAlex's `group_by` does not support a
  two-dimensional bucket in one request (verified live); two cheap calls is
  the actual mechanism, not a compromise.

## Consequences

- **Better:** a genuinely differentiated, zero-curation, zero-typing surface
  no reviewed competitor offers in this shape — topic growth without a
  library to curate first.
- **Honest by construction:** the volume floor and the "show both counts"
  requirement make the percentage checkable rather than asserted; a topic
  that can't clear the floor doesn't appear rather than showing an inflated
  ratio.
- **New surface:** one route (`app/api/openalex/trending-topics`), one pure
  lib module + config keys, one card component — no Go/Python change, same
  layer Discovery already lives in.
- **Watch:** the growth ratio is unstable near the volume floor (a topic just
  above 15 prior works can still swing widely run to run) — the floor default
  is a starting point, not a validated threshold; revisit if real usage shows
  noisy entries.
- **Cost:** two `/works` calls (`$0.0002` total) per distinct scope per 12h
  cache window — negligible against the existing per-view budget.
