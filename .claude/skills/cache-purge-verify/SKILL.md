---
name: cache-purge-verify
description: Purge or invalidate multi-layer Postgres (PgBackedCache) and Firestore recommendation caches to verify live backend changes without serving stale cached results. Use whenever testing recommendations, daily feed algorithms, or profile metrics after backend code edits. Do NOT use for endpoints that aren't cached, or as a substitute for `backend-rebuild-verify` when the container image itself is stale.
---

# Cache Purging & Invalidation Skill

Per [AGENTS.md](file:///c:/Users/VikasVijigiri/Documents/SkoLab/AGENTS.md), backend recommendation endpoints are cached across **two layers**:
- **L1/L2 Postgres Cache:** `PgBackedCache` (1h TTL, stored in table `cache_entries`).
- **Firestore Cache Layer:** Firestore document layer with ~1h staleness check.

## Purge Strategies

### Strategy A: Target Row Invalidation via Postgres CLI (Recommended)
Delete specific cached key from `cache_entries` without flushing the entire database:

```bash
docker exec skolab_postgres psql -U postgres -d skolab -c "DELETE FROM cache_entries WHERE key LIKE '%<author_id_or_cache_key>%';"
```

### Strategy B: Daily-Feed Dismiss Endpoint Trick
Dismissing an item whose ID is in the currently cached result forces an immediate, full recompute bypassing both cache layers:

```bash
curl -X POST -H "Content-Type: application/json" \
  -d '{"dismissed_id": "<item_id>"}' \
  http://localhost:8000/api/v1/feed/dismiss
```

## Verification Step
After purging, verify with a live request passing a browser-like `User-Agent`:

```bash
curl -A "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36" \
  http://localhost:8000/<endpoint>
```

## Routing

- Mandatory validator: none beyond the post-purge live request — that
  response is the proof the cache no longer serves the stale value.
- Preceded by: `backend-rebuild-verify` if the code itself was also changed
  and the container hasn't been rebuilt yet.
- Terminal handoff: none. Report what the purge and re-request showed.

## Success

The relevant cache row(s) were purged (or the dismiss-endpoint trick was
used), and the follow-up live request shows the fresh, non-cached result.
