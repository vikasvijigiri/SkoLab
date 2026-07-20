# 0002. Go gateway in front of the Python backend

**Date:** 2026-06-29
**Status:** Accepted

## Context

The backend has two very different kinds of work: fast, boring, high-volume
requests (auth, profile CRUD, contact/invite sync, CORS-sensitive browser
traffic) and slow, heavy, I/O- or CPU-bound work (OpenAlex/arXiv enrichment,
LLM calls, embeddings, scoring). Putting both in one Python process means a
slow enrichment request can back up the event loop for everything else, and
means every fast endpoint pays Python's overhead for no reason.

## Decision

Split into two services:
- **`services/backend-go`** (Gin) — the edge: auth, CORS, request routing,
  and the fast-path endpoints (profile CRUD, contacts/invite sync,
  check-registered lookups) that benefit from Go's low-latency concurrency
  model. This is what both clients talk to directly.
- **`services/backend`** (FastAPI) — everything AI/enrichment: OpenAlex/
  arXiv clients, LLM calls, embeddings, recommendation scoring. Proxied to
  by the Go gateway, never called directly by a client.

## Alternatives considered

- **One Python service for everything.** Simpler to reason about, but ties
  the fast-path availability to the health/load of the heavy AI workload —
  exactly the coupling `docs/scaling-decision.md` later flagged as a
  bottleneck (CPU-bound tasks blocking the event loop for simple endpoints).
- **One Go service for everything, calling out to a Python worker for AI
  only.** Considered and effectively where this architecture ends up in
  practice, just without a formal job-queue boundary yet (see
  `docs/scaling-decision.md`'s proposed Celery/Redis worker-pool phase,
  which hasn't been built).

## Consequences

Two languages, two deploy units, two places to add a new endpoint (decide
which side it belongs on: is it fast/simple, or AI/heavy?). In exchange, the
fast path (most traffic, most latency-sensitive) never contends with slow
LLM/OpenAlex round-trips for CPU or connections, and either side can be
redeployed or scaled independently.
