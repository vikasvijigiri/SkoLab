# Plan — World-class similarity engine (similar researchers + similar papers)

## Context

**Why now:** the user wants one recommendation engine powering two product
surfaces — *similar researcher profiles* (connection suggestions on Home + the
author page) and *similar papers* (does not exist yet). They picked "engine
first, infra PRs after" and "full pgvector hybrid".

**What's wrong today:**

| Surface | Today | Problem |
|---|---|---|
| "Researchers you may know" | `search_author` → OpenAlex `search_works?q=<topic string>` → first 5 unique co-authors → string-overlap score (`decisions/0005`) | Not similarity-ranked (iteration order), no embeddings, no graph, doesn't exclude existing connections, brittle on vague focus |
| "Similar papers" | — | Missing entirely |
| Vector store | none — embeddings computed per-request, cached as JSON blobs in `cache_entries` | No persistence, no kNN |

**Outcome:** a persistent `pgvector` store (populated lazily by the existing
teleport worker), two Go gateway endpoints that blend embedding cosine + the
co-authorship graph + shared-concept + bibliographic-coupling signals with MMR
diversification, and web UI for both surfaces. `search_author.similar_researchers`
is re-pointed at the new engine so the existing Home/author-page cards get the
upgrade for free.

**Bright-line (`decisions/0010`) split:** embedding *computation* is Python
(teleport worker + one internal route); the kNN query + graph blend + MMR is a
Postgres-query-and-arithmetic job and lives in the **Go gateway**, exactly like
`network_collaborators` and the Go half of `author_metrics`.

---

## Phase A — schema + Python backfill (ships dark, no reader)

### A1. Alembic migration
- New revision `b2c3d4e5f6a7_add_similarity_vectors.py`, `down_revision =
  "a1b2c3d4e5f6"` (current head).
- `op.execute("CREATE EXTENSION IF NOT EXISTS vector")` — Supabase ships
  pgvector; only needs enabling.
- Tables (no ANN index in cut 1 — exact kNN is fine under ~50k rows and avoids
  HNSW build-memory risk on the 512 MB instance; HNSW is Phase D):

  ```
  work_embeddings(
    work_id text primary key,
    embedding vector(384) not null,
    title text, concepts text[], referenced_works text[],
    publication_year int, updated_at timestamptz default now())

  author_embeddings(
    author_id text primary key,
    embedding vector(384) not null,
    concepts text[], coauthor_ids text[], institution text,
    works_count int, h_index int, updated_at timestamptz default now())
  ```
- `down_revision` reversal drops both tables, leaves the extension.

### A2. SQLAlchemy models
- Add `WorkEmbedding`, `AuthorEmbedding` to `app/models/researcher_models.py`
  using `pgvector.sqlalchemy.Vector(384)` — **new dep** `pgvector==0.3.6` in
  `services/backend/requirements.txt` (pulls nothing heavy; it's a thin
  SQLAlchemy/`asyncpg` type adapter).

### A3. Teleport worker extension — `app/services/data/researcher_worker.py`
- After the existing `_pg_upsert_researcher_metrics` / `_pg_upsert_researcher_works`
  calls in `teleport_researcher`, add `_pg_upsert_embeddings(clean_id, works,
  author_raw)`:
  - For up to 25 recent works: build `title + " " + reconstructed_abstract`
    (helper `_reconstruct_abstract` already exists), call
    `embedding_service.embed_texts(...)`, upsert each into `work_embeddings`
    (`work_id`, vector, `concepts` from the work, `referenced_works`,
    `publication_year`).
  - Author vector = L2-normalized mean of those work vectors; upsert
    `author_embeddings` (`author_id`, vector, `concepts` from author,
    `coauthor_ids` = union of co-authors across those works,
    `institution`/`works_count`/`h_index` from `author_raw`).
  - Reuse the `AsyncSessionLocal()` + try/except/rollback pattern already in
    the two `_pg_upsert_*` helpers.

### A4. Internal embed route — `app/api/v1/endpoints/internal.py`
- `POST /api/v1/internal/similar/embed_work` `{ "work_id": "..." }` →
  fetch the work from OpenAlex (reuse `openalex_service`), embed
  title+abstract, upsert `work_embeddings`, return `{ "ok": true }`.
  Shared-secret `X-Internal-Token` gate, same as the existing teleport route.
- Purpose: the Go `/similar_papers` cold path (query paper never seen) calls
  this once, then runs its kNN.

### A5. Bulk seed script — `services/backend/scripts/backfill_embeddings.py`
- Iterate `researcher_profiles` + `researcher_works` already in PG, embed and
  populate the two tables. `--dry-run` writes nothing (prints counts).
  `--limit N`. Mirrors the style of `scripts/backfill_email_bidx.py`.

### A6. Tests — `services/backend/tests/test_similarity_backfill.py`
- Teleport writes N `work_embeddings` + 1 `author_embeddings` (monkeypatch
  `embed_texts` to a deterministic fake).
- `embed_work` internal route: 200 + row present; 401 on bad token.
- `backfill_embeddings.py --dry-run` writes nothing.

---

## Phase B — Go similarity engine + endpoints

### B1. New package `services/backend-go/internal/similarity/`
- `vectors.go` — pgx helpers: `getWorkVector(ctx, workID) ([]float32, ok)`,
  `getAuthorVector(...)`, `knnWorks(ctx, vec, limit)`, `knnAuthors(...)`.
  Bind the query vector as a `::vector` string literal (`'[0.1,0.2,...]'`) —
  the pool runs `QueryExecModeExec` (pgBouncer txn mode, no prepared
  statements), so `pgvector-go`'s binary valuer is avoided; a formatted
  literal in the SQL text is the safe path. Distance operator `<=>` (cosine).
- `mmr.go` — port `app/domains/recommendation/engine.py`'s MMR (`MMR_LAMBDA =
  0.6`) to Go (~30 lines, pure float math).
- `blend.go` — `scoreResearcher(cand, me, weights)`:
  `0.50*cosine + 0.25*jaccard(coauthors) + 0.15*jaccard(concepts) +
  0.10*biblioCoupling`; weights are package consts, env-overridable. Reuse
  `author.computeJaccardSimilarity` (exported or copied) from
  `internal/author/network.go`.
- `similar.go` — the two HTTP handlers (below).
- `similar_test.go` — MMR ordering, blend math, exclude-connected filter,
  cold-path fallback. Follow `internal/author/network_test.go` (skips
  gracefully when `db.Pool == nil`, `httptest` fake OpenAlex).

### B2. `GET /api/v1/similar_papers?work_id=&limit=` (default 10, max 25)
1. `getWorkVector(work_id)`; miss → `POST` Python `internal/similar/embed_work`,
   retry once; still miss → fall back to `openAlexClient.FetchRelatedWorks`
   (already exists) and return those, `degraded: true`.
2. `knnWorks(vec, 200)`, drop the query work itself.
3. Re-rank: `cosine + 0.15*sharedConceptFrac + 0.15*biblioCouplingFrac`.
4. MMR → `limit`.
5. Hydrate `title` / `authors` / `year` from the stored columns (fill gaps
   from OpenAlex only if missing).
6. Response `[{work_id, title, authors, year, score, why}]` where `why` =
   `"0.83 topical · 4 shared references"`.

### B3. `GET /api/v1/similar_researchers?author_id=&limit=&exclude_connected=true`
1. `getAuthorVector(author_id)`; miss → enqueue teleport
   (`POST /internal/teleport/{id}`, fire-and-forget) and fall back to the
   current `author.fetchSimilarAuthors` OpenAlex path, `degraded: true`.
2. Candidate pool = `knnAuthors(vec, 150)` ∪ depth-1 co-authors from
   `coauthor_ids`.
3. Exclusions: `user_circles.peer_id`, `connections` (status `accepted`) for
   the requesting user (pass `user_id` like `network_collaborators` does),
   the author themselves, and direct co-authors when
   `exclude_connected=true`.
4. `blend.scoreResearcher` per candidate → MMR → `limit`.
5. Response `[{author_id, display_name, institution, field_of_study, h_index,
   score, why, shared_collaborators: N}]`, `why` = `"same subfield · 3 shared
   collaborators · 0.79 topical match"`.

### B4. Route registration — `services/backend-go/main.go`
- Register both under the "Author endpoints — Go PG + OpenAlex, no AI" block
  (bare + `/api/v1` forms, matching neighbours).
- `similar_researchers` also gets an `auth.VerifyUser()`-optional group only
  if `user_id` scoping needs the token — match `network_collaborators` (it
  takes `user_id` as a plain query param today; keep parity, no new auth).

### B5. Re-point `search_author` — `services/backend-go/internal/author/search.go`
- `fetchSimilarAuthors(...)` call site in the `AuthorResponse` assembly →
  call `similarity.ResearchersForAuthor(ctx, authorID, "", 5, false)`; on
  empty/error fall back to the existing `fetchSimilarAuthors` (keep the
  function). This upgrades the Home `PeerSuggestionsCard` and the author-page
  "Similar Researchers" card with **zero web changes**.

---

## Phase C — Web UI

### C1. API client — `apps/web/src/lib/api/endpoints.ts` + `types.ts`
- `getSimilarPapers(workId, limit?)` → `SimilarPaper[]`.
- `getSimilarResearchers(authorId, opts?)` → `SimilarResearcher[]` (extends
  `AuthorSuggestion` with `score`, `why`, `shared_collaborators`).
- React-query wrappers in `apps/web/src/lib/api/queries.ts`.

### C2. "Related papers" card — `apps/web/src/app/(app)/paper/[id]/page.tsx`
- New `<Reveal><Card>` section under the analysis sections, `Compass` icon,
  lists up to 8 results with title, author label, year, the `why` string, and
  a link to `/paper/<id>`. Skeleton + calm empty state (match
  `PeerSuggestionsCard`'s pattern). Add a `TableOfContents` entry.

### C3. Richer "Similar Researchers" — `apps/web/src/components/feed/PeerSuggestionsCard.tsx`
  and the author-page section
- Show the `why` line and `shared_collaborators` badge.
- Add a "Connect" affordance that calls the existing `logPeerInvite`
  endpoint (Home card only; author page stays a link).
- Keep the existing `unresolved` cold-start empty state.

### C4. Tests
- `paper/[id]/page.test.tsx` — "renders related papers from the query".
- `PeerSuggestionsCard` test — asserts the `why` line renders.

---

## Phase D — record + hardening (small, after A–C land)

- `decisions/0011-similarity-engine.md` — the schema, the blend weights and
  why, the Go/Python split, the free-tier budget cap.
- TTL cleanup: extend `scripts/database/db-cleanup-retention.py` to delete
  `*_embeddings` rows `updated_at < now() - interval '90 days'` not belonging
  to an active user / recently-viewed work. Document the 500 MB budget
  (≈2 KB/row → cap ~80k rows total).
- Add HNSW indexes (`vector_cosine_ops`, `m=16, ef_construction=64`) **only**
  once row count > ~30k, in its own migration.
- Copy this plan to `docs/plans/2026-09-06-similarity-engine.md` and log the
  task in `TASK.md`.

---

## Files touched (summary)

| Area | Files |
|---|---|
| Migration | `services/backend/alembic/versions/b2c3d4e5f6a7_add_similarity_vectors.py` (new) |
| Python models | `services/backend/app/models/researcher_models.py`, `requirements.txt` |
| Python backfill | `services/backend/app/services/data/researcher_worker.py`, `app/api/v1/endpoints/internal.py`, `services/backend/scripts/backfill_embeddings.py` (new) |
| Python tests | `services/backend/tests/test_similarity_backfill.py` (new) |
| Go engine | `services/backend-go/internal/similarity/{vectors,mmr,blend,similar,similar_test}.go` (new) |
| Go wiring | `services/backend-go/main.go`, `services/backend-go/internal/author/search.go`, `internal/author/network.go` (export `computeJaccardSimilarity`) |
| Web | `apps/web/src/lib/api/{endpoints,queries}.ts`, `src/lib/types.ts`, `src/app/(app)/paper/[id]/page.tsx`, `src/components/feed/PeerSuggestionsCard.tsx`, `src/app/(app)/author/[id]/page.tsx` |
| Web tests | `apps/web/src/app/(app)/paper/[id]/page.test.tsx`, `src/components/feed/PeerSuggestionsCard.test.tsx` |
| Record | `decisions/0011-similarity-engine.md` (new), `docs/plans/2026-09-06-similarity-engine.md` (new), `TASK.md` |

## Delivery — one PR per phase

1. **PR A** — schema + backfill (dark). Verify: `alembic upgrade head` on a
   scratch DB; teleport a known author, assert both tables populate;
   `pytest services/backend/tests/test_similarity_backfill.py`.
2. **PR B** — Go engine + endpoints + `search_author` re-point. Verify:
   `go test ./internal/similarity/...`; `go build ./...`; hit
   `/api/v1/similar_papers?work_id=W...` and
   `/api/v1/similar_researchers?author_id=A...` against a seeded DB and eyeball
   the `why` strings; confirm Home/author cards still render.
3. **PR C** — web UI. Verify: `pnpm --filter web test`, `next build`,
   Playwright walk of `/paper/<id>` (Related papers card) and `/home`
   (Similar Researchers with `why`).
4. **PR D** — decision record + TTL + task log.

## Verification (end-to-end, after all phases)

- Fresh `alembic upgrade head` succeeds; `\d work_embeddings` shows the
  `vector(384)` column.
- `python services/backend/scripts/backfill_embeddings.py --limit 50` populates
  rows; re-run is idempotent.
- `GET /api/v1/similar_papers?work_id=<a real OpenAlex work>` returns ≥5
  results ordered by descending `score`, none equal to the query work, each
  with a non-empty `why`.
- `GET /api/v1/similar_researchers?author_id=<a teleported author>&user_id=<me>`
  excludes everyone already in my `user_circles` / accepted `connections`, and
  the top result shares either a subfield or a collaborator.
- Web `/home` "Similar Researchers" and `/paper/<id>` "Related papers" render
  real data, with skeletons on load and calm empty states when cold.
- `go test ./... && go build ./...` green; `pytest services/backend` green;
  `pnpm --filter web test && next build` green.
- Free-tier check: after seeding 50 authors, `SELECT pg_size_pretty(
  pg_total_relation_size('work_embeddings'))` is single-digit MB.
