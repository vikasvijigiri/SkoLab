# SkoLab — Founding Plan

> Written as the pre-build plan for SkoLab. Kept at the root as a historical
> reference for *why* the repo is shaped the way it is — architecture and
> priorities may have since evolved; check `decisions/` for anything that
> superseded a call made here, and `HANDOFF.md` for current state.

## 1. Problem & Vision

Researchers don't have a single place that (a) quantifies their own research
quality and trajectory, (b) surfaces genuinely relevant new literature instead
of generic keyword search, and (c) helps them find collaborators, grants, and
career milestones matched to their actual field. Google Scholar tracks
citations; it doesn't predict, recommend, or coach.

SkoLab is a research-discovery and career-analytics platform for individual
researchers: it enriches a researcher's public profile (via OpenAlex) with
derived metrics (h-index trajectory, disruption/novelty scores, collaboration
network), and layers AI-driven features on top — a personalized daily paper
feed, a "Horizon" discovery engine for emerging research directions, grant and
industry-opportunity matching, and an assistant-professor career roadmap.

## 2. Target Users & Platforms

Primary user: an individual academic researcher (grad student through faculty)
who wants a personal research dashboard. Two client platforms from day one,
sharing one backend and one design system:

- **Android (Jetpack Compose)** — the primary mobile client; ships first.
- **Web (Next.js)** — desktop-native companion, same feature set, same visual
  language, for the "at my desk, deep work" use case Android isn't suited to.

Non-goals for v1: institutional/admin accounts, multi-tenant orgs, a public
researcher-search product independent of an individual's own account,
publishing/paywall content — SkoLab reads and enriches *public* metadata
(OpenAlex, arXiv), it does not host paper content.

## 3. Architecture

```
                    ┌─────────────────┐        ┌──────────────────────┐
  Android (Kotlin)  │                 │        │                      │
  Web (Next.js)  ───▶  Go Gateway      ├───────▶  Python Backend (AI)  │
                    │  (auth, proxy,  │        │  OpenAlex/arXiv       │
                    │  CORS, cache)   │        │  enrichment, LLM,     │
                    └────────┬────────┘        │  embeddings, scoring  │
                             │                  └──────────┬───────────┘
                             │                             │
                    ┌────────▼────────┐          ┌─────────▼─────────┐
                    │   PostgreSQL    │          │     Firestore      │
                    │  (hot: users,   │          │  (large docs: full │
                    │  caches, search │          │  works arrays, LLM │
                    │  metadata)      │          │  output, CoLab)    │
                    └─────────────────┘          └────────────────────┘
```

**Why a Go gateway in front of a Python backend, not just Python:** the
gateway owns auth, CORS, request routing, and the endpoints that need to be
fast and boring (profile CRUD, invite/contact sync) — Go's low-latency,
low-memory-footprint concurrency model suits that. The Python backend owns
everything that's inherently slow and I/O- or CPU-heavy: OpenAlex/arXiv
enrichment, LLM calls, embeddings, scoring — Python because that's where the
ML/NLP ecosystem (sentence-transformers, numpy) actually lives. Splitting them
means a slow enrichment call never blocks a fast profile lookup, and either
service can be scaled or redeployed independently.

**Why Firestore *and* Postgres, not just one:** Postgres holds anything
relational, small, and query-heavy (users, cache entries, search metadata,
researcher connection graphs) — cheap, transactional, and already needed for
the encrypted user-record store. Firestore holds large, document-shaped blobs
(a researcher's full OpenAlex works array, LLM-generated reports, CoLab
workspace state) that don't benefit from a relational schema and that the
Android/web clients may eventually want to read directly without a REST
round-trip. See `decisions/0001-two-tier-caching.md`.

**Why Android and web share Firestore directly for CoLab/Profile instead of a
REST API:** those features are inherently collaborative, real-time-ish state
(shared workspace docs, live profile edits) — a REST layer would just be a
thin, laggy proxy in front of Firestore's own realtime sync. Everything that
*isn't* that shape (search, enrichment, recommendations, metrics) goes through
the Go/Python backend instead. See `decisions/0004-firebase-for-colab-profile.md`.

## 4. Build Phases

**Phase 0 — Backend core.** FastAPI service, OpenAlex client, author search
and profile enrichment, Postgres models, in-memory + Postgres caching. Get one
real researcher profile rendering end-to-end before building any UI polish.

**Phase 1 — Android client.** Jetpack Compose app against Phase 0's API:
search, profile view, metrics dashboard (radar chart, stats quad), similar-
researchers, network/collaborator graph. This is the client that ships first
and validates the backend contract.

**Phase 2 — Go gateway.** Once the Python backend's surface area is proven,
extract auth/CORS/proxy/fast-path endpoints into a Go gateway sitting in front
of it, so the mobile client (and later web) never talks to Python directly.

**Phase 3 — Web client.** Next.js app mirroring Android's feature set and
design system (same type scale, same color tokens, compiled from the shared
design-system package) — for desktop use, not a scaled-down mobile port.

**Phase 4 — Discovery & recommendation engine.** Daily personalized paper
feed, Horizon (emerging-direction prediction), grant/industry-opportunity
matching, assistant-professor roadmap. Start with the cheapest thing that
could plausibly work (keyword search + rule-based scoring), then iterate
toward real semantic similarity as the naive version's limitations surface in
practice — this is expected to be the most iterated-on phase over the life of
the project, not a one-shot build. See `HANDOFF.md` and `decisions/` for how
far that iteration has actually gone.

**Phase 5 — Observability & SRE hardening.** Prometheus/Grafana/Alertmanager,
runbooks, postmortem process, load-testing suite (k6), threat model, data-
quality checks. Treated as a real discipline from early on rather than
bolted on right before a launch that may never come — this is a learning
project as much as a product, and doing SRE process properly is part of the
point.

## 5. Tech Stack

| Layer | Choice | Why |
|---|---|---|
| Backend (AI/enrichment) | FastAPI (Python 3.10) | Async-first, and the only realistic home for the ML/NLP dependencies this project needs. |
| Gateway | Go (Gin) | Fast, low-overhead proxy/auth layer; a natural fit for CORS and routing that shouldn't touch Python. |
| Web | Next.js 16 (App Router, Turbopack) + TypeScript + Tailwind v4 | Modern React defaults, fast dev loop, and a framework young enough that an agent should verify current APIs against installed `node_modules` rather than trust training data (see `apps/web/AGENTS.md`). |
| Mobile | Kotlin + Jetpack Compose | Native performance and the modern Android UI toolkit; no cross-platform framework since Android is the primary target and web is a separate, purpose-built client rather than a port. |
| Relational data | PostgreSQL + SQLAlchemy (async) + Alembic | Battle-tested, async-compatible, and already required for encrypted user records. |
| Document/realtime data | Firebase (Auth + Firestore) | Auth-as-a-service plus a realtime document store the mobile app already needed, reused by web instead of building a second auth system. |
| LLM | Groq (primary) + OpenRouter (fallback) | Groq for low-latency inference on hosted open models; OpenRouter as a fallback pool when Groq is unavailable or rate-limited — no single-provider dependency. |
| Embeddings | Self-hosted `sentence-transformers` (see `decisions/0003-self-hosted-embeddings.md`) | No embeddings endpoint on either LLM provider; self-hosting avoids a new external dependency and per-call cost. |
| Observability | Prometheus + Grafana + Alertmanager + Cloudflare | Standard, self-hostable stack; Cloudflare in front for CDN/WAF. |

## 6. Success Criteria for v1

- A researcher can search their own name, see a real enriched profile
  (metrics, radar chart, similar researchers), on both Android and web.
- The daily feed surfaces papers that are *actually* relevant to that
  researcher's real, current research area — not just their broad field.
- Backend survives an OpenAlex or LLM-provider outage without cascading
  (circuit breakers, cached fallbacks) — see `docs/lessons_learned.md`.
- Every non-obvious architectural or scope call is written down in
  `decisions/`, not just known tribal knowledge.
