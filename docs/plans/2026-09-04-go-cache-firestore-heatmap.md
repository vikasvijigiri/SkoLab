# Go `cache_entries` client + Firestore wrapper + `citation_heatmap` → Go

**Slug:** go-cache-firestore-heatmap
**Branch:** `feat/go-cache-firestore-heatmap` (base `origin/main` = `02001fe`)
**Status:** implemented 2026-09-04. Go code verified by the `SkoLab CI Pipeline /
build-and-test` job (`go build` + `go vet` + `go test -race`) — no Go toolchain on
this box.
**Roadmap row:** unblocks the author/feed → Go migration
(`docs/plans/2026-09-03-python-llm-only-phase0.md`). "Every non-LLM concern leaves
Python."

## Goal

Three things, in order, because each is the next one's prerequisite:

1. **Part 1 — Go `cache_entries` parity client.** Python's `PgBackedCache`
   (`services/backend/app/db/pg_cache.py`) is the two-tier cache nearly every
   author/feed endpoint reads or writes. Nothing else moves until Go can read and
   write the same rows.
2. **Part 2 — Firestore from Go.** A thin, nil-safe wrapper over the Firestore
   client the Firebase Admin SDK already ships, so ported endpoints keep their
   Firestore mirror tier.
3. **Part 3 — `GET /citation_heatmap` → Go.** The only author route with no LLM
   and no embedding. First real consumer of Parts 1 + 2.

## Part 1 — `internal/cache/pgcache.go`

Parity port of `PgBackedCache`'s **L2 Postgres tier only**.

| Aspect | Python (`pg_cache.py`) | Go (`pgcache.go`) |
| :--- | :--- | :--- |
| Table | `cache_entries` | same |
| Key namespacing | `f"{name}::{key}"` | `name + "::" + key` — identical |
| Value envelope | `{"v": <normalized value>}` | `{"v": <value>}` via `json.Marshal` |
| Get | `SELECT ... WHERE cache_key=$1 AND expires_at > now()`, then `entry.data["v"]` | same SQL, unwrap `{"v":...}`, return inner **raw JSON** (`json.RawMessage`) |
| Set | `pg_insert(...).on_conflict_do_update(index=[cache_key], set_={data,last_synced,expires_at})` | `INSERT ... ON CONFLICT (cache_key) DO UPDATE SET data=EXCLUDED.data, last_synced=EXCLUDED.last_synced, expires_at=EXCLUDED.expires_at` — same shape |
| `last_synced` | `datetime.now(utc).replace(tzinfo=None)` | `now() at time zone 'utc'` (naive UTC — matches Python's stored values regardless of DB session TZ) |
| `expires_at` | `now + timedelta(seconds=ttl)` | `(now() at time zone 'utc') + make_interval(secs => $3)` |
| Delete | `sa_delete(CacheEntry).where(cache_key == db_key)` | `DELETE FROM cache_entries WHERE cache_key = $1` |
| Clear | `DELETE FROM cache_entries WHERE cache_key LIKE :prefix` (`"<name>::%"`) | identical |
| Redis L2 | preferred when `REDIS_URL` set | **skipped** — `REDIS_URL` unused in this deploy. `// TODO: Redis L2` note in the file. |
| L1 in-memory | 30 s dedupe dict | **not ported** — the existing `internal/cache.Cache` already covers per-handler in-memory TTL; each handler composes the two tiers itself (heatmap does). |

**API shape.** The brief lists `Get(ctx, name, key)` / `Set(ctx, name, key, value,
ttl)` / `Delete(ctx, name, key)` / `Clear(ctx, name)`. Implemented as package-level
functions with exactly that signature (`PgGet` / `PgSet` / `PgDelete` / `PgClear`)
so `name` is an explicit argument, not hidden constructor state — Python constructs
`PgBackedCache(name=...)` per call site anyway (`base.py::_save_to_postgres`).

**`db.Pool == nil` safety.** Every function short-circuits: `PgGet` → `(nil,
false)`, the writers → `nil`. Mirrors Python swallowing every `cache_entries`
error and returning `None` / silently continuing.

**Column types.** `cache_entries.data` is `JSON` (not `JSONB`); `cache_key` is
`VARCHAR(512) UNIQUE` (so `ON CONFLICT (cache_key)` resolves); `last_synced` /
`expires_at` are `TIMESTAMP WITHOUT TIME ZONE`. The envelope is passed as `text`
with an explicit `$2::json` cast and TTL seconds as `float8` into `make_interval`.

**Tests** (`pgcache_test.go`, no DB needed):
- `db.Pool == nil` path for each of the four functions.
- `pgKey` prefixing == `"<name>::<key>"`.
- Envelope round-trip: `wrapEnvelope(v)` → bytes → `unwrapEnvelope` → the inner
  value's raw JSON, for scalars, maps, slices, and nil.

## Part 2 — `internal/firestore/client.go` — **LANDED**

Wiring a real Firestore client from Go was **well under the ~1 hour budget** and
needed **no new credentials**: `cloud.google.com/go/firestore` is already an
indirect dependency (pulled by `firebase.google.com/go/v4`), and the client reads
the same Application Default Credentials the Firebase Auth path already uses.

| Function | Behaviour |
| :--- | :--- |
| `Init()` | Builds a `firebase.NewApp` (same ambient ADC as `auth.InitFirebase`) and calls `app.Firestore(ctx)`. Any failure → log at WARNING, leave the package client `nil`. Never fatal. |
| `Available() bool` | `client != nil`. |
| `GetDoc(ctx, collection, docID) (map[string]any, bool, error)` | `nil` client → `(nil, false, nil)`. `NotFound` → `(nil, false, nil)`. Hit → `(snap.Data(), true, nil)`. |
| `SetDoc(ctx, collection, docID, data) error` | `nil` client → `nil` (no-op). Else `Doc().Set(ctx, data)`. |
| `ServerTimestamp` | Re-exported `firestore.ServerTimestamp` sentinel so callers don't import the SDK directly. |

**Degradation parity.** Python's `_PipelineBase._get_firestore_db()` returns
`None` when `FIRESTORE_AVAILABLE` is false and every `_firestore_*_safe` wrapper
then no-ops; `researcher_worker` does the same. The Go wrapper's `nil`-client
branches are the exact same contract.

**[NOTE — minor deviation, not blocking]** The brief says "that same app gives
`app.Firestore(ctx)` — use it". The `firebase.App` built in
`internal/auth/firebase.go` is a package-local variable and `peers-hardauth` is
concurrently editing that same file. Rather than export it (a guaranteed
merge-conflict on `firebase.go` for no behavioural gain), `firestore.Init()` calls
`firebase.NewApp` itself. Same SDK, same `nil`-credential ambient path, **no
separate GCP SDK and no separate creds path** — which is the constraint the brief
actually names. `firebase.NewApp` with no ADC returns an error and the wrapper
degrades to no-op, so CI (no `GOOGLE_APPLICATION_CREDENTIALS` on the Go test step)
stays green.

**Tests** (`client_test.go`): `nil`-client `GetDoc` → `(nil,false,nil)`;
`nil`-client `SetDoc` → `nil`; `Available()` false before `Init`.

## Part 3 — `GET /citation_heatmap` → `internal/author/heatmap.go`

Port of `app/services/platform/pipeline/heatmap.py::HeatmapMixin.get_citation_heatmap`.

### Tier order (unchanged from Python)

1. **Postgres `cache_entries`** — `PgGet("pipeline", "citation_heatmap_<cleanID>")`.
   Hit → return.
2. **Firestore `citation_heatmaps/<cleanID>`** — hit → drop `last_synced`, warm
   the Postgres tier, return.
3. **Compute** from OpenAlex `counts_by_year` (see below), write Postgres, mirror
   to Firestore with `last_synced = ServerTimestamp`.

### Compute

| Step | Python | Go |
| :--- | :--- | :--- |
| Source | `_fetch_author_profile` → `openalex_service.fetch_author_by_id` → `profile["counts_by_year"]` | `openalex.FetchAuthorCountsByYear(ctx, id)` — one `/authors/{id}` GET via the shared circuit-broken client, returns `counts_by_year` + `summary_stats.h_index` |
| Sort | `sorted(..., key=year)` | `sort.Slice` by year asc |
| Window | last 8 entries | `counts[len-8:]` |
| `years` / `citations` / `works` | `x["year"]` / `x["cited_by_count"]` / `x["works_count"]` | identical field reads |
| `h_index` | `int(summary_stats.h_index or 5)` | `hIndex`; if `0` → `5` |
| `institutional_reach` | `min(int(h_index*1.5) + random.randint(2,6), 35)` | **`random` fudge dropped** → `min(trunc(h_index*1.5), 35)` — deterministic real number |
| profile missing | `{"years":[],"citations":[],"works":[],"institutional_reach":0,"h_index":0}` | same (zero-value struct with empty slices) |

### Response type

`CitationHeatmap{ Years []int, Citations []int, Works []int, InstitutionalReach
float64, HIndex int }` — matches `app/schemas/authors_extra.py::CitationHeatmap`
(`model_config extra="allow"`, so field-name parity is all that's required).

### Wiring — `main.go`

One appended line in the existing "Author endpoints" block, no reordering:

```
r.GET("/api/v1/citation_heatmap", author.GetCitationHeatmap)
```

Plus `firestore.Init()` called once in `main()` right after `auth.InitFirebase()`.
Python's route was registered on `authors.router` with no prefix, so FastAPI served
it at `/api/v1/citation_heatmap` (and, via the app's prefixless mount, `/citation_heatmap`).
The Go gateway's other author routes register both `/api/v1/...` and bare aliases;
`citation_heatmap` only ever had the `/api/v1` form exercised by the web/mobile
clients and the Python test, so only that path is added. Anything else still falls
through `NoRoute` → Python until the Python handler is removed (same commit).

### OpenAlex helper — `internal/services/openalex/heatmap.go` (new file)

`FetchAuthorCountsByYear` lives in a **new file** in the `openalex` package (not an
edit to `client.go`) to keep the merge with `network-to-go` trivial. It reuses the
unexported `c.get` (circuit breaker + polite headers) and unmarshals just
`counts_by_year` + `summary_stats`.

## Python removals (same PR)

| File | Change |
| :--- | :--- |
| `app/api/v1/endpoints/authors.py` | Delete the `@router.get("/citation_heatmap")` handler + `get_citation_heatmap`. Drop the now-unused `CitationHeatmap` import. Leave a `# GET /citation_heatmap — migrated to Go` breadcrumb next to the existing `resolve_email` / `orbit_metrics` ones. Router itself untouched. |
| `app/services/platform/pipeline/heatmap.py` | **Deleted** — the file only held `HeatmapMixin.get_citation_heatmap`. |
| `app/services/platform/pipeline_services.py` | Drop the `HeatmapMixin` import and its base-class entry. |
| `tests/api/test_authors.py` | Remove `test_citation_heatmap_parses`, the `_FakePipeline.get_citation_heatmap` stub, and the unused `CitationHeatmap` import. The route no longer exists in the Python app — same treatment `resolve_email` / `orbit_metrics` got when they migrated. |
| `api-contracts/openapi.snapshot.json` | Regenerated (`python scripts/gen_openapi_snapshot.py`, then LF-normalized). `citation_heatmap` path + `CitationHeatmap` schema drop out. |

**`tests/api/test_auth_posture.py`** — **no change needed.** `citation_heatmap`
had no auth dependency, so it was in the dynamically-computed `public` set, never in
the pinned `EXPECTED_AUTHED` / `EXPECTED_OPTIONAL`. `test_public_is_the_remainder`
self-adjusts; `test_route_table_is_actually_populated` only needs `> 20` routes.

## Verification

| Check | How | Result |
| :--- | :--- | :--- |
| Python suite | `pytest` from `services/backend` (sqlite) | baseline 166 → 165 (the one migrated-route test removed) |
| `ruff check app` | clean before, clean after | — |
| OpenAPI snapshot | regenerated + committed; `test_openapi.py` byte-equality | passes |
| Go build / vet / `test -race` | `SkoLab CI Pipeline / build-and-test` on the PR | see PR checks |

## Commits

1. `feat(backend-go): Postgres cache_entries parity client (Part 1)`
2. `feat(backend-go): nil-safe Firestore wrapper (Part 2)`
3. `feat: move GET /citation_heatmap to the Go gateway (Part 3)`

## `[NEEDS DECISION]` / open items

- **[NEEDS DECISION — Redis L2, non-blocking]** `pgcache.go` writes/reads Postgres
  `cache_entries` only. If `REDIS_URL` is ever set on *both* the Go gateway and the
  Python service, Python's `PgBackedCache` would prefer Redis and the two services
  would diverge on any key one warms into Redis. Today `REDIS_URL` is unset in
  `render.yaml` for both, so Postgres is the only tier. Revisit if Redis is
  introduced. `// TODO: Redis L2` marks the spot in code.
- **[NOTE]** Firestore `Init` builds its own `firebase.App` — see the Part 2 NOTE
  above. Behaviourally identical to sharing `auth`'s app; chosen to avoid a
  concurrent-edit conflict on `internal/auth/firebase.go`.
