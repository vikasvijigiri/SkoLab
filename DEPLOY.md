# Deploying SkoLab — free-tier PaaS split

Everything runs on managed hosts. After setup, **no local machine resources are
used** — your laptop only pushes commits and builds the Android APK. Each host
redeploys automatically on push to `main`.

> **2026-09 update — the Python backend moved from Hugging Face to Render.**
> Hugging Face put Docker Spaces behind a paid plan. The Python service now
> deploys via the **same `render.yaml` Blueprint** as the gateway (it defines
> two services: `skolab-gateway` and `skolab-backend-py`). It fits Render's
> free 512 MB tier because embeddings run via the **Hugging Face Inference
> API** instead of a bundled PyTorch model — see
> `services/backend/app/services/ai/embedding_service.py`. `render.yaml` is the
> source of truth for env vars; where a table below still says "HF Space",
> read "the `skolab-backend-py` Render service".

> This chooses a Vercel + Render split over the Cloudflare-first default in
> `.claude/rules/edge-hosting.md`. Reason: FastAPI on the Workers runtime is a
> non-starter (no persistent Python process). The rule's own escape hatch
> ("accept Render's free tier ... explicitly") applies.

## The stack

| Component | Repo path | Host | Free tier | Sleeps when idle |
|---|---|---|---|---|
| Next.js frontend | `apps/web` | **Vercel** Hobby | yes, no card | no |
| Go API gateway | `services/backend-go` | **Render** free web service | yes, no card | after 15 min |
| Python / FastAPI backend | `services/backend` | **Render** free web service (same Blueprint) | yes, no card, 512 MB | after 15 min |
| Embeddings (BAAI/bge-small-en-v1.5) | — | **Hugging Face Inference API** | yes, rate-limited | n/a |
| Postgres | — | **Supabase** free | 500 MB | project pauses after 7 days idle |
| Redis (optional) | — | **Upstash** free | 10k cmd/day | n/a |

Cold start after a sleep: each Render service ~30–60 s.
Acceptable for a demo; see "Keeping things warm" below.

## Before you start

| Need | Where to get it | Secret? |
|---|---|---|
| GitHub repo pushed | already at `github.com/vikasvijigiri/SkoLab` | — |
| Firebase **service account** JSON | Firebase console → Project settings → Service accounts → Generate new private key | **yes — never paste in chat or commit** |
| `GROQ_API` key | `console.groq.com` → API Keys | yes |
| Sentry DSNs | already created (`skolab-web`, `skolab-backend` in org `vikas-1k`) | public-ish |
| Firebase web config | already in `apps/web/.env.local` | public |

## Order of operations

Build back-to-front so each layer's URL exists when the next one needs it:

```text
1. Supabase (Postgres)                 → DATABASE_URL
2. Render Blueprint (both backends)    → gateway URL + python URL
3. Set the gateway's PYTHON_BACKEND_URL to the python service URL
4. Vercel (frontend)                   → production URL
5. Cross-wire CORS + URLs, redeploy gateway and frontend
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

### 2. Render — both backends, one Blueprint

1. `render.com` → **New → Blueprint** → connect this repo. Render reads
   `render.yaml` and creates **two** services: `skolab-gateway` (Go) and
   `skolab-backend-py` (Python/FastAPI).
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

### 3. Render — wire the gateway to the Python service

1. `skolab-gateway` was created by the same Blueprint above. Its prompts:
2. Fill the prompted `sync: false` vars:

   | Key | Value |
   |---|---|
   | `PYTHON_BACKEND_URL` | leave blank during Blueprint create; set it to `https://skolab-backend-py.onrender.com` **after** that service is live (Render `fromService` can't supply the `https://` scheme the gateway needs) |
   | `DATABASE_URL` | Supabase transaction-pooler URI, plain `postgres://...` (append `?sslmode=require` if the gateway logs an SSL error) |
   | `CORS_ORIGINS` | `http://localhost:3000` for now; add the Vercel URL in step 5 |
   | `REDIS_URL` | leave blank (memory-only) unless you set up Upstash |

3. Dashboard → `skolab-gateway` → **Secret Files** → add `service-account.json`
   with the Firebase service-account JSON. `render.yaml` already points
   `GOOGLE_APPLICATION_CREDENTIALS` at `/etc/secrets/service-account.json`.
4. Deploy. Verify `https://skolab-gateway.onrender.com/gateway-health` is 200,
   then set `PYTHON_BACKEND_URL` (it redeploys) and check
   `https://skolab-gateway.onrender.com/livez` flips 502 → 200.
5. `https://skolab-gateway.onrender.com` is `NEXT_PUBLIC_API_BASE_URL` for step 4.

### 4. Vercel — Next.js frontend

1. `vercel.com` → Add New → Project → import the repo.
2. **Root Directory: `apps/web`** (critical — it's an npm workspace member).
   Framework preset auto-detects as Next.js. Leave build/output at defaults.
3. Environment Variables — copy every line from `apps/web/.env.local`, but set
   `NEXT_PUBLIC_API_BASE_URL` to the Render gateway URL from step 3:

   | Key | Value |
   |---|---|
   | `NEXT_PUBLIC_API_BASE_URL` | `https://skolab-gateway.onrender.com` |
   | `NEXT_PUBLIC_FIREBASE_API_KEY` | `AIzaSyA9E9Z3zFR-WPnNlFuZ80aWey3bIXPLk84` |
   | `NEXT_PUBLIC_FIREBASE_AUTH_DOMAIN` | `skolab-vvi.firebaseapp.com` |
   | `NEXT_PUBLIC_FIREBASE_PROJECT_ID` | `skolab-vvi` |
   | `NEXT_PUBLIC_FIREBASE_STORAGE_BUCKET` | `skolab-vvi.firebasestorage.app` |
   | `NEXT_PUBLIC_FIREBASE_APP_ID` | `1:412488544680:web:5ec939773d50b1b933ad9d` |
   | `NEXT_PUBLIC_GOOGLE_WEB_CLIENT_ID` | `412488544680-jr969qv6a5aih569rmd8l8egjetl9lrg.apps.googleusercontent.com` |
   | `NEXT_PUBLIC_SENTRY_DSN` | `https://12e2c847ce6e46d052cb7d9cd87c7f22@o4512014875426816.ingest.de.sentry.io/4512016181755984` |

4. Deploy. Note the production URL (e.g. `https://skolab.vercel.app`).

### 5. Cross-wire and redeploy

1. **Render** → `skolab-gateway` → Environment → set `CORS_ORIGINS` to the exact
   Vercel production URL (no trailing slash, comma-separate if more than one).
   Save → it redeploys.
2. **Firebase console** → Authentication → Settings → **Authorized domains** →
   add the Vercel domain, so Google/Firebase sign-in popups work.
3. **Google Cloud console** → APIs & Services → Credentials → the browser API
   key → if it has an "HTTP referrers" restriction, add
   `https://skolab.vercel.app/*`.
4. Smoke test: load the Vercel URL, sign in, hit a page that calls the gateway,
   confirm a request in the Network tab returns from `onrender.com` and the
   gateway proxies `/api/...` ML calls through to the HF Space.

## Environment variables by host

| Var | Vercel | Render (gateway) | HF Space (python) |
|---|:--:|:--:|:--:|
| `NEXT_PUBLIC_API_BASE_URL` | ✅ → Render URL | — | — |
| `NEXT_PUBLIC_FIREBASE_*` | ✅ (5 keys) | — | — |
| `NEXT_PUBLIC_GOOGLE_WEB_CLIENT_ID` | ✅ | — | — |
| `NEXT_PUBLIC_SENTRY_DSN` | ✅ web DSN | — | — |
| `PORT` | — | ✅ `8080` | — (Space sets it; Dockerfile CMD is fixed 8000 via `app_port`) |
| `GIN_MODE` | — | ✅ `release` | — |
| `PYTHON_BACKEND_URL` | — | ✅ → HF Space URL | — |
| `CORS_ORIGINS` | — | ✅ → Vercel URL | — |
| `DATABASE_URL` | — | ✅ | ✅ (`+asyncpg`) |
| `REDIS_URL` | — | optional | optional |
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

### Upstash Redis

Only needed if the gateway runs more than one instance and must share pub/sub
state. `upstash.com` → Redis → create → copy the `redis://` URL into Render's
`REDIS_URL`. Without it, `internal/pubsub/redis.go` logs a warning and uses
memory-only mode — fine for a single free instance.

### Teach the gateway to read `PORT` (removes the `PORT=8080` workaround)

`services/backend-go/main.go:117` hardcodes `addr := ":8080"`. A 2-line change
to read `PORT` with an `:8080` default makes it portable across any PaaS. Not
required — setting `PORT=8080` in `render.yaml` already works.

### Keeping things warm

Free instances sleep. Do **not** add a cron that pings them just to defeat the
sleep — several free tiers call that a fair-use violation. Either accept the
cold start, or move the always-on piece to a paid instance when it matters.

## What this does not deploy

- `infrastructure/` (Prometheus, Grafana, Loki, Alertmanager, uptime-kuma) —
  self-hosted observability. On this split, Sentry covers errors; Render and
  Vercel dashboards cover the rest. Run `infrastructure/docker-compose.yml`
  locally or on a VM if you want the full stack.
- `apps/android-app` — ships through Play Console / EAS, not a web host.
