# 0010. The bright line: LLM/embedding work is Python, everything else is Go

**Date:** 2026-09-04
**Status:** Accepted — supersedes the "delete `services/backend`" draft

## Context

The migration roadmap (`docs/plans/2026-09-03-python-llm-only-phase0.md`,
`decisions/0002`, `0008`) has been moving non-LLM surface off Python route by
route, each move argued on its own. An intermediate draft proposed finishing the
job by **deleting `services/backend` entirely** and folding the last routes into
Go or a managed service.

That draft is wrong. The LLM-generation endpoints (agent chat, document upload
analysis, daily conjecture, roadmap, discovery predict/nexus-chat, the author
LLM tools, feed generation, grants/journal advisors, the quest generator) and
the embedding service are real Python code with no Go equivalent. `services/backend`
is not dead weight to delete — it is the **LLM service**, and it keeps that job.

What was missing was a single rule a future contributor can apply without
re-litigating each route.

## Decision

### The rule

> A route stays in `services/backend` (Python) **if and only if its core work is
> calling an LLM or computing an embedding.** Everything else is served by the Go
> gateway (`services/backend-go`).

"Core work" means the request cannot be answered without the model call — not
"happens to enrich one field with an LLM if the LLM is up." A route whose
Postgres/OpenAlex/CRUD work is the substance, with an optional LLM garnish, moves
to Go and calls back to Python for the garnish (see the `/author_metrics` split
below).

### Current split

**Python keeps (core work is an LLM call or an embedding):**

| Route / worker | Why it is Python |
|---|---|
| `POST /agent/chat` | LLM conversation |
| `POST /agent/upload_document` | LLM document analysis |
| `GET /daily_conjecture` | LLM generation |
| `GET /assistant_professor_roadmap` | LLM generation |
| `POST /discovery/predict` | LLM prediction |
| `POST /discovery/nexus-chat` | LLM chat |
| `POST /chat_with_author` | LLM conversation |
| `POST /summarize_work` | LLM summary |
| `POST /analyze_paper` | LLM analysis |
| `POST /presentation_outline` | LLM generation |
| `GET /daily_feed` | LLM feed generation |
| `GET /semantic_trending` | embedding similarity |
| `GET /match_grants` | embedding-grounded scoring + LLM rationale |
| `GET /journal_advisor` | embedding similarity + LLM rationale |
| `GET /collaborator_synergy` | LLM (`SYNERGY_COUNSELOR` prompt) |
| `GET /industry_opportunities` | LLM generation |
| `GET /industry_academic_tieups` | LLM + user-memory profile |
| quest LLM init | LLM quest generation |
| `embedding_service` | model inference |
| the teleport worker (`researcher_worker.py`) | orchestrates LLM + embedding enrichment |
| `GET /ai_status` | reports LLM key/health — trivially an LLM-status route |
| new `POST /internal/author_metrics_enrich` | the LLM half of the `/author_metrics` split (below) |

**Go has everything else** — auth, users, user_memory, CoLab/recommendations
peers, feed persistence + non-LLM CRUD, leaderboard, quest fast-path reads,
`/author_suggestions`, `/resolve_email`, `/orbit_metrics`, `/citation_heatmap`,
`/network_collaborators`, and now `/author_metrics`, `GET /` (API-router root),
`GET /status`.

### Two owner-decided splits

1. **`GET /author_metrics` → Go, with a Python LLM callback.**
   Go serves the endpoint: it does the OpenAlex works fetch, owns the
   "not enough recent papers" 422, builds the analysis context, caches the
   result (`cache_entries`, name `author_metrics`, 2 h — same as the Python
   `author_metrics_cache`), and returns the byte-identical
   `AuthorMetricsResponse` shape (`overall_score`, `topic_toughness`,
   `velocity`, `skills[]`, `tools[]`, `analysis`).
   The **LLM enrichment** — parsing that context into the scored bundle — is
   the one part that is genuinely model work, so Go calls a new minimal Python
   internal route `POST /api/v1/internal/author_metrics_enrich`
   (`{ "context": "<title/concepts digest>" }` → the scored bundle). On any
   enrichment failure (Python down, LLM down, timeout) Go **degrades to the
   empty bundle** (`overall_score: 0`, empty strings/arrays) with `200`, which
   the Android `AuthorMetrics` model tolerates via its field defaults. This is
   a deliberate parity change from the old Python behaviour (LLM failure →
   `503`); `/author_metrics` is now a best-effort enrichment read, not a
   hard-fail one.
   `compute_author_metrics(author_id, ...)` stays in `metrics_service.py`
   unchanged — the teleport worker still calls it directly — refactored only to
   share the LLM-parse step with the new internal route.
   This revises `decisions/0009`'s conclusion that `/author_metrics` "stays in
   Python permanently": the routing front door moves; the model call does not.

2. **`GET /search_author` + `GET /refresh_author` → Go lookup, Python teleport
   worker.** Go serves the author lookup/read; where enrichment is needed it
   enqueues the Python teleport worker (`researcher_worker.py`), which owns the
   LLM + embedding enrichment. **This split is stream S2's work** — referenced
   here for completeness, not implemented in this change.

## Alternatives considered

- **Delete `services/backend`.** Rejected: the LLM-generation endpoints and the
  embedding service have no Go equivalent and are not being rewritten. The
  Python service is the LLM service, not scaffolding.
- **Keep `/author_metrics` wholly in Python** (per `0009`). Rejected by the
  owner: the endpoint's OpenAlex fetch + caching + response assembly is gateway
  work; only the context→bundle parse is model work. Splitting it keeps the
  bright line clean and lets Go own the cache and the 422.
- **Drop the LLM-parsed fields from `/author_metrics`** instead of the callback.
  Rejected: the Android metrics screen renders `topic_toughness`, `velocity`,
  `skills`, `tools`, `analysis`, `overall_score` — every field is client-visible.

## Consequences

- **Easier:** a new route's home is a one-line test — "is the model call the
  substance?" No more per-route roadmap debate.
- **Easier:** Go owns the `/author_metrics` cache and input validation; the
  Python side shrinks to a single stateless LLM function behind an internal
  route.
- **Harder:** `/author_metrics` now has a cross-service hop on a cache miss
  (Go → Python → Groq). Mitigated by the 2 h cache and the degrade-to-empty
  fallback.
- **Behaviour change:** `/author_metrics` no longer returns `503` when the LLM
  is down — it returns an empty bundle with `200`. Documented on the route and
  in the PR.
- **Unresolved:** the internal route `POST /api/v1/internal/author_metrics_enrich`
  is unauthenticated on the public Python URL, same exposure class as the old
  public `GET /author_metrics` it replaces. If the Python service is ever locked
  to gateway-only ingress, this route rides that change; no separate work.
- `decisions/0009` is annotated: its `/author_metrics` row is revised here.
