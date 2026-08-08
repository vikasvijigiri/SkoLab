---
name: backend-rebuild-verify
description: Rebuild the skolab_python_ai docker container after a services/backend (Python) change and verify the fix with a live request. Use whenever you've edited services/backend/** and need the change to take effect in the docker-compose-run backend rather than the uvicorn --reload dev server — the single most common "fixed it but the bug is still there" confusion here. Do NOT use if the dev server is what's running, or to run the lint/test gates (`backend-test-suite`).
---

# Backend rebuild + verify

This repo's Python backend runs two different ways (see AGENTS.md):

1. `npm run dev:backend` / `uvicorn --reload` — hot-reloads automatically.
2. `services/backend/docker-compose.yml` (container `skolab_python_ai`) — a
   **static image**. Code changes do NOT take effect until rebuilt.

Before using this skill, check which mode is actually running with
`docker ps --filter name=skolab_python_ai`. If that container isn't up, the
dev server is hot-reloading already and this skill is unnecessary.

## Steps

1. From `services/backend`, rebuild and restart only the `web` service
   (leave `db`/`gateway` untouched):
   ```bash
   cd services/backend
   docker compose build web
   docker compose up -d --no-deps web
   ```
2. Wait for the container to report healthy/listening — poll until the API
   responds:
   ```bash
   for i in 1 2 3 4 5 6 7 8; do
     code=$(curl -s -o /dev/null -w "%{http_code}" http://localhost:8000/docs)
     [ "$code" != "000" ] && break
     sleep 2
   done
   ```
3. Verify the actual fix with a **live request**, not just a rebuild. Bot
   detection middleware blocks bare `curl`/`requests` user agents — always
   pass a browser-like `-A`:
   ```bash
   curl -A "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36" \
     http://localhost:8000/<the endpoint you changed>
   ```
   Confirm the response reflects the code change (not a stale cached value —
   see the cache-invalidation gotcha in AGENTS.md: `PgBackedCache` L1/L2 plus
   a Firestore layer each have ~1h TTLs; a fresh recompute may still need the
   daily-feed dismiss-endpoint trick to bypass both).
4. Report what you verified (endpoint, request, and the specific part of the
   response that proves the fix), not just "rebuild succeeded."

## Routing

- Mandatory validator: none beyond the live request in step 3 — that request
  is the proof, not the rebuild exit code.
- Preceded by: the code change itself; use `cache-purge-verify` first if the
  endpoint is one of the cached recommendation/feed paths, or the live
  request may still show stale data.
- Terminal handoff: none. Report what the live request proved.

## Success

`docker compose up -d --no-deps web` came back healthy, a live request (with
the browser-like `-A`) was made against the changed endpoint, and the
response — not just the rebuild — was confirmed to reflect the fix.
