# SkoLab — Agent Cold-Start Guide

Read this first. It should be enough to be useful within ten minutes. For
*why* things are shaped this way, see `PLAN.md` and `decisions/`. For what's
currently in flight, see `HANDOFF.md`.

## What this is

A research-discovery and career-analytics platform for individual
researchers: OpenAlex-based profile enrichment, an AI-driven recommendation
engine (daily feed, "Horizon" discovery, grant/opportunity matching), and a
CoLab collaboration workspace — shipped as an Android app and a Next.js web
app sharing one backend.

## Repo map

```
apps/
  android-app/     Kotlin + Jetpack Compose. Primary mobile client.
  web/             Next.js 16 (App Router, Turbopack), TypeScript, Tailwind v4.
                    Has its own apps/web/AGENTS.md — read it before touching
                    anything Next.js-specific; the framework is newer than
                    most training data.
services/
  backend/         FastAPI (Python 3.10). OpenAlex/arXiv enrichment, LLM calls,
                    embeddings, recommendation/scoring logic. Where most
                    backend work happens.
  backend-go/      Go (Gin) gateway. Auth, CORS, proxying, fast-path endpoints
                    (profile CRUD, contacts/invites). Sits between the clients
                    and the Python backend.
shared/
  skolab-design-system/   Design tokens, compiled into both clients.
infrastructure/    Prometheus, Grafana, Alertmanager, Cloudflare config.
api-contracts/     openapi.yaml — schema-first contract. Edit with intent,
                    it's meant to be the source of truth, not a byproduct.
docs/              Runbooks, postmortems, lessons-learned, threat model, UAT
                    checklists. Append to postmortems/lessons_learned; don't
                    rewrite history there.
scripts/           Build, backup, database, ops, security scripts.
tools/clean_caches.py   Cache-clearing utility.
tests/             unit / integration / load (k6).
```

## Running things locally

From repo root (npm workspaces cover `apps/web` and `shared/skolab-design-system`):

```bash
npm install
npm run dev:web        # Next.js on :3000
npm run dev:go         # Go gateway on :8080 (go run main.go)
npm run dev:backend    # uvicorn --reload on :8000
```

Or via `make dev-backend` / `make dev-go` / `make build-android`. Android
builds go through `scripts/build/build-and-install.ps1`, not raw Gradle — see
"Windows-specific gotchas" below.

### The two ways the Python backend runs — don't confuse them

1. **`npm run dev:backend` / `uvicorn --reload`** — hot-reloads on save. Use
   this for normal local iteration.
2. **`services/backend/docker-compose.yml`** (container `skolab_python_ai`) —
   a **static image**, built via `build: .`. It does **not** hot-reload.
   If you're testing against this container (check `docker ps` if unsure),
   every Python change requires, from `services/backend`:
   ```bash
   docker compose build web
   docker compose up -d --no-deps web
   ```
   Forgetting this step is the single most common source of "I fixed it but
   the bug is still there" confusion in this repo. Always verify a backend
   fix with a live request (`curl`) after rebuilding — don't assume a code
   change took effect.

### Cache-invalidation gotcha

Backend responses that hit the recommendation/feed endpoints are cached two
ways: an in-memory + Postgres `cache_entries` layer (1h TTL, `PgBackedCache`),
and a Firestore document layer with its own staleness check (also ~1h). After
a backend logic change, clearing the Postgres cache alone can still serve a
stale Firestore-cached result. If a fresh recompute genuinely needs to be
observed, either wait out the window or use the daily-feed dismiss endpoint's
side effect (dismissing an item whose ID is in the *currently cached* result
forces a real recompute, bypassing both cache layers) — see
`services/backend/app/services/platform/pipeline_services.py`.

## Secrets — where they live, never commit them

- `services/backend/.env` — `GROQ_API`, `OPENROUTER_API_KEY`, `openalex_api`,
  `DATABASE_URL`, `DATABASE_ENCRYPTION_KEY`, `GOOGLE_APPLICATION_CREDENTIALS`
  (points at a `service-account.json` for Firebase/Firestore), PagerDuty/SMTP
  keys. Template: `services/backend/.env.example`.
- `apps/web/.env.local` — `NEXT_PUBLIC_FIREBASE_*`, `NEXT_PUBLIC_API_BASE_URL`,
  `NEXT_PUBLIC_GOOGLE_WEB_CLIENT_ID`. Template: `apps/web/.env.local.example`.
- `apps/android-app/app/google-services.json` — Firebase config for the
  Android app (project `skolab-vvi`). The web app's Firebase Web app is not
  yet registered in that project — until it is, auth/Firestore calls on web
  fail with an explicit "Firebase is not configured" error, not a silent one.
- `.secrets.baseline` + the `detect-secrets` pre-commit hook guard against
  committing real credentials. Don't hand-edit the baseline to silence a
  finding — regenerate it (`detect-secrets scan`) only if you're sure the
  finding is a false positive.

Never commit `.env`, `.env.local`, or `service-account.json`. All three are
gitignored — keep them that way.

## Conventions

- **Language boundary per concern — keep it that way when adding features.**
  Pure AI/ML logic (LLM calls, embeddings, scoring, recommendation pipelines)
  belongs in `services/backend` (Python) — that's where the model-serving
  infra (`embedding_service.py`, `llm_service.py`) already lives, and it's
  the fastest/most direct path to those libraries. Web app logic (UI, data
  fetching, client state) belongs in `apps/web` (TypeScript/Next.js) — don't
  reimplement web-facing logic in Python or push it through the Go gateway.
  Android logic belongs in `apps/android-app` (Kotlin). The Go gateway
  (`services/backend-go`) is for auth/CORS/proxying/fast-path CRUD, not AI
  logic. When a new feature needs work in more than one of these, split it
  along this boundary rather than picking whichever language is convenient.
- **Never hardcode a specific user/researcher into logic.** Every profile-
  dependent code path (recommendation scoring, feed generation, name
  resolution) must derive everything from the `author_id`/profile passed in
  at request time — no shortcuts assuming "the" test researcher. If you find
  yourself testing with a specific author (there's a recurring one used
  throughout backend development), that's fine for verification, but the
  *code* must not know about them.
- **Verify backend changes live, not just by reading the diff.** `curl` the
  actual endpoint with a browser-like `-A` user agent (bot-detection
  middleware blocks bare `curl`/`requests` UAs) after rebuilding.
- **Caching pattern:** `PgBackedCache` (`app/db/pg_cache.py`) — L1 in-memory,
  L2 Postgres, optional Redis if `REDIS_URL` is set (it isn't, by default;
  Postgres is the real L2 in this deployment). Reuse it for any new cached
  endpoint rather than inventing another caching mechanism.
- **OpenAlex's `/authors?search=` endpoint matches author *display names*,
  not topics/concepts.** Don't pass a concept string to it expecting
  topic-based researcher discovery — it returns nothing or garbage. See
  `decisions/0005-similar-researchers-via-authorship.md`.
- **Frontend math/LaTeX rendering** goes through `MathText`/`MarkdownText`
  (`apps/web/src/components/ui/MathText.tsx`) — any user-facing text that
  might contain `$...$`/`$$...$$` or semantic HTML from OpenAlex titles must
  render through these, not raw `{text}`.
- **Design tokens** come from `shared/skolab-design-system` (compiled via
  `npm run compile:tokens`) — don't hand-roll colors/spacing in either client.
- **Frontend go/no-go check:** `cd apps/web && npx tsc --noEmit` — run it
  after any TypeScript change before considering the change done.

## Windows-specific gotchas

- The historical repo path contained the unicode symbol **π**, which crashes
  Gradle in standard shells. `scripts/build/build-and-install.ps1` works
  around this by copying `apps/android-app` into an ASCII-only temp dir
  before building — always build Android through that script, not raw
  `gradlew`.
- Shell tooling in this environment is Git Bash / PowerShell, not WSL — see
  each tool's own syntax notes if writing scripts.

## Keeping docs in sync

A Stop hook mechanically nudges you when files change without a matching doc
update (dependency manifests vs. this file, decision files vs. their index,
route files vs. `api-contracts/openapi.yaml`, `docker-compose.yml`/Dockerfile
changes vs. this file, env templates vs. this file) — see the reminder if one
fires. But a hook can only check *mtimes*; it can't judge whether a change
was actually significant. That judgment is yours. Use this checklist:

- **Architecturally/scope significant?** If a future reader would benefit
  from knowing the alternatives and reasoning (not just the outcome), write
  a new `decisions/NNNN-title.md` and add its row to `decisions/README.md`'s
  index table. Routine implementation of an already-decided approach, a bug
  fix, or a dependency bump does not need one.
- **Does a new decision make an old one obsolete?** Don't edit the old
  decision file's content — `decisions/` is append-only. Do open the old
  file and change its `Status:` line to `Superseded by NNNN`.
- **Did a stack/dependency/how-it-runs fact change?** (new required env var,
  a service now runs differently, a core library was swapped) Update the
  relevant section of this file (`AGENTS.md`) — not just the manifest.
  Skip this for routine patch/minor version bumps that don't change behavior
  developers need to know about.
- **Did something user-facing ship?** If it's a feature a newcomer reading
  `README.md`'s feature list or architecture section would expect to see
  mentioned, add it there. Internal refactors and backend-only changes don't
  need a README mention.
- **End of session, regardless of the above:** overwrite `HANDOFF.md` with
  the current state (not history — that's what it's for) and append one
  entry to `LOG.md` (never edit a past entry). Do this every session that
  changed anything, not just when the Stop hook reminds you.
- **`PLAN.md` is frozen/historical** — it is never updated to match current
  reality. If something in it turned out differently, that's what
  `decisions/` is for, not an edit to `PLAN.md` itself.

## What not to touch without being asked

- `apps/android-app` Gradle signing config / keystores.
- `infrastructure/` (Prometheus/Grafana/Alertmanager/Cloudflare configs) —
  these back a real alerting setup; changes there have operational blast
  radius beyond this repo.
- `.secrets.baseline` — managed by `detect-secrets`, not hand-edited.
- `docs/postmortems/`, `docs/incidents.json`, `docs/lessons_learned.md` —
  treat as an append-only historical record, not something to rewrite.
- `api-contracts/openapi.yaml` — it's the schema-first contract; changing it
  should be a deliberate act with the API change it describes, not a
  side effect.
