# API contracts

## Source of truth

The FastAPI backend's HTTP contract is **generated from the code**, not
hand-authored. There is no `openapi.yaml` to keep in sync any more (it drifted
to ~16 % coverage — a two-copies problem, not a missing-spec one).

| Artifact | What it is |
|---|---|
| `GET /openapi.json` (live) | The authoritative schema, produced by `app.openapi()` |
| `/docs`, `/redoc` (live) | Swagger UI / ReDoc over the same schema |
| `openapi.snapshot.json` (this dir) | A committed copy of `app.openapi()`, diffed by a test so a contract change shows up as a reviewable diff |

## Regenerating the snapshot

Run after any change that alters a route, parameter, or `response_model`:

```bash
cd services/backend && python -m pytest tests/test_openapi.py
```

If the snapshot is missing it is written and the diff test is skipped with a
message telling you to commit it. If it exists and differs, the test fails and
prints the same instruction. Commit `api-contracts/openapi.snapshot.json` with
the change that caused it.

## Go gateway

The Go gateway (`services/backend-go`) has its own route surface. It is
documented by its handlers and tests, not by this directory.
