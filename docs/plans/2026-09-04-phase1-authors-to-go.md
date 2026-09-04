# Phase 1 — `authors.py` non-LLM surface → Go gateway

- **Slug:** phase1-authors-to-go
- **Branch:** `feat/phase1-authors-to-go`
- **Base:** `origin/main` @ `53da9fa`
- **Status:** Plan complete. **Implementation outcome: no routes moved** (see
  "Decision" and "Route classification").
- **Author:** implementer (backend), dispatched from the Phase 1 stream.
- **Related:** `decisions/0002`, `decisions/0008`, `decisions/0009`,
  `docs/plans/2026-09-03-python-llm-only-phase0.md` (roadmap, Phase 1 row).

## Goal

Move the author-centric **non-LLM** surface from
`services/backend/app/api/v1/endpoints/authors.py` to the Go gateway
(`services/backend-go/internal/author/`), matching the existing `author.go`
pgx + OpenAlex patterns, under the "Python = LLM-only" migration.

The dispatch brief set a hard conservative bar: **move a route only if it is
unambiguously pure Postgres / OpenAlex proxy CRUD** — no LLM call, no
`embed_*`, no heavy graph / numpy work, and not a large Go port. "If in doubt,
don't move it." There is **no Go toolchain on this machine and CI is blocked**,
so any moved Go code ships unverified — which raises the bar further.

## Method

- Read every handler in `authors.py` and every service function it reaches.
- Followed `compute_author_metrics`, each `pipeline/*` mixin
  (`heatmap`, `synergy`, `network`, `grants`, `journals`, `base`),
  `app/core/cache.py`, and `app/db/pg_cache.py` to their leaves.
- Classified each route MOVE / STAY / NEEDS DECISION against the bar above.

## Route classification

`authors.py` exposes **8** live routes. `/author_suggestions`, `/resolve_email`,
`/orbit_metrics` were already migrated in a prior PR (comments in `authors.py`
and `internal/author/author.go`); they are not in scope here.

| Route | Verdict | Reason |
|---|---|---|
| `GET /refresh_author` | **STAY** | Structurally Python. Its entire job is (1) delete keys from the Python-process `profile_cache` / `suggestions_cache` (`PgBackedCache`, L1 in-memory + L2 `cache_entries`) and (2) enqueue `track_teleport_researcher`, a FastAPI `BackgroundTask` that runs the LLM enrichment worker. A Go copy can do neither. |
| `GET /search_author` | **STAY** | No direct LLM call, but deeply coupled: Python `profile_cache` read/write; a **Firestore** `global_researchers` fallback tier (Go gateway has no Firestore SDK); `fetch_similar_authors` (OpenAlex works-search + N `fetch_author_by_id` + `extract_field_and_expertise` + scoring); `is_llm_working()` gating on `metrics_computed` / `llm_active`; and a conditional background teleport enqueue. Assembles a 40-field `AuthorResponse`. Large, non-obvious port. |
| `GET /author_metrics` | **STAY** | **LLM call.** `compute_author_metrics` (`app/services/platform/metrics_service.py`) fetches 10 works from OpenAlex, builds a prompt context, and calls `ScrapingService.parse_content_to_json` → `LLMService.query` (Groq `json_object`). It does **not** read the `researcher_metrics` table — the dispatch brief's parenthetical ("reads `researcher_metrics`") is incorrect; see `[NEEDS DECISION]` #3. |
| `GET /network_collaborators` | **STAY** | Large port. `NetworkMixin.get_network_collaborators` is a depth-1 / depth-2 co-author traversal: OpenAlex fan-out (works for the author + works for the top-10 direct co-authors + batched `/authors` stats calls), Jaccard scoring, three Postgres tables (`ResearcherConnection`, `ResearcherProfile`, `ResearcherMetrics`) read + write-back with TTLs. No LLM and no `networkx` today (pure dict traversal), but well outside "small, obviously-correct." |
| `GET /collaborator_synergy` | **STAY** | **LLM call.** `SynergyMixin` formats `SYNERGY_COUNSELOR_PROMPT_TEMPLATE` and calls `llm_service.query`; also Firestore-cached. |
| `GET /citation_heatmap` | **STAY** | Closest thing to a clean candidate — **no LLM, no embeds, no numpy/graph** — but not a conservative drop-in: it reads/writes a **Firestore** `citation_heatmaps` cache tier, reads/writes the Python `PgBackedCache` (`pipeline::citation_heatmap_<id>`, `{"v": ...}` envelope) which no Go code touches today, needs `counts_by_year` from the OpenAlex client, and uses a `random.randint(2,6)` fudge for `institutional_reach`. See `[NEEDS DECISION]` #1. |
| `GET /match_grants` | **STAY** | `GrantsMixin` (`pipeline/grants.py`) — scraping + **embedding-grounded** match-scoring + LLM extraction + Firestore. Also this file is **Phase 2's** territory. |
| `GET /journal_advisor` | **STAY** | `JournalsMixin` (`pipeline/journals.py`) — **embedding** similarity (`score_candidates_against_profile`) + `JOURNAL_ADVISOR_RATIONALE_PROMPT_TEMPLATE` LLM rationale + Firestore. Also **Phase 2's** file. |

### MOVE set: empty

No route in `authors.py` clears the conservative bar. Every remaining route
either calls an LLM, calls an embedding service, depends on a Firestore cache
tier the Go gateway does not have, mutates a Python-process cache, or is a
large multi-source port. The genuinely pure endpoints were already migrated.

## Decision

**Ship the plan, move nothing, record why.** This PR contains only:

- this plan,
- `decisions/0009-phase1-authors-assessment.md`,
- a note in `docs/backend-auth-posture.md` (no route changed class).

No Go handlers, no `main.go` change, no `authors.py` deletion, no `pipeline/*`
method removal. A no-op is the correct conservative outcome given the bar and
the absence of Go CI. The roadmap's Phase 1 row is re-scoped into the
`[NEEDS DECISION]` items below (candidates 1c / 1b), to be taken one at a time
with a working Go build.

## `[NEEDS DECISION]`

1. `[NEEDS DECISION: /citation_heatmap — port as Phase 1c?]` It is the only
   LLM-free / embed-free route. Blockers to resolve first: (a) drop the
   Firestore `citation_heatmaps` cache tier on the Go path, or add a Firestore
   client to the gateway? (b) introduce a Go reader/writer for `cache_entries`
   with exact `name::key` + `{"v": ...}` + `expires_at` parity, or give the Go
   path its own cache? (c) confirm the Go OpenAlex `Author` carries
   `counts_by_year`. (d) accept that `institutional_reach` becomes deterministic
   (drop `random.randint`).

2. `[NEEDS DECISION: /search_author — Go PG fast-path in front of a Python
   proxy fallback?]` A Go handler could serve the `researcher_metrics` +
   `researcher_works` hit case and proxy everything else to Python. Blockers:
   40-field `AuthorResponse` parity; port or skip `fetch_similar_authors`
   (OpenAlex fan-out); a Go `is_llm_working()` equivalent for the
   `metrics_computed` / `llm_active` flags; Python `profile::` `PgBackedCache`
   parity; and the background teleport enqueue can only stay Python — is a
   partial move worth the split?

3. `[NEEDS DECISION: /author_metrics — brief premise retired.]` The dispatch
   brief and the roadmap Phase 1 row list "metrics" as in-scope on the
   assumption it reads `researcher_metrics`. It does not — it is a Groq LLM
   call (`ScrapingService.parse_content_to_json`). It stays in Python under the
   "Python = LLM-only" rule. Confirm the roadmap row is updated.

4. `[NEEDS DECISION: /network_collaborators — its own phase (1d)?]` No LLM, but
   a depth-1/depth-2 OpenAlex fan-out + 3-table Postgres read/write-back. The
   roadmap says "networkx → gonum/graph"; the current mixin uses no networkx, so
   the port is "OpenAlex fan-out + pgx", still large. Schedule as a dedicated
   phase, not a Phase 1 drive-by.

## Verification

| Check | Result |
|---|---|
| `pytest -q` (baseline, `main`) | **180 passed** |
| `pytest -q` (this branch) | **180 passed** (no code change) |
| `pytest --collect-only -q` | 180 tests |
| `ruff check app` | `All checks passed!` |
| Go `go vet` / `go test` | N/A — no Go change in this PR. Also no Go toolchain on this machine and CI blocked. |

## Files in this PR

| File | Change |
|---|---|
| `docs/plans/2026-09-04-phase1-authors-to-go.md` | New — this plan. |
| `decisions/0009-phase1-authors-assessment.md` | New — records the empty MOVE set and the re-scope. |
| `docs/backend-auth-posture.md` | Note under "Moved off the Python backend": Phase 1 assessed `authors.py`, nothing moved, no route changed auth class. |
