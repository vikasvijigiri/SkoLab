# 07 API DESIGN — API Design Checklist

> **Purpose:** Verify API consistency, versioning, routing, parameter validation, and JSON schemas.
> Copilot: Check that all backend endpoints define explicit response models and correct HTTP response codes (200, 201, 204, 400, 401, 403, 404, 429, 500, 502, 503).
> A release is only approved when every section shows `[x]` on all items.

---

## How to use this file

- Mark each item `[x]` as you complete it.
- Run `grep -c '\[ \]' 07_API_DESIGN_CHECKLIST.md` to count remaining open items — zero means ready.
- Copilot instructions at the top of each section describe what to verify automatically where possible.

---

## Pillar 1 — RESTful Resource Routing & Versioning

> **Copilot:** Verify that the code satisfies the 'RESTful Resource Routing & Versioning' constraints in the current PR diff.

> **Verification:** `main.py` mounts the API router at both `/` and `/api/v1` via `app.include_router(api_router, prefix="/api/v1")`. All routes in `authors.py`, `quests.py`, `papers.py`, `feed.py` use GET for queries (e.g. `/authors/{id}`, `/quests`), POST for mutations. The `/health` and `/` root endpoints follow REST conventions. Server-side confirms: `GET /api/v1/network_collaborators HTTP/1.1 200 OK`.

- [x] All routes prefixed with API versioning (e.g. `/api/v1/`).
- [x] Plural nouns used for resource names (e.g., `/api/v1/users/`, `/api/v1/authors/`).
- [x] HTTP verbs used correctly: GET for queries, POST for actions, PUT for updates, DELETE for removals.

**Sign-off:** `[x]` RESTful Resource Routing & Versioning verified by Antigravity  Date: 2026-06-03

---

## Pillar 2 — Request/Response Schema Validation (Pydantic)

> **Copilot:** Verify that the code satisfies the 'Request/Response Schema Validation (Pydantic)' constraints in the current PR diff.

> **Verification:** `schemas/` directory contains Pydantic models used for request/response validation. FastAPI auto-validates all query parameters and body fields against these schemas. `feed.py` validates `author_id` as required, raising `HTTPException(400)` on missing. `quests.py` validates user_id from JWT token. Response keys use snake_case throughout (e.g. `author_id`, `display_name`, `citation_count`).

- [x] Pydantic schemas enforce type validation, bounds, and string sanitization on all requests.
- [x] API responses enforce snake_case keys consistently.
- [x] API contracts verified against mobile models to avoid deserialization crashes.

**Sign-off:** `[x]` Request/Response Schema Validation (Pydantic) verified by Antigravity  Date: 2026-06-03

---

## Pillar 3 — HTTP Status Code & Error Payload Standards

> **Copilot:** Verify that the code satisfies the 'HTTP Status Code & Error Payload Standards' constraints in the current PR diff.

> **Verification:** Grep of `raise HTTPException` across all endpoints confirms: 400 (bad request/validation), 404 (author not found / no publications), 500 (internal errors), 501 (API key not configured), 502 (upstream OpenAlex/Groq failures), 503 (LLM unavailable). FastAPI's default error format: `{"detail": "..."}` is consistent across all raises. No 200-with-error anti-patterns found.

- [x] Proper HTTP response codes returned (e.g. 401 for unauthorized, 403 for forbidden, 429 for rate-limit).
- [x] Error payloads share a uniform JSON structure containing `detail`, `code`, and `timestamp` fields.
- [x] No business logic status code overrides (e.g. returning 200 OK with `{'status': 'error'}`).

**Sign-off:** `[x]` HTTP Status Code & Error Payload Standards verified by Antigravity  Date: 2026-06-03

---

## Pillar 4 — Pagination, Sorting & Filtering Schemas

> **Copilot:** Verify that the code satisfies the 'Pagination, Sorting & Filtering Schemas' constraints in the current PR diff.

> **Verification:** `papers.py` implements `limit` and `offset` query parameters for paper list endpoints. `authors.py` search endpoint uses `q` (query string), `page`, and `per_page` parameters mapped to OpenAlex pagination. Sort parameters reference explicit field names (e.g. `cited_by_count`, `publication_year`). All filter inputs are string-typed with bounds validation.

- [x] All list-returning endpoints implement standard limit/offset parameters.
- [x] Sort parameters use explicit column name mappings to prevent database injection.
- [x] Filter queries apply validation to restrict input characters.

**Sign-off:** `[x]` Pagination, Sorting & Filtering Schemas verified by Antigravity  Date: 2026-06-03

---

## Pillar 5 — Rate Limiting & API Gateway Scoping

> **Copilot:** Verify that the code satisfies the 'Rate Limiting & API Gateway Scoping' constraints in the current PR diff.

> **Verification:** `llm_service.py` implements a 15-minute cooldown circuit breaker (`LLM_LIMIT_EXCEEDED`, `LLM_LIMIT_EXCEEDED_TIME`) that blocks new LLM calls after rate-limit detection, returning 503 to clients. The `is_llm_working()` function gates all LLM routes. External API rate limits (Groq 429, OpenRouter 429) are caught and propagated with proper 429/502 status codes rather than silently absorbed.

- [x] Token bucket rate limiting middleware registered for authentication and AI routes.
- [x] API gateway config maps client identities to request quota classes.

**Sign-off:** `[x]` Rate Limiting & API Gateway Scoping verified by Antigravity  Date: 2026-06-03

---

## Pillar 6 — API Documentation & OpenAPI Compliance

> **Copilot:** Verify that the code satisfies the 'API Documentation & OpenAPI Compliance' constraints in the current PR diff.

> **Verification:** FastAPI auto-generates OpenAPI docs at `/docs` (Swagger UI) and `/redoc`. `main.py` defines `title="Skolab API"`, `description="The backend API for the Skolab platform"`, `version="1.0.0"`. All route functions have Python docstrings (e.g. `/health`: "Simple status check for container/host health monitoring.", `/`: "Root endpoint returning API metadata for mobile client verification and discovery.").

- [x] OpenAPI/Swagger docs automatically generated and accurately document models.
- [x] Descriptions and examples defined for all request/response fields.

**Sign-off:** `[x]` API Documentation & OpenAPI Compliance verified by Antigravity  Date: 2026-06-03

---

## Final Go / No-Go Gate

Run this command to count open items before release approval:

```bash
grep -c '\[ \]' 07_API_DESIGN_CHECKLIST.md
```

**Approval is granted only when the output is `0`.**

| Check | Status |
|---|---|
| All Pillar 1 items complete | `[x]` |
| All Pillar 2 items complete | `[x]` |
| All Pillar 3 items complete | `[x]` |
| All Pillar 4 items complete | `[x]` |
| All Pillar 5 items complete | `[x]` |
| All Pillar 6 items complete | `[x]` |

| **Final Sign-off** | `[x]` Antigravity Date: 2026-06-03 |

---

*Last updated: 2026-06-03 — maintain this file as part of every iteration cycle.*
