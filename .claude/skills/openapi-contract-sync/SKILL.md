---
name: openapi-contract-sync
description: Verify and sync API endpoints, request/response models, and parameters against the schema-first contract api-contracts/openapi.yaml. Use whenever creating or modifying endpoints in services/backend (Python FastAPI) or services/backend-go (Go Gateway), or when updating frontend API clients in apps/web. Do NOT use for internal-only changes that never cross an API boundary (no route, schema, or client touched).
---

# OpenAPI Contract Synchronization Skill

Per [AGENTS.md](file:///c:/Users/VikasVijigiri/Documents/SkoLab/AGENTS.md), `api-contracts/openapi.yaml` is the **schema-first contract** for SkoLab. It is the source of truth for all API definitions across Python FastAPI, Go Gateway, and Web/Android clients.

## Verification Checklist

When creating or updating API endpoints:

1. **Schema-First Contract Check**:
   - Inspect [api-contracts/openapi.yaml](file:///c:/Users/VikasVijigiri/Documents/SkoLab/api-contracts/openapi.yaml) for the target path, HTTP method, query parameters, request body schemas, and response status codes.
   - If the endpoint is new or changed, update `openapi.yaml` **first** before implementation.

2. **Python Backend Alignment (`services/backend`)**:
   - Check `app/schemas/` for Pydantic models matching `components.schemas` in `openapi.yaml`.
   - Ensure `app/api/v1/endpoints/` route handlers use the matching Pydantic response models and query parameter types.

3. **Go Gateway Alignment (`services/backend-go`)**:
   - Check `internal/` or `main.go` route definitions to ensure proxy routes, header forwarding, and CORS parameters match `openapi.yaml`.

4. **Web Frontend Alignment (`apps/web`)**:
   - Check `apps/web/src/lib/api/` or component fetch calls to verify TypeScript interfaces match the response schema defined in `openapi.yaml`.

## Routing

- Mandatory validator: none beyond a manual diff of `openapi.yaml` against
  the Pydantic models, Go routes, and TypeScript interfaces it should match.
- Terminal handoff: `backend-test-suite` / `full-repo-test-suite` once the
  contract and implementations agree, to confirm nothing else broke.

## Success

`openapi.yaml` and every consumer (Python schemas, Go routes, web TS
interfaces) describe the same path, method, params, and response shape —
checked by reading each side, not assumed from the endpoint name.
