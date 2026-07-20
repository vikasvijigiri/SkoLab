# 0005. Similar-researchers channel via authorship, not author-name search

**Date:** 2026-07-14
**Status:** Accepted

## Context

While broadening the daily-feed candidate pool, a second candidate-generation
channel was added: pull recent papers from researchers similar to the
logged-in user, so the feed isn't limited to exact keyword-search hits. The
first implementation called OpenAlex's `GET /authors?search=` with the
user's topic/concept strings (e.g. `"CRISPR and Genetic Engineering"`,
`"Advanced Condensed Matter Physics"`) as the query.

Tested directly against the live OpenAlex API: this consistently returned
zero results, or occasional garbage matches (e.g. a journal title
misindexed as an author name). `/authors?search=` does fuzzy matching against
author **display names** — it is not a topic/concept discovery endpoint, and
a concept phrase doesn't look like a person's name.

## Decision

Derive similar-researcher IDs from the **authorships of papers already
returned by the topic/keyword search** (the same candidates already fetched
for the main pool), rather than an independent author-name search call. Take
the unique author IDs off the first ~20 already-topic-matched candidates,
excluding the logged-in user, and fetch a few of each one's other recent
papers.

## Alternatives considered

- **Resolve a concept name to an OpenAlex concept ID first, then filter
  `/authors` by `x_concepts.id`.** Would work, but costs an extra
  concept-name-to-ID resolution round-trip per search term, for a result
  that's less directly grounded than "who actually wrote the papers my
  search already found."
- **Keep the name-search call, accept it sometimes returns nothing.**
  Rejected once confirmed via direct testing that it wasn't a rate-limit or
  edge-case issue — it was structurally the wrong endpoint for the job, not
  worth keeping degraded behavior for.

## Consequences

Zero extra network round-trips for author resolution (the source works are
already in hand from the main gather) and every derived "similar researcher"
is guaranteed to have already published something topically matched — a
stronger guarantee than a name-search-based approach would have given even
if it had worked. The one tradeoff: this channel is empty whenever the main
topic search itself returns nothing (no bootstrapping from author identity
alone), which is an acceptable failure mode since the main search already has
its own fallback broadening logic.
