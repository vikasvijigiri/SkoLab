# Deploying SkoLab — free-tier PaaS split

Everything runs on managed hosts. After setup, **no local machine resources are
used** — your laptop only pushes commits and builds the Android APK. Each host
redeploys automatically on push to `main`.

> This chooses a Vercel + Render + Hugging Face split over the Cloudflare-first
> default in `.claude/rules/edge-hosting.md`. Reason: the Python service needs a
> persistent container with >512 MB RAM for `sentence-transformers`, which the
> Workers runtime cannot host. The rule's own escape hatch ("accept Render's
> free tier ... explicitly") applies.

## The stack

| Component | Repo path | Host | Free tier | Sleeps when idle |
|---|---|---|---|---|
| Next.js frontend | `apps/web` | **Vercel** Hobby | yes, no card | no |
| Go API gateway | `services/backend-go` | **Render** free web service | yes, no card | after 15 min |
| Python / ML backend | `services/backend` | **Hugging Face** Docker Space (free CPU) | yes, no card, 16 GB RAM | after 48 h |
| Postgres | — | **Supabase** free | 500 MB | project pauses after 7 days idle |
| Redis (optional) | — | **Upstash** free | 10k cmd/day | n/a |

Cold start after a sleep: gateway ~30 s, Python Space ~40–60 s (model load).
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
1. Supabase (Postgres)      → DATABASE_URL
2. Hugging Face Space        → PYTHON_BACKEND_URL
3. Render (Go gateway)       → gateway URL
4. Vercel (frontend)         → production URL
5. Cross-wire CORS + URLs, redeploy gateway and frontend
```

### 1. Supabase — Postgres

1. `supabase.com` → New project (org's free slot). Pick a region near Render's
   (`oregon` → US West).
2. Project Settings → Database → **Connection string → URI**. Copy the
   `postgresql://postgres:...@...pooler.supabase.com:5432/postgres` form (session
   pooler, port 5432 — not 6543).
3. Run the schema: `services/backend` owns migrations via Alembic
   (`alembic upgrade head` with `DATABASE_URL` set), or apply
   `services/backend-go`'s expected tables. Confirm `list_tables` after.
4. Hold the URI for steps 2 and 3.

### 2. Hugging Face — Python / ML backend

The Space is its own git repo, so push the `services/backend` subtree to it.

1. `huggingface.co` → New Space → **Docker** SDK → blank → free CPU hardware.
   Name it `skolab-backend`. Note its git URL:
   `https://huggingface.co/spaces/<you>/skolab-backend`.
2. The Space needs the Dockerfile and app at **its repo root**. From this repo:

   ```bash
   git remote add hf https://huggingface.co/spaces/<you>/skolab-backend
   git subtree push --prefix=services/backend hf main
   ```

   `services/backend/README.md` already carries the required Space metadata
   (`sdk: docker`, `app_port: 8000`).
3. Space → Settings → **Variables and secrets**:

   | Key | Value |
   |---|---|
   | `DATABASE_URL` | the Supabase URI from step 1 (swap scheme to `postgresql+asyncpg://` — the app uses asyncpg) |
   | `GROQ_API` | your Groq key |
   | `SENTRY_DSN` | `https://7f3234a6dd311681fb026b919d4dfb69@o4512014875426816.ingest.de.sentry.io/4512016181297232` |
   | `FIREBASE_CREDENTIALS_JSON` or the SDK's expected var | paste the service-account JSON as a **secret** if `firebase-admin` init needs it; otherwise omit |
   | `APP_ENV` | `production` |

4. Wait for the build (first one is slow — it pre-downloads the embedding
   model). Verify `https://<you>-skolab-backend.hf.space/livez` returns 200.
5. That base URL is `PYTHON_BACKEND_URL` for step 3.

### 3. Render — Go API gateway

1. `render.com` → New → **Blueprint** → connect this repo. Render reads
   `render.yaml` and creates `skolab-gateway`.
2. Fill the prompted `sync: false` vars:

   | Key | Value |
   |---|---|
   | `PYTHON_BACKEND_URL` | the HF Space URL from step 2 |
   | `DATABASE_URL` | Supabase URI, `postgresql://...?sslmode=require` |
   | `CORS_ORIGINS` | leave blank for now; set in step 5 |
   | `REDIS_URL` | leave blank (memory-only) unless you set up Upstash |

3. Dashboard → the service → **Secret Files** → add `service-account.json`
   with the Firebase service-account JSON. `render.yaml` already points
   `GOOGLE_APPLICATION_CREDENTIALS` at `/etc/secrets/service-account.json`.
4. Deploy. Verify `https://skolab-gateway.onrender.com/gateway-health` is 200.
5. That URL (`/`) is `NEXT_PUBLIC_API_BASE_URL` for step 4.

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
