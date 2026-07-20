# 0001. Two-tier caching: Postgres + Firestore, not one store

**Date:** 2026-06-29
**Status:** Accepted

## Context

The backend needs to cache two very different shapes of data: small,
structured, frequently-queried records (cache metadata, user records, search
history), and large, document-shaped blobs (a researcher's full OpenAlex
works array, LLM-generated reports, daily-feed results) that don't benefit
from a relational schema and can run to hundreds of KB per entry.

A single store for both means either forcing large documents into relational
tables (expensive migrations, awkward querying) or forcing small structured
data into a document store (losing transactional guarantees and cheap joins).

## Decision

Split by shape, not by feature:
- **PostgreSQL** — the `cache_entries` table backs `PgBackedCache`
  (`app/db/pg_cache.py`): L1 in-memory (30s) + L2 Postgres (configurable TTL,
  typically 1h), used by every feature-specific cache (`pipeline_daily_feed`,
  `pipeline_match_grants`, etc., all namespaced by cache name).
- **Firestore** — large enriched documents (full works arrays, LLM output,
  the `daily_feeds` collection) that clients may eventually read directly,
  plus all CoLab workspace and Profile data (see `0004`).

Firestore's own staleness is checked manually (`last_synced` timestamp
compared against a freshness window) since Firestore itself has no TTL —
without that check, once the faster Postgres cache expires, it would just
re-read the same (potentially year-old) Firestore doc forever.

## Alternatives considered

- **Redis for everything.** Supported in code (`PgBackedCache` checks for
  `REDIS_URL` and prefers it when set) but not configured in this deployment
  — Postgres is the real L2 today. Redis remains a drop-in upgrade path for
  a distributed multi-node deployment (see `docs/scaling-decision.md`)
  without a code change, which is exactly why the cache abstraction exists.
- **Postgres only, JSONB columns for the large blobs.** Rejected: still
  couples large-document growth to the same database doing transactional
  work, and neither Android nor web would get realtime sync for free the way
  they do from Firestore.

## Consequences

Two systems to reason about instead of one, and two places a "why is this
stale" bug can hide (this bit us directly during recommendation-engine work —
see `HANDOFF.md` gotchas). In exchange: cheap relational queries stay cheap,
large documents don't bloat the transactional database, and CoLab/Profile
get realtime sync without a bespoke websocket layer.
