# 0003. Self-hosted embeddings over an API provider or TF-IDF

**Date:** 2026-07-13
**Status:** Accepted

## Context

The daily-feed recommendation engine's similarity scoring started as a naive
bag-of-words/token-overlap formula, then briefly a TF-IDF + cosine-similarity
implementation reused from `app/domains/recommendation/engine.py`. Both
structurally miss paraphrase/synonymy (e.g. "spin-echo dephasing" vs.
"coherence loss under refocusing pulses" score as unrelated even though
they're the same phenomenon), which showed up directly as bad recommendations
during testing.

Moving to real neural embeddings required deciding *how*: no embeddings
endpoint was configured anywhere in this deployment — the only two LLM
providers wired in, Groq and OpenRouter, both do chat completions only, no
embeddings API.

## Decision

Self-host `sentence-transformers` with `BAAI/bge-small-en-v1.5` (384-dim,
~130MB) — `app/services/ai/embedding_service.py`. Model weights are
pre-downloaded at Docker build time (not on first request) so runtime never
depends on HuggingFace Hub being reachable. CPU-only `torch` is installed
explicitly (not the default CUDA-bundled wheel) to keep the image size sane.

## Alternatives considered

- **Hosted embeddings API** (Gemini `gemini-embedding-001`, OpenAI
  `text-embedding-3-small`). No image-size cost, no torch dependency — but
  requires obtaining and configuring a new API key for a provider not
  otherwise used in this project, plus a network round-trip and rate limits
  on every recommendation computation. Rejected in favor of no new external
  dependency, given the option existed to self-host instead.
- **Keep TF-IDF, just enrich the vocabulary.** Lowest-risk, zero new
  dependencies — but doesn't fix the structural paraphrase-blindness problem,
  which was the actual complaint. Rejected as not actually solving the
  problem.

## Consequences

The Docker image grew meaningfully (CPU torch + model weights baked in at
build time) and image builds take longer. In exchange: genuinely better
similarity judgments, no per-call external cost or rate limit, and no new
API key to provision. This decision directly required a second, harder-won
fix — see the embedding-latency root-cause noted in `HANDOFF.md` (batch
padding to the longest text in a batch was inflating latency 10-40x; fixed
by truncating candidate text before embedding and running the CPU-bound
encode call via `asyncio.to_thread`).
