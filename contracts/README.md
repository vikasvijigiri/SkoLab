# Contracts

All cross-service API compatibility material lives here.

- `openapi/` contains the generated FastAPI OpenAPI snapshot.
- `schemas/` contains language-neutral JSON schemas shared by services and clients.

Run `make verify-contracts` after changing a schema. Regenerate the OpenAPI
snapshot from `services/backend` with `python scripts/gen_openapi_snapshot.py`
when an API route or schema changes.
