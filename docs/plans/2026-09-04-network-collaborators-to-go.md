# Move `GET /network_collaborators` to the Go gateway

- **Status:** in progress
- **Branch:** `feat/network-collaborators-to-go`
- **Base:** `origin/main` @ `02001fe`
- **Rule this serves:** every non-LLM concern leaves Python (decisions/0002,
  "Python is LLM-only"). `/network_collaborators` has no LLM call and no
  embedding — it is OpenAlex fan-out + Jaccard math + Postgres read/write.

## Goal

Serve `GET /network_collaborators` from `services/backend-go`
(`internal/author/network.go`), native `pgx` via `db.Pool`, OpenAlex via the
existing `internal/services/openalex` client. Response shape, query params and
their defaults are byte-compatible with the retired Python route. Remove the
Python handler and the now-dead `NetworkMixin`.

## Scope

| In | Out |
|---|---|
| New `services/backend-go/internal/author/network.go` (same `package author`) | Any other Go package |
| One appended route block in `services/backend-go/main.go` | Reordering existing routes |
| Delete Python handler in `app/api/v1/endpoints/authors.py` | The `authors` router itself (kept) |
| Delete `app/services/platform/pipeline/network.py` + its `NetworkMixin` wiring in `pipeline_services.py` | `_upsert_researcher_profile` (lives in `author_chat.py`, still used) and `is_field_semantically_relevant` (in `text_utils.py`, re-exported) |
| Prune Python tests that exercised the removed route | Auth-posture `EXPECTED_*` sets (route was public — sets unchanged) |
| Regenerate `api-contracts/openapi.snapshot.json` | |
| Go table tests in `internal/author/` | |

## Cache tier — deferred

Python wraps the route in `network_collaborators_cache =
PgBackedCache(ttl_seconds=3600, name="network_collaborators")` (a
`cache_entries`-backed tier). Stream `feat/go-cache-firestore-heatmap` is
building the shared `internal/cache` `cache_entries` client concurrently and has
not landed. Per the task brief, this port ships **without that outer 1 h tier**
and computes live each call, mitigated by the two inner Postgres cache layers it
*does* port:

- `researcher_connections` table fast-path (24 h TTL) — read + write-back.
- the legacy `pipeline::network_collaborators_<id>_<field>` `cache_entries`
  blob (1 h TTL) — read + write-back, done with direct `pgx` the same way
  `internal/feed/feed.go` already writes `cache_entries`.

`network.go` carries `// TODO: wire internal/cache once
feat/go-cache-firestore-heatmap lands` at the handler entry.

**[NEEDS DECISION]** Once `internal/cache` exists: re-add the outer 1 h
`network_collaborators` tier, or accept the 24 h `researcher_connections`
fast-path as sufficient and delete the dead `network_collaborators_cache`
singleton from `app/core/cache.py` + `app/main.py`.

**[NEEDS DECISION]** `app/core/cache.py::network_collaborators_cache` and its
`.clear()` call in `app/main.py` lifespan are left in place by this PR (out of
the declared file set, and harmless — a startup L1 wipe of an unused
namespace). Flag for a follow-up sweep.

## Per-symbol port map

| Python (`app/services/platform/pipeline/network.py` unless noted) | Go (`internal/author/network.go`) | Notes |
|---|---|---|
| route `get_network_collaborators` (`app/api/v1/endpoints/authors.py`) | `GetNetworkCollaborators(c *gin.Context)` | param parse, exclude filter, `[offset:offset+limit]` slice |
| `network_collaborators_cache` wrap (`app/core/cache.py`) | *not ported* | outer 1 h tier — see "Cache tier — deferred" |
| `NetworkMixin.get_network_collaborators` | `computeNetworkCollaborators(ctx, p)` | pipeline body |
| `NetworkMixin._resolve_author_id_by_name` | `resolveAuthorIDByName(ctx, name, field)` | OpenAlex name→id, token + concept match, first-result fallback |
| `NetworkMixin._compute_jaccard_similarity` | `computeJaccardSimilarity(a, b []string) float64` | exact port incl. 0.5 partial-substring credit, `min(1.0, overlap/union)` |
| `_process_depth1_work` / `_process_depth1_authorship` | `processDepth1Work(...)` | direct-coauthor accumulation, `joint_count++` |
| `_process_depth2_works` / `_process_depth2_work` / `_process_depth2_authorship` | `processDepth2Works(...)` / `processDepth2Work(...)` | 2-hop, skips excluded / already-seen |
| `_PipelineBase._fetch_author_profile` | `openAlexClient.FetchAuthorByID` | |
| `AuthorChatMixin._upsert_researcher_profile` | `upsertResearcherProfile(ctx, raw, ttlDays)` | `INSERT ... ON CONFLICT (openalex_id) DO UPDATE` into `researcher_profiles`; best-effort |
| ResearcherConnection fast-path SELECT | `readCachedConnections(ctx, cleanID, excl, field, limit, offset)` | `expires_at > now`, `ORDER BY relevance_score DESC`, field filter via `isFieldSemanticallyRelevant` |
| ResearcherConnection `delete(...)` + row inserts | `writeConnections(ctx, cleanID, pool)` | single `pgx.Tx`, 24 h `expires_at`; best-effort |
| `_load_from_postgres` / `_save_to_postgres` (`PgBackedCache name="pipeline"`) | `readPipelineBlob` / `writePipelineBlob` | `cache_entries.cache_key = "pipeline::network_collaborators_<cleanID>_<field>"`, value wrapped `{"v": {"collaborators": [...]}}` |
| `is_field_semantically_relevant` (`app/services/platform/pipeline/text_utils.py`) | `isFieldSemanticallyRelevant(field, path, discipline)` | substring + word-overlap + `domain_keywords` stem expansion (phys/comput/cs/ai/bio/chem/math/eng) |
| `openalex_service.is_work_relevant_to_discipline` | `isWorkRelevantToDiscipline(w, discipline)` | concept/topic/title/journal/abstract keyword scan + discipline term expansion; `""` / generic disciplines → always true |
| batch `GET /authors?filter=openalex:a|b|...` (raw `httpx` in Python) | `fetchAuthorStatsBatch(ctx, ids)` | raw `http.Client`, chunks of 50, polite `mailto`; fills `works_count` / `h_index` / `concepts` / `institution` / `raw` |
| DB stats fallback (`researcher_profiles` then `researcher_metrics`) | `fillStatsFromDB(ctx, missing, stats)` | same two-table order |

## Response-shape parity

Bare JSON array. Element = `NetworkCollaborator`
(`app/schemas/authors_extra.py`, `extra="allow"`; TS `apps/web/src/lib/types.ts`).

| Field | Python origin (live-compute row) | Go `json:` tag | Go type | Client contract |
|---|---|---|---|---|
| `id` | `auth_id` — full `https://openalex.org/A…` URL | `id` | `string` | TS `string` (required) |
| `name` | `d1/d2["name"]` | `name` | `string` | TS `string` |
| `institution` | `d1/d2["institution"]`, default `"Independent Researcher"` | `institution` | `string` | TS `string` |
| `field` | `d1["field"] or "Researcher"` / `d2["field"]` | `field` | `string` | TS `string` |
| `connection_path` | `"Co-authored '<paper>' with <primary>"` (d1) / `"Collaborates with <d1> (connected via <primary>)"` (d2) | `connection_path` | `string` | TS `string` |
| `relevance_score` | d1: `min(99, max(80, int(80 + sim*40 + joint*2)))`; d2: `min(99, max(60, int(60 + sim*100)))` | `relevance_score` | `int` | Pydantic `float \| None`; TS `number` |
| `papers_collaborated` | d1: `joint_count`; d2: `0` | `papers_collaborated` | `int` | TS `number?` |
| `total_publications` | `stats.works_count` (0 if unknown) | `total_publications` | `int` | TS `number?` |
| `h_index` | `stats.h_index` (0 if unknown) | `h_index` | `int` | TS `number?` |
| `depth` | `1` or `2` | `depth` | `int` | not in TS type — tolerated (`extra="allow"`), ignored client-side; matches Python live rows |

Ordering: `collaborators_pool.sort(relevance_score desc)` → Go
`sort.SliceStable` on `RelevanceScore` desc. Exclusion + window: filter
`cleanID(id) ∉ exclude_set`, then `[offset : offset+limit]`.

## Params + defaults

| Param | Python | Go |
|---|---|---|
| `author_id` | `Query(...)` required | required; missing → `400` (sibling Go author handlers use 400; FastAPI used 422 — no client depends on it) |
| `exclude_ids` | `Query("")`, comma-split | `c.Query("exclude_ids")`, comma-split, trims blanks |
| `field` | `Query("")` | `c.Query("field")` |
| `name` | `Query("")` | `c.Query("name")` |
| `limit` | `PaginationParams` `Query(20, ge=1, le=100)` | default 20, clamped to `[1,100]` (FastAPI 422 on out-of-range; Go clamps — noted deviation) |
| `offset` | `PaginationParams` `Query(0, ge=0)` | default 0, clamped `>= 0` |

## Error-status deltas (Python → Go)

| Case | Python | Go |
|---|---|---|
| missing `author_id` | 422 (FastAPI) | 400 |
| unresolvable id / `"fallback_seed"` | `ValueError` → 500 (app catch-all) | 400 `{"error": …}` |
| author not on OpenAlex | `ValueError` → 500 | 404 `{"error": …}` |
| no works for author | `ValueError` → 500 | 404 `{"error": …}` |
| OpenAlex compute failure | `ValueError` → 500 | 502 `{"error": …}` |
| `limit` out of `[1,100]` | 422 | clamped, 200 |

## Route wiring

`main.go`, appended after the existing author block, no reorder:

```go
r.GET("/api/v1/network_collaborators", author.GetNetworkCollaborators)
r.GET("/network_collaborators", author.GetNetworkCollaborators)
r.GET("/api/v1/authors/network_collaborators", author.GetNetworkCollaborators)
```

Mirrors the existing `orbit_metrics` / `resolve_email` multi-alias style. The
web client calls the bare `/network_collaborators` on `:8080`; the
`/api/v1/...` alias keeps the old Python contract path working through Go.

## Python removals

| File | Change |
|---|---|
| `app/api/v1/endpoints/authors.py` | delete `get_network_collaborators`; drop now-unused imports `NetworkCollaborator`, `network_collaborators_cache`, `PaginationParams`; leave a `# GET /network_collaborators — migrated to Go` marker |
| `app/services/platform/pipeline/network.py` | delete file (whole `NetworkMixin` is now dead: `_compute_jaccard_similarity` / `_resolve_author_id_by_name` were used only here) |
| `app/services/platform/pipeline_services.py` | drop `NetworkMixin` import + base class |
| `tests/api/test_authors.py` | drop `NetworkCollaborator` import, `_FakePipeline.get_network_collaborators`, `test_network_collaborators_is_typed_array_and_bounds_limit` |
| `tests/test_product_requirements.py` | drop `test_network_collaborators_mocked` |
| `api-contracts/openapi.snapshot.json` | regenerate (`scripts/gen_openapi_snapshot.py`) + LF-normalize |

`tests/api/test_auth_posture.py`: **no change** — `/network_collaborators` was
public, so `EXPECTED_AUTHED` / `EXPECTED_OPTIONAL` are unaffected and
`test_public_is_the_remainder` recomputes from the live table.

## Verification

- Python: `python -m ruff check app` clean; `pytest` — baseline 166 → 164
  (two removed), affected files green, `test_openapi.py` green against the
  regenerated snapshot.
- Go: no local toolchain — CI `SkoLab CI Pipeline / build-and-test`
  (`go build` + `go vet` + `go test -race`) is the gate. Iterate on
  `gh pr checks` until green.

## Go tests (`internal/author/network_test.go`)

- `computeJaccardSimilarity`: disjoint → 0; identical → 1; partial-substring
  0.5 credit; empty input → 0.
- `GetNetworkCollaborators` with `db.Pool == nil`: still returns `200` +
  JSON array (live OpenAlex path), missing `author_id` → `400`, `limit`
  clamped.
- shape: unmarshal a synthesised `collaborators_pool` row and assert the ten
  JSON keys and their types.
