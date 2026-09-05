# Deploying SkoLab — free-tier PaaS split

Everything runs on managed hosts. After setup, **no local machine resources are
used** — your laptop only pushes commits and builds the Android APK. Each host
redeploys automatically on push to `main`.

> **2026-09 update — the Python backend moved from Hugging Face to Render.**
> Hugging Face put Docker Spaces behind a paid plan. The Python service now
> deploys via the **same `render.yaml` Blueprint** as the gateway. It fits
> Render's free 512 MB tier because embeddings run via the **Hugging Face
> Inference API** instead of a bundled PyTorch model — see
> `services/backend/app/services/ai/embedding_service.py`. `render.yaml` is the
> source of truth for env vars; where a table below still says "HF Space",
> read "the `skolab-backend-py` Render service".

> **2026-09 update — the frontend moved from Vercel to Render.** The original
> choice (documented below for the record) was Next.js's own first-party host,
> whose CDN-served static pages have no cold start at all — a real advantage
> over Render's free tier, which sleeps after 15 minutes idle. It moved
> because Render was already fully wired for this project with zero friction,
> and `next start`'s standalone output is just a persistent Node process —
> exactly what a Render web service wants (this is precisely why Cloudflare
> Workers was never an option for the *backend*: no persistent process at
> all). The tradeoff is the same cold start the other two services already
> accept. `render.yaml` now defines all three services in one Blueprint.
>
> This chooses Render over the Cloudflare-first default in
> `.claude/rules/edge-hosting.md` for the same reason as the Python backend:
> the rule's own escape hatch ("accept Render's free tier ... explicitly")
> applies, now to all three services rather than just the two backends.

## The stack

| Component | Repo path | Host | Free tier | Sleeps when idle |
|---|---|---|---|---|
| Next.js frontend | `apps/web` | **Render** free web service (same Blueprint) | yes, no card | after 15 min |
| Go API gateway | `services/backend-go` | **Render** free web service | yes, no card | after 15 min |
| Python / FastAPI backend | `services/backend` | **Render** free web service (same Blueprint) | yes, no card, 512 MB | after 15 min |
| Embeddings (BAAI/bge-small-en-v1.5) | — | **Hugging Face Inference API** | yes, rate-limited | n/a |
| Postgres | — | **Supabase** free | 500 MB | project pauses after 7 days idle |
| Redis | — | **Render Key Value** free (`skolab-cache`) | yes, no card, 1 per workspace | in-memory only, no persistence |

Cold start after a sleep: each Render service ~30–60 s. A scheduled uptime
check (`.github/workflows/uptime-monitor.yml`, every 30 min) surfaces a real
outage without fighting the sleep policy — see "Keeping things warm" below
for why that distinction matters.

## Before you start

| Need | Where to get it | Secret? |
|---|---|---|
| GitHub repo pushed | already at `github.com/vikasvijigiri/SkoLab` | — |
| Firebase **service account** JSON | Firebase console → Project settings → Service accounts → Generate new private key | **yes — never paste in chat or commit** |
| `GROQ_API` key | `console.groq.com` → API Keys | yes |
| Sentry DSNs | already created (`skolab-web`, `skolab-backend` in org `vikas-1k`) | public-ish |
| Firebase web config | already in `apps/web/.env.local` and inlined as `render.yaml` build-time values | public |

## Order of operations

Build back-to-front so each layer's URL exists when the next one needs it —
though with all three services in one Blueprint and `skolab-web`'s env vars
pre-filled with the other two services' predictable `*.onrender.com` URLs
(see `render.yaml`), steps 2–4 below now happen in a single Blueprint create:

```text
1. Supabase (Postgres)                  → DATABASE_URL
2. Render Blueprint (all three services) → gateway URL + python URL + web URL
3. Set the gateway's PYTHON_BACKEND_URL to the python service URL
4. Cross-wire Firebase authorized domains + the Google API key referrer
```

### 1. Supabase — Postgres

1. `supabase.com` → New project (org's free slot). Pick a region near Render's
   (`oregon` → US West). A cross-Pacific region adds ~200 ms to every query —
   see the audit doc's note.
2. Project Settings → Database → **Connection string → URI**. Copy the
   **transaction pooler** form —
   `postgresql://postgres.<ref>:...@...pooler.supabase.com:6543/postgres`
   (port **6543**). Both services are already configured for pgBouncer
   transaction mode (asyncpg `statement_cache_size=0`, pgx `QueryExecModeExec`);
   the direct `:5432` connection blows the free tier's ~60-connection ceiling
   under load.
   - Python wants the `postgresql+asyncpg://` scheme; the Go gateway wants
     plain `postgres://...?sslmode=require`.
3. Schema bootstrap: the Alembic migrations **cannot** build an empty database
   (the "initial" migration only renames auto-created indexes). Instead set
   `RUN_DB_CREATE_ALL=1` on the `skolab-backend-py` service for its **first**
   deploy — the app runs `Base.metadata.create_all`. Blank it once `/livez` is
   green. Optionally `python -m alembic stamp head` afterwards so future
   migrations track. (If your DB password has a `%`, double it for Alembic's
   configparser: `%40` → `%%40`.)
4. Hold the URI for step 2.

### 2. Render — all three services, one Blueprint

1. `render.com` → **New → Blueprint** → connect this repo. Render reads
   `render.yaml` and creates **three** services: `skolab-gateway` (Go),
   `skolab-backend-py` (Python/FastAPI), and `skolab-web` (Next.js).
2. It prompts for every `sync: false` value. For `skolab-backend-py`:

   | Key | Value |
   |---|---|
   | `DATABASE_URL` | Supabase transaction-pooler URI, `postgresql+asyncpg://` scheme, port `6543` |
   | `DATABASE_ENCRYPTION_KEY` | `python -c "import base64,os;print(base64.b64encode(os.urandom(32)).decode())"` — **required**, the app refuses to boot without it in production |
   | `GROQ_API` | your Groq key |
   | `SRE_SECURITY_TOKEN` | any random string |
   | `HF_INFERENCE_TOKEN` | a Hugging Face token (read scope) — enables the embedding backend; unset ⇒ match scores floor but nothing 500s |
   | `SENTRY_DSN` | `https://7f3234a6dd311681fb026b919d4dfb69@o4512014875426816.ingest.de.sentry.io/4512016181297232` |
   | `APP_BASE_URL` | `https://skolab-backend-py.onrender.com` |
   | `RUN_DB_CREATE_ALL` | `1` for the first deploy only (see step 1.3), then blank |
   | `GOOGLE_APPLICATION_CREDENTIALS` | optional — `/etc/secrets/service-account.json` if you upload the Firebase JSON as a Secret File; only `/agent/chat` needs it |

   (`skolab-gateway`'s prompts are unchanged — see step 3.)
3. Wait for both builds. Verify:
   `https://skolab-backend-py.onrender.com/livez` → `{"status":"alive"}`.
4. Once green, remove `RUN_DB_CREATE_ALL` from `skolab-backend-py`.

### 3. Render — wire the gateway to the Python service, and check the frontend

1. `skolab-gateway` was created by the same Blueprint above. Fill the
   prompted `sync: false` vars:

   | Key | Value |
   |---|---|
   | `PYTHON_BACKEND_URL` | leave blank during Blueprint create; set it to `https://skolab-backend-py.onrender.com` **after** that service is live (Render `fromService` can't supply the `https://` scheme the gateway needs) |
   | `DATABASE_URL` | Supabase transaction-pooler URI, plain `postgres://...` (append `?sslmode=require` if the gateway logs an SSL error) |

   `CORS_ORIGINS` is no longer a prompt — `render.yaml` sets it directly to
   `skolab-web`'s own predictable Render URL, since both services are created
   by the same Blueprint at once. `REDIS_URL` likewise points at the Render
   Key Value instance created for this project (`skolab-cache`) rather than
   Upstash — see "Redis" below.
2. Dashboard → `skolab-gateway` → **Secret Files** → add `service-account.json`
   with the Firebase service-account JSON. `render.yaml` already points
   `GOOGLE_APPLICATION_CREDENTIALS` at `/etc/secrets/service-account.json`.
3. Deploy. Verify `https://skolab-gateway.onrender.com/gateway-health` is 200,
   then set `PYTHON_BACKEND_URL` (it redeploys) and check
   `https://skolab-gateway.onrender.com/livez` flips 502 → 200.
4. `skolab-web` builds from the same Blueprint, with `NEXT_PUBLIC_API_BASE_URL`
   already pointed at `skolab-gateway`'s URL — no separate host, no manual
   env var copy. Verify `https://skolab-web.onrender.com` loads.

### 4. Cross-wire Firebase and the Google API key

The one manual step left, since neither has a public API this project has
credentials for:

1. **Firebase console** → Authentication → Settings → **Authorized domains** →
   add `skolab-web.onrender.com`, so Google/Firebase sign-in popups work.
2. **Google Cloud console** → APIs & Services → Credentials → the browser API
   key → if it has an "HTTP referrers" restriction, add
   `https://skolab-web.onrender.com/*`.
3. Smoke test: load `https://skolab-web.onrender.com`, sign in, hit a page
   that calls the gateway, confirm a request in the Network tab returns from
   `skolab-gateway.onrender.com` and the gateway proxies `/api/...` ML calls
   through to `skolab-backend-py`.

## Environment variables by host

| Var | Render (web) | Render (gateway) | Render (python) |
|---|:--:|:--:|:--:|
| `NEXT_PUBLIC_API_BASE_URL` | ✅ → gateway URL | — | — |
| `NEXT_PUBLIC_FIREBASE_*` | ✅ (5 keys) | — | — |
| `NEXT_PUBLIC_GOOGLE_WEB_CLIENT_ID` | ✅ | — | — |
| `NEXT_PUBLIC_SENTRY_DSN` | ✅ web DSN | — | — |
| `NEXT_PUBLIC_SITE_URL` | ✅ → own URL | — | — |
| `PORT` | ✅ `3000` | ✅ `8080` | ✅ `8000` |
| `GIN_MODE` | — | ✅ `release` | — |
| `PYTHON_BACKEND_URL` | — | ✅ → python URL | — |
| `CORS_ORIGINS` | — | ✅ → web URL | — |
| `DATABASE_URL` | — | ✅ | ✅ (`+asyncpg`) |
| `REDIS_URL` | — | ✅ → `skolab-cache` | optional |
| `GOOGLE_APPLICATION_CREDENTIALS` | — | ✅ secret file path | — |
| `GROQ_API` | — | — | ✅ |
| `SENTRY_DSN` | — | — | ✅ backend DSN |
| `APP_ENV` | — | — | ✅ `production` |

### Scale / latency tunables (optional — defaults are safe)

All have working defaults; set them when the free-tier hosts are the
constraint. Full rationale in `docs/plans/2026-09-02-scale-latency-audit.md`.

| Var | Host | Default | When to change |
|---|---|---|---|
| `WEB_CONCURRENCY` | HF Space | `1` | `2`+ once the Space has the RAM (each worker = its own ~130 MB model copy) |
| `DB_POOL_SIZE` / `DB_MAX_OVERFLOW` / `DB_POOL_TIMEOUT_SECONDS` | HF Space | `5` / `10` / `10` | lower if `WEB_CONCURRENCY` × (size+overflow) nears the pooler's ~60 |
| `DB_MAX_CONNS` / `DB_MIN_CONNS` | Render | `15` / `3` | same ceiling, from the Go side |
| `EMBED_MAX_CONCURRENCY` | HF Space | `0` (= core count) | pin lower if embedding starves request serving |
| `EMBED_VECTOR_CACHE_TTL_SECONDS` | HF Space | `2592000` (30 d) | rarely — text→vector is deterministic per model |
| `LLM_MAX_FALLBACK_MODELS` / `LLM_TOTAL_DEADLINE_SECONDS` | HF Space | `4` / `90` | raise the deadline only if long 70B generations are being cut off |
| `RUN_DB_CREATE_ALL` | HF Space | off when `APP_ENV=production` | `1` only to let the app bootstrap its own schema (skips Alembic) |

## Optional

### Redis

Already set up: **Render Key Value** (`skolab-cache`, free plan, `oregon`,
one per workspace) rather than a separate Upstash account — same vendor
already in use for everything else, zero new signup. Only needed if the
gateway runs more than one instance and must share pub/sub state; without it,
`internal/pubsub/redis.go` logs a warning and uses memory-only mode — fine
for a single free instance. No data persistence on the free plan (a restart
loses it) — the app already tolerates this by design, since Postgres is the
L2 cache fallback whenever Redis isn't reachable (`app/db/pg_cache.py`).

### Teach the gateway to read `PORT` (removes the `PORT=8080` workaround)

`services/backend-go/main.go:117` hardcodes `addr := ":8080"`. A 2-line change
to read `PORT` with an `:8080` default makes it portable across any PaaS. Not
required — setting `PORT=8080` in `render.yaml` already works.

### Keeping things warm — and the difference between that and monitoring

Free instances sleep. Do **not** add a cron that pings them just to defeat
the sleep — several free tiers call that a fair-use violation. Either accept
the cold start, or move the always-on piece to a paid instance when it
matters.

`.github/workflows/uptime-monitor.yml` is not that: it checks all three
services every 30 minutes (wider than the 15-minute idle window, so it does
not keep them perpetually warm) and only fails loudly — a red X in the
Actions tab, plus GitHub's default email notification — when a target stays
unreachable across five retries (~100 s), which a normal cold start clears
easily. It is disclosed, not silent, and it is a monitor, not a keep-alive.

## What this does not deploy

- `infrastructure/` (Prometheus, Grafana, Loki, Alertmanager, uptime-kuma) —
  self-hosted observability. On this split, Sentry covers errors, the Go
  gateway's own `GET /metrics` covers RED metrics, and
  `uptime-monitor.yml` covers availability. Run
  `infrastructure/docker-compose.yml` locally or on a VM if you want the
  full self-hosted stack instead.
- `apps/android-app` — ships through Play Console / EAS, not a web host.
