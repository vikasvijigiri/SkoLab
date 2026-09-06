# 0011. Similarity engine: pgvector kNN + graph blend in Go, embeddings in Python

**Date:** 2026-09-06
**Status:** Accepted

## Context

Two product surfaces need a real similarity engine:

- **Researchers you may know** (Home + author page) — was a string-overlap hack
  over the first few co-authors of topic-matched papers (`decisions/0005`),
  not similarity-ranked, no graph, never excluded existing connections.
- **Similar papers** — did not exist.

There was no persistent vector store; embeddings were recomputed per request
and cached as JSON blobs in `cache_entries`.

The owner's standing rule (`decisions/0010`, memory `python-llm-only-boundary`):
the app is **not LLM-heavy**, Python is restricted to model inference, and a
new capability should adopt the world-class pattern rather than be hand-rolled.

## Decision

### Architecture

| Concern | Home | Why |
|---|---|---|
| Embedding computation (`bge-small`, 384-d) | **Python** — teleport worker + `POST /internal/similar/embed_work` | Model inference. The one legitimate Python touch. |
| Vector store | **Postgres + `pgvector`** — `work_embeddings`, `author_embeddings` | Standard vector store for a Postgres app; free on Supabase. |
| kNN + ranking + MMR + the HTTP endpoints | **Go gateway** — `internal/similarity` | A DB query plus arithmetic. No model call. Same split as `network_collaborators` and the Go half of `author_metrics`. |

No ANN index in the first cut — exact `<=>` scan is fine under ~50k rows and
avoids HNSW build-memory pressure on the 512 MB instance. HNSW lands in its
own migration once row counts justify it.

### Ranking

- **`GET /api/v1/similar_papers`** — `cosine + 0.15·shared-concept-Jaccard +
  0.15·bibliographic-coupling`, then MMR.
- **`GET /api/v1/similar_researchers`** — blended score
  `0.50·cosine + 0.25·shared-collaborator-Jaccard + 0.15·shared-concept-Jaccard
  + 0.10·same-institution`, minus the caller's `user_circles` / accepted
  `connections` / direct co-authors, then MMR. Weights are `SIM_W_*`
  env-overridable for live tuning.
- Each result carries a plain-language `why` string.
- Cold entity → one Python embed callback / teleport enqueue, then a degraded
  OpenAlex fallback (`related_works` / topic-derived co-authors) flagged
  `degraded: true`.

### Free-tier budget

~2 KB per embedding row. The 500 MB DB ceiling caps the store at roughly
**80k rows total**. Only entities actually touched (teleport-enriched authors +
their ~25 recent works, and queried papers) are stored; a 90-day
`updated_at` sweep in `scripts/database/db-cleanup-retention.py` reclaims the
rest. A pruned row is re-embedded on its next hit.

## Alternatives considered

- **Adopt a recommendation library** (LightFM, implicit, RecBole, Microsoft
  Recommenders). Rejected: those are collaborative-filtering engines over a
  user–item interaction matrix. SkoLab has almost no interaction data (cold
  start) and the signal that matters — scholarly co-authorship and concept
  structure from OpenAlex — is domain-specific. Content-based kNN + a graph
  blend is the correct pattern here, and it is ~600 lines, not a dependency.
- **Port the LLM endpoints and delete `services/backend`** (the
  `2026-09-04-minimal-python-llm-boundary` research). Out of scope and
  superseded by `decisions/0010`; this change keeps the bright line.
- **Keep similarity in Python next to the embedder.** Rejected: the kNN query
  and blend are not model work; they belong in the gateway with every other
  DB-backed read.
- **HNSW from day one.** Deferred — index build memory is a real risk on the
  free instance and exact scan is fast enough at current scale.

## Consequences

- **Better:** researcher and paper similarity are now genuine vector + graph
  ranking with an explanation, and connection suggestions exclude people you
  already know.
- **Better:** the daily-feed "similar researcher" channel and `search_author`
  can adopt the same engine later with no new infrastructure.
- **Harder:** a cold query paper adds a Go→Python→OpenAlex hop on first hit
  (mitigated by the persistent store and the degraded fallback).
- **New ops surface:** a `pgvector` extension, two tables, and a size budget
  to watch. CI's Postgres image moved to `pgvector/pgvector:pg15`.
- `decisions/0005` is superseded for the researcher channel; its OpenAlex
  fallback path lives on as the degraded mode.
