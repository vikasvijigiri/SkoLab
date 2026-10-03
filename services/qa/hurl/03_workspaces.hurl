# Workspace lifecycle, validation, idempotency, paging, and isolation from
# other users. Leaves nothing behind.

# Profiles first (an account needs one to own or join a workspace).
POST {{base}}/api/v1/users/profile/sync
Authorization: Bearer {{owner_token}}
{"uid": "{{owner_uid}}", "name": "QA owner"}
HTTP 200
[Asserts]
jsonpath "$.status" == "synced"

POST {{base}}/api/v1/users/profile/sync
Authorization: Bearer {{outsider_token}}
{"uid": "{{outsider_uid}}", "name": "QA outsider"}
HTTP 200

# Profile rules: only your own uid, a real name.
POST {{base}}/api/v1/users/profile/sync
Authorization: Bearer {{owner_token}}
{"uid": "{{outsider_uid}}", "name": "Impostor"}
HTTP 403
[Asserts]
jsonpath "$.code" == "forbidden"

POST {{base}}/api/v1/users/profile/sync
Authorization: Bearer {{owner_token}}
{"uid": "{{owner_uid}}", "name": "   "}
HTTP 400
[Asserts]
jsonpath "$.code" == "invalid_name"

# Create, with an idempotency key.
POST {{base}}/api/v1/workspaces
Authorization: Bearer {{owner_token}}
Idempotency-Key: qa-{{owner_uid}}-a
{"title": "QA workspace A"}
HTTP 201
[Captures]
ws_a: jsonpath "$.id"
[Asserts]
header "Location" == "/api/v1/workspaces/{{ws_a}}"
jsonpath "$.role" == "owner"
jsonpath "$.owner_id" == "{{owner_uid}}"
jsonpath "$.created_at" isIsoDate

# Same key, same body: the same workspace, marked as a replay.
POST {{base}}/api/v1/workspaces
Authorization: Bearer {{owner_token}}
Idempotency-Key: qa-{{owner_uid}}-a
{"title": "QA workspace A"}
HTTP 201
[Asserts]
jsonpath "$.id" == "{{ws_a}}"
header "Idempotent-Replayed" == "true"

# Same key, different body: refused.
POST {{base}}/api/v1/workspaces
Authorization: Bearer {{owner_token}}
Idempotency-Key: qa-{{owner_uid}}-a
{"title": "Something else"}
HTTP 422
[Asserts]
jsonpath "$.code" == "idempotency_key_reused"

POST {{base}}/api/v1/workspaces
Authorization: Bearer {{owner_token}}
{"title": "QA workspace B"}
HTTP 201
[Captures]
ws_b: jsonpath "$.id"

# Validation.
POST {{base}}/api/v1/workspaces
Authorization: Bearer {{owner_token}}
{"title": "  "}
HTTP 400
[Asserts]
jsonpath "$.code" == "invalid_title"

POST {{base}}/api/v1/workspaces
Authorization: Bearer {{owner_token}}
```
{not json
```
HTTP 400
[Asserts]
jsonpath "$.code" == "invalid_body"

POST {{base}}/api/v1/workspaces
Authorization: Bearer {{owner_token}}
Idempotency-Key: has spaces
{"title": "x"}
HTTP 400
[Asserts]
jsonpath "$.code" == "invalid_idempotency_key"

# Paging: one per page, then the next page via the token.
GET {{base}}/api/v1/workspaces?page_size=1
Authorization: Bearer {{owner_token}}
HTTP 200
[Captures]
next: jsonpath "$.next_page_token"
[Asserts]
jsonpath "$.workspaces" count == 1
jsonpath "$.next_page_token" != ""

GET {{base}}/api/v1/workspaces?page_size=1&page_token={{next}}
Authorization: Bearer {{owner_token}}
HTTP 200
[Asserts]
jsonpath "$.workspaces" count == 1

GET {{base}}/api/v1/workspaces?page_size=0
Authorization: Bearer {{owner_token}}
HTTP 400
[Asserts]
jsonpath "$.code" == "invalid_page_size"

GET {{base}}/api/v1/workspaces?pagesize=1
Authorization: Bearer {{owner_token}}
HTTP 400
[Asserts]
jsonpath "$.code" == "unknown_parameter"

GET {{base}}/api/v1/workspaces?page_token=forged
Authorization: Bearer {{owner_token}}
HTTP 400
[Asserts]
jsonpath "$.code" == "invalid_page_token"

# Read and rename.
GET {{base}}/api/v1/workspaces/{{ws_a}}
Authorization: Bearer {{owner_token}}
HTTP 200
[Asserts]
jsonpath "$.title" == "QA workspace A"

PATCH {{base}}/api/v1/workspaces/{{ws_a}}
Authorization: Bearer {{owner_token}}
{"title": "QA workspace A, renamed"}
HTTP 200
[Asserts]
jsonpath "$.title" == "QA workspace A, renamed"

# Another user can neither see nor change it: 404, never 403.
GET {{base}}/api/v1/workspaces/{{ws_a}}
Authorization: Bearer {{outsider_token}}
HTTP 404
[Asserts]
jsonpath "$.code" == "not_found"

PATCH {{base}}/api/v1/workspaces/{{ws_a}}
Authorization: Bearer {{outsider_token}}
{"title": "Hijacked"}
HTTP 404

DELETE {{base}}/api/v1/workspaces/{{ws_a}}
Authorization: Bearer {{outsider_token}}
HTTP 404

GET {{base}}/api/v1/workspaces/{{unknown_id}}
Authorization: Bearer {{owner_token}}
HTTP 404

GET {{base}}/api/v1/workspaces/not-a-uuid
Authorization: Bearer {{owner_token}}
HTTP 404

# Clean up, then confirm it is gone.
DELETE {{base}}/api/v1/workspaces/{{ws_a}}
Authorization: Bearer {{owner_token}}
HTTP 204

DELETE {{base}}/api/v1/workspaces/{{ws_b}}
Authorization: Bearer {{owner_token}}
HTTP 204

GET {{base}}/api/v1/workspaces/{{ws_a}}
Authorization: Bearer {{owner_token}}
HTTP 404
