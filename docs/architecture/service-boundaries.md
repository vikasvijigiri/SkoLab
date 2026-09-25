# Service boundaries

SkoLab has three deployable/runtime boundaries. Keep requests on the smallest
boundary that can own them end to end.

## Android client

The Android app owns device UI, local encrypted preferences, and Firebase user
authentication. It calls public backend APIs with a Firebase ID token. It never
connects directly to the Postgres database.

## Go gateway

`services/backend-go` owns request-edge concerns: Firebase token verification,
rate limiting, CORS, WebSocket/realtime coordination, and low-latency
Postgres-backed reads or writes. It may call FastAPI only for model-bound work.

## FastAPI service

`services/backend` owns AI and enrichment workloads: embeddings, LLM calls,
OpenAlex/data-provider integration, bounded long-running computation, and
domain workflows requiring those capabilities.

## Cross-service rules

1. The gateway and FastAPI service authenticate internal calls with the same
   `INTERNAL_API_TOKEN`.
2. Contract changes update `contracts/` and pass `make verify-contracts`.
3. New public routes default to gateway ownership unless they are inherently
   model- or enrichment-bound.
4. Request handlers must not fabricate fallback data; return explicit pending,
   unavailable, or partial states instead.
