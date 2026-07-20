# 0007. Retire the dormant unified-recommendations endpoint

**Date:** 2026-07-19
**Status:** Accepted

## Context

`app/domains/recommendation/` contained a fully-built, fully-tested "unified
hybrid recommendation" system — `service.py`'s `get_unified_recommendations`
(papers/grants/collaborators in one response) backed by `engine.py`'s 8
techniques (time-weighted profiling, concept expansion, TF-IDF cosine
similarity, domain-scoped citation PageRank, serendipity injection, MMR
diversification, team composition optimizer, Bayesian grant probability),
exposed as `GET /api/v1/recommendations`.

Confirmed by direct investigation: neither `apps/web` nor `apps/android-app`
calls this endpoint. Both clients only call its sibling sub-routes —
`/peers`, `/peers/invite`, `/peers/check-registered` — which live in the
same `service.py`/`router.py` files but serve a different purpose (CoLab
project invite/collaborator autocomplete) and are genuinely live.

Separately, this session's recommendation-engine work
(`decisions/0003-self-hosted-embeddings.md`) replaced the *active* daily-
feed path's TF-IDF similarity with real sentence-transformer embeddings,
specifically because TF-IDF is structurally blind to paraphrase/synonymy.
That means the dormant endpoint's similarity scoring is now strictly worse
than the live path's — reviving it as-is would be reintroducing a quality
regression that was deliberately fixed elsewhere in the codebase.

Import trace confirmed `engine.py` is not fully dead: `pipeline_services.py`
already imports and uses `cosine_similarity` and `mmr_diversify` from it
directly. The other 6 techniques and their profile-building helpers
(`build_time_weighted_profile`, `expand_concepts`) were used only by the
dormant path.

## Decision

Retire the unused parts only:
- `engine.py`: removed `build_time_weighted_profile`, `expand_concepts`,
  `build_tfidf_vector`, `domain_pagerank`, `inject_serendipity`,
  `team_composition_optimizer`, `bayesian_grant_probability`,
  `compute_novelty_score`, and their orphaned constants. Kept
  `cosine_similarity`, `mmr_diversify`, `MMR_LAMBDA` — still actively used.
- `service.py`: removed `get_unified_recommendations` and its three private
  builders. Kept `get_peer_recommendations`, `log_peer_invite`,
  `check_registered_peers` — unchanged, still live.
- `router.py`: removed the unified `GET ""` route. Kept `/peers`,
  `/peers/invite`, `/peers/check-registered`.
- `schemas.py`: removed `PaperRecommendation`, `GrantRecommendation`,
  `CollaboratorRecommendation`, `RecommendationResponse`. Kept
  `PeerRecommendation` and the peer-invite/check-registered schemas.
- `tests/test_recommendation_system.py`: removed tests for everything
  above; kept the 4 tests covering `cosine_similarity`/`mmr_diversify`
  (rewritten to use hand-built vectors instead of the removed
  `build_tfidf_vector`, since those two functions are generic vector
  utilities, not TF-IDF-specific).

## Alternatives considered

- **Merge select techniques (PageRank citation-influence, serendipity
  injection, Bayesian grant probability) into the active
  `pipeline_services.py` path first, then retire the wrapper.** Rejected
  for this pass — genuinely open-ended scope (each technique would need its
  own integration design against the embeddings-based flow), effectively a
  new feature-design session rather than a cleanup. Nothing prevents
  revisiting this later; the removed code is recoverable from git history
  if any specific technique turns out to be worth porting.
- **Point the frontend at the unified endpoint instead of retiring it.**
  Rejected: would be a quality regression today (TF-IDF instead of real
  embeddings, no dismiss/feedback loop, no similar-researchers-via-
  authorship fix), not a viable "quick switch."

## Consequences

Two fewer files' worth of untested-in-production, unreachable code paths
to reason about. The live peer/invite/check-registered functionality is
unchanged — verified via live requests after the change. If PageRank-style
citation influence or Bayesian grant probability are wanted in the active
path later, they can be re-introduced deliberately (with their own
integration design against the current embeddings-based flow) rather than
inherited wholesale from code that was never actually serving real traffic.
