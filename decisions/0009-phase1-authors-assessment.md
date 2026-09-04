# 0009. Phase 1 (`authors.py` → Go) — assessment: nothing moves yet

**Date:** 2026-09-04
**Status:** Accepted — the `/author_metrics` conclusion (row below, and the
"stay in Python permanently" list) is revised by
[0010](0010-llm-python-rest-go-boundary.md): the endpoint's routing front door
moves to Go with a Python LLM callback. The rest of this assessment stands.

## Context

`decisions/0008` and `docs/plans/2026-09-03-python-llm-only-phase0.md` set the
"Python = LLM-only" migration. The roadmap's **Phase 1** row reads:

> `authors.py` non-LLM surface (metrics, heatmap, synergy, network, search, DB
> parts of grants / journal-advisor) → Go `internal/author`.

Phase 1 was dispatched with a hard conservative bar: move a route **only** if it
is unambiguously pure Postgres / OpenAlex proxy CRUD — no LLM, no `embed_*`, no
graph/numpy, and not a large Go port — "if in doubt, don't move it." There is no
Go toolchain on the dev box and CI is currently blocked, so any moved Go code
would ship unverified.

## Decision

**Move no routes in this pass.** A full read of every handler in `authors.py`
and every service it reaches (`compute_author_metrics`, the `pipeline/*` mixins,
`app/core/cache.py`, `app/db/pg_cache.py`) found that none of the 8 live routes
clears the bar:

| Route | Blocker |
|---|---|
| `/refresh_author` | Structurally Python — busts the Python-process cache and enqueues the LLM teleport `BackgroundTask`. |
| `/search_author` | Firestore fallback tier + Python `profile_cache` + `fetch_similar_authors` OpenAlex fan-out + `is_llm_working()` gating + background teleport. 40-field response. |
| `/author_metrics` | **LLM call** — `ScrapingService.parse_content_to_json` → `LLMService.query`. Does **not** read `researcher_metrics`; the roadmap's "metrics" premise is wrong. |
| `/network_collaborators` | Depth-1/depth-2 OpenAlex fan-out + 3-table Postgres read/write-back. Large port (no `networkx` today — the roadmap's "networkx → gonum" note is stale). |
| `/collaborator_synergy` | **LLM call** (`SYNERGY_COUNSELOR_PROMPT_TEMPLATE`). |
| `/citation_heatmap` | No LLM/embeds, but reads/writes a Firestore cache tier + the Python `PgBackedCache` `cache_entries` table (no Go reader exists) + needs `counts_by_year` + `random` fudge. |
| `/match_grants` | Embedding-grounded scoring + LLM + Firestore. Also a Phase 2 file. |
| `/journal_advisor` | Embedding similarity + LLM rationale + Firestore. Also a Phase 2 file. |

The genuinely pure author endpoints (`/author_suggestions`, `/resolve_email`,
`/orbit_metrics`) were already migrated in an earlier PR.

## Re-scope

The Phase 1 roadmap row is split; each sub-item is taken on its own with a
working Go build, tracked as `[NEEDS DECISION]` in
`docs/plans/2026-09-04-phase1-authors-to-go.md`:

- **1c — `/citation_heatmap`**: the only LLM-free candidate. Needs a decision on
  the Firestore tier and a `cache_entries` Go client (or a separate cache) first.
- **1d — `/network_collaborators`**: dedicated phase; OpenAlex fan-out + pgx.
- **`/search_author` partial**: a Go PG fast-path in front of a Python proxy
  fallback — only if the response-parity and `fetch_similar_authors` cost is
  judged worth a split handler.
- **`/author_metrics`, `/collaborator_synergy`, `/match_grants`,
  `/journal_advisor`**: stay in Python permanently under the LLM-only rule.

## Alternatives considered

- **Hand-port `/citation_heatmap` now, unverified.** Rejected: it carries a
  Firestore tier and a `cache_entries` parity requirement the Go gateway has no
  precedent for; an unverifiable port of that is the opposite of the
  "obviously-correct" bar.
- **Port the `researcher_metrics` read out of `/search_author`.** Rejected for
  this pass: the DB-hit path is entangled with a 40-field response, an OpenAlex
  similar-authors fan-out, LLM-status flags, and a Python-only background task.

## Consequences

- `authors.py`, `main.go`, `app/api/v1/router.py`, and the `pipeline/*` mixins
  are unchanged. No behaviour change, no risk.
- The "Python = LLM-only" goal for the author surface is deferred, not
  abandoned — the follow-up items above are concrete and independently shippable
  once a Go build is available.
- `docs/plans/2026-09-03-python-llm-only-phase0.md` Phase 1 row should be updated
  to drop "metrics" (it is LLM) and to reference this assessment.
