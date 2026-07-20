# 0006. Decouple ranking score (pool-relative) from displayed match % (absolute)

**Date:** 2026-07-14
**Status:** Accepted

## Context

Raw `bge-small` cosine similarity is anisotropic: even topically unrelated
text pairs commonly score ~0.4-0.6, not ~0. Left uncorrected, every
candidate's displayed match % clustered near the ceiling (97/97/97)
regardless of actual relevance — a real bug, caught by inspection.

The fix at the time was to mean-center similarity against the candidate
pool's own average before scoring, which restored real discrimination
between good and mediocre matches *within a given request*. But centering
has a side effect: it guarantees roughly half of any candidate pool reads as
"below average" on every single request, by construction — regardless of
whether the whole pool is genuinely excellent or genuinely mediocre. This
surfaced as a second, related complaint: a steep, seemingly-arbitrary cliff
between rank 1 (96%) and ranks 2-3 (80%, 66%), which doesn't reflect reality
when hundreds of papers in the literature could plausibly be strong matches.

## Decision

Compute similarity twice per candidate: once mean-centered (for **ranking**
— `is_relevant` gating, `get_sort_key`, MMR diversification, all of which
correctly want "best relative to what we found this round"), and once raw/
uncentered (for the **displayed match %**, calibrated against a fixed
absolute scale instead of the pool's own average).

## Alternatives considered

- **Centered score for both ranking and display.** What was shipped first;
  rejected once the artificial-cliff behavior was identified as a symptom of
  centering being the wrong tool for an absolute, user-facing percentage.
- **Uncentered score for both.** Would fix the display issue but bring back
  the original bug (everything clustering near the ceiling), since ranking
  needs the discrimination centering provides.
- **A single blended formula that's "close enough" for both purposes.**
  Rejected — ranking and display have genuinely different, incompatible
  requirements (relative vs. absolute), and trying to serve both with one
  number is exactly how the tension arose in the first place.

## Consequences

Two similarity computations per candidate instead of one (embeddings are
still computed once; centering is a cheap vector operation on top). The
displayed percentage can now legitimately show several papers all in the
high-80s/90s when the literature genuinely has that many strong matches,
instead of manufacturing a gap between rank 1 and rank 2 that isn't real.
