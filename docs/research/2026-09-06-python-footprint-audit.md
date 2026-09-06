# Python footprint audit — how many `.py` files still leave `services/backend`?

**Asked because:** the owner sees "a lot of `.py` files" and wants every
non-LLM service moved to the appropriate language. This audit inventories all
`.py` under `services/backend/` and classifies each file against the settled
boundary (`decisions/0010`): a file stays in Python **iff its core work is an
LLM call or an embedding**.

**Verdict:** the migration is essentially complete. **~9 files leave Python**
(7 dead / duplicated, 2 replaced by a managed cache). The remaining ~110 are
the LLM service and the FastAPI host that serves it. The large non-LLM tranche
the question imagines was already moved to Go in Phases 0–2.

## What already moved (Phases 0–2, on `main` today)

| Surface | Now served by |
|---|---|
| `/recommendations/peers*` (CoLab autocomplete) | `services/backend-go/internal/recommendation/` |
| `user_memory` endpoints | `internal/user/user.go` |
| `/support/metrics`, `/integrations/zotero/*`, `POST /daily_feed/dismiss` | `internal/feed/feed.go` |
| `GET /search_author`, `GET /refresh_author` (front doors) | `internal/author/search.go` |
| `/author_suggestions`, `/resolve_email`, `/orbit_metrics`, `/citation_heatmap`, `/network_collaborators`, `/author_metrics` | `internal/author/*.go` |
| `/leaderboard/{field}` | `internal/quest/quest.go` |
| per-process rate limiter, Prometheus `MetricsStore`, `GET /metrics` | `internal/middleware/ratelimit.go`, gateway request path |

The Phase 1 authors stream's written conclusion:
`docs/plans/2026-09-04-phase1-authors-to-go.md` — **"MOVE set: empty"**. Every
remaining `authors.py` route calls an LLM, calls an embedding service, depends
on a Firestore cache tier the Go gateway lacks, or is a large multi-source
port.

## Full inventory — `services/backend/` = 136 `.py` files

| Bucket | Count | Verdict |
|---|---:|---|
| LLM / embedding / agent core (`services/ai/*`, `prompts/*`, `pipeline/*`, `connectors.py`, `metrics_service.py`, `industry/*`, `scraping_service.py`, `researcher_worker.py`, `recommendation/engine.py`) | ~46 | **Stay — permanently.** This is the service. |
| FastAPI plumbing serving the LLM routes (`main.py`, `config.py`, `api/*`, all `endpoints/*`, `schemas/*`, `core/*`, `db/{database,pg_cache}.py`, `__init__.py`) | ~33 | **Stay**; several shrink. |
| SQLAlchemy models (`models/*.py`) | 5 | **Stay as files**, trim Go-owned tables out (`users`, `connections`, `researcher_connections`, `daily_feed_items`, leaderboard). |
| **Dead code / Go duplicates → DELETE** | **3 + companions** | **Leave Python.** See below. |
| Hand-rolled two-tier cache (`core/cache.py`, `db/pg_cache.py`) | **2** | **Replace with Upstash Redis** — collapses to a ~40-line async client. Not a Go rewrite. |
| Model-crypto for the Go-owned `users.email` (`db/blind_index.py`, `db/encrypted_type.py`) | 2 | **Leave Python** — but needs `user_models.py` surgery + an Alembic migration, so it rides with the model-trim work, not the pure-deletion PR. |
| Tests (`tests/**/*.py`) | 42 | Follow their targets. |
| Alembic migrations | 4 | Stay — Python owns schema-migration tooling. |
| Root / scripts (`ci_bootstrap_db.py`, `scripts/backfill_email_bidx.py`, `scripts/gen_openapi_snapshot.py`) | 3 | `backfill_email_bidx.py` retires with the blind index; other 2 stay. |

## The dead files (this PR)

| File | Evidence it is dead |
|---|---|
| `app/services/domain/author_service.py` | No `import` anywhere in `services/backend/` (grep, whole tree incl. tests/scripts). |
| `app/repositories/author_repository.py` | Imported only by the dead `author_service.py`. |
| `app/services/data/researcher_fetcher.py` (`PhysicsResearcherFetcher`) | Not imported by `app/`. Not used by any worker. `scripts/ops/fetch_physics_profiles.py` is standalone (`requests` → OpenAlex directly). Only reference was one orphan test. |
| `tests/test_data_quality.py::test_data_ingest_filters_researcher_fetcher` | Tests only the file above; removed with it (import line + 34-line test). |

**Not touched here** (`app/services/domain/` and `app/repositories/` are
implicit namespace packages — no `__init__.py` to remove).

## Why it is not more

`openalex_service.py` is imported by **20 modules**, almost all LLM routes
(prediction, user-context, teleport worker, every pipeline mixin, quests,
papers, feed, industry). An LLM route calling OpenAlex to build prompt context
is *core work*, so its OpenAlex client stays in Python — even though Go has a
parallel `internal/services/openalex/client.go`. Each service owning its own
outbound clients is correct, not duplication to remove.

The high `.py` count reflects what an LLM service legitimately needs: prompt
templates, Pydantic response schemas, an OpenAlex client, response caching, DB
models for its own tables (`cache_entries`, `agent_chat_history`,
`conjectures`, quests, `researcher_profiles/metrics`), and a FastAPI shell.

## Recommended sequence

| Step | Scope | Effort |
|---|---|---|
| 1 (this PR) | Delete the 3 dead files + the orphan test. Zero runtime path touched. | done |
| 2 | `PgBackedCache` → Upstash Redis client behind the existing `cache.get/set` interface. Provision a free Upstash DB. | ~half day |
| 3 | Trim `models/*` to the tables Python actually reads; drop `users`/email crypto (`blind_index.py`, `encrypted_type.py`) + `backfill_email_bidx.py`; Alembic migration; regenerate the OpenAPI snapshot. | ~half day |
| — | Leave the LLM/embedding/agent core alone — already on the correct side of the line. | — |

## Prior art / disagreement

`docs/research/2026-09-04-minimal-python-llm-boundary.md` argued Python could
go to **zero** by porting the ~8 LLM endpoints into a Go `internal/llm`
package and deleting `services/backend`. `decisions/0010` (one day later)
**explicitly rejected that** — "the Python service is the LLM service, not
scaffolding." This audit assumes `0010` stands. Revisiting the delete-entirely
option is a separate, larger decision, not part of the ~9-file cleanup above.

## Method

- `find services/backend -name '*.py'` → 136 files (87 `app/`, 42 `tests/`, 4
  `alembic/`, 3 root/scripts).
- Keyword scan of every `app/` file for LLM/embedding markers
  (`llm_service`, `embedding`, `prompt`, `cosine`/`mmr`, `parse_content_to_json`,
  provider names, …).
- `grep -rn` every deletion candidate across the whole tree (app, tests,
  scripts) for any `import`.
- Cross-checked against `decisions/0002`, `0008`, `0009`, `0010` and the three
  `docs/plans/2026-09-0{3,4}-*` migration plans.
- Verified post-deletion: `ruff check` clean, `ruff format --check` clean,
  `pytest tests/` = 182 passed, `from app.main import app` imports.
