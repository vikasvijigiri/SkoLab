# The editor's backend: the journal template catalog, and a workspace's
# LaTeX document with versioned saves and role-based access.

POST {{base}}/api/v1/users/profile/sync
Authorization: Bearer {{owner_token}}
{"uid": "{{owner_uid}}", "name": "QA owner"}
HTTP 200

POST {{base}}/api/v1/users/profile/sync
Authorization: Bearer {{member_token}}
{"uid": "{{member_uid}}", "name": "QA member"}
HTTP 200

# ── Template catalog ──────────────────────────────────────────────────────
GET {{base}}/api/v1/templates
HTTP 401
[Asserts]
jsonpath "$.code" == "token_missing"

GET {{base}}/api/v1/templates
Authorization: Bearer {{owner_token}}
HTTP 200
[Captures]
catalog_etag: header "ETag"
[Asserts]
header "Cache-Control" == "private, no-cache"
jsonpath "$.domains" count == 4
jsonpath "$.templates" count >= 8
jsonpath "$.templates[0].source" not exists
jsonpath "$.templates[?(@.domain == 'physics')]" count >= 2
jsonpath "$.templates[?(@.domain == 'chemistry')]" count >= 2
jsonpath "$.templates[?(@.domain == 'mathematics')]" count >= 2
jsonpath "$.templates[?(@.domain == 'biology')]" count >= 2

GET {{base}}/api/v1/templates
Authorization: Bearer {{owner_token}}
If-None-Match: {{catalog_etag}}
HTTP 304

GET {{base}}/api/v1/templates?domain=biology
Authorization: Bearer {{owner_token}}
HTTP 200
[Asserts]
jsonpath "$.templates[?(@.domain == 'biology')]" count >= 2
jsonpath "$.templates[*].domain" not contains "physics"
jsonpath "$.templates[*].domain" not contains "chemistry"
jsonpath "$.templates[*].domain" not contains "mathematics"

GET {{base}}/api/v1/templates?domain=astrology
Authorization: Bearer {{owner_token}}
HTTP 400
[Asserts]
jsonpath "$.code" == "invalid_domain"

GET {{base}}/api/v1/templates?domian=physics
Authorization: Bearer {{owner_token}}
HTTP 400
[Asserts]
jsonpath "$.code" == "unknown_parameter"

GET {{base}}/api/v1/templates/aps-physical-review
Authorization: Bearer {{owner_token}}
HTTP 200
[Asserts]
jsonpath "$.domain" == "physics"
jsonpath "$.source" contains "\\documentclass"
jsonpath "$.source" contains "{revtex4-2}"

GET {{base}}/api/v1/templates/no-such-template
Authorization: Bearer {{owner_token}}
HTTP 404
[Asserts]
jsonpath "$.code" == "not_found"

# ── A workspace's document: first save, versioned updates, conflicts ──────
POST {{base}}/api/v1/workspaces
Authorization: Bearer {{owner_token}}
{"title": "QA editor workspace"}
HTTP 201
[Captures]
ws: jsonpath "$.id"

GET {{base}}/api/v1/workspaces/{{ws}}/document
Authorization: Bearer {{owner_token}}
HTTP 200
[Asserts]
header "ETag" == "\"v0\""
header "Cache-Control" == "no-store"
jsonpath "$.version" == 0
jsonpath "$.source" == ""
jsonpath "$.role" == "owner"
jsonpath "$.updated_at" == null

PUT {{base}}/api/v1/workspaces/{{ws}}/document
Authorization: Bearer {{owner_token}}
{"source": "\\documentclass{revtex4-2}\n\\begin{document}\nFirst draft.\n\\end{document}\n", "base_version": 0, "template_id": "aps-physical-review"}
HTTP 200
[Asserts]
header "ETag" == "\"v1\""
jsonpath "$.version" == 1
jsonpath "$.template_id" == "aps-physical-review"
jsonpath "$.updated_by" == "{{owner_uid}}"
jsonpath "$.updated_at" isString

# A second "first save" lost the race: nothing changes, the caller learns
# the current version.
PUT {{base}}/api/v1/workspaces/{{ws}}/document
Authorization: Bearer {{owner_token}}
{"source": "overwrite", "base_version": 0}
HTTP 409
[Asserts]
header "ETag" == "\"v1\""
jsonpath "$.code" == "version_conflict"
jsonpath "$.current_version" == 1

PUT {{base}}/api/v1/workspaces/{{ws}}/document
Authorization: Bearer {{owner_token}}
{"source": "\\documentclass{revtex4-2}\n\\begin{document}\nSecond draft.\n\\end{document}\n", "base_version": 1}
HTTP 200
[Asserts]
jsonpath "$.version" == 2
jsonpath "$.template_id" == "aps-physical-review"

GET {{base}}/api/v1/workspaces/{{ws}}/document
Authorization: Bearer {{owner_token}}
HTTP 200
[Asserts]
jsonpath "$.version" == 2
jsonpath "$.source" contains "Second draft."

PUT {{base}}/api/v1/workspaces/{{ws}}/document
Authorization: Bearer {{owner_token}}
{"source": "x", "base_version": 2, "template_id": "no-such-template"}
HTTP 400
[Asserts]
jsonpath "$.code" == "unknown_template"

PUT {{base}}/api/v1/workspaces/{{ws}}/document
Authorization: Bearer {{owner_token}}
{"source": "a\u0000b", "base_version": 2}
HTTP 400
[Asserts]
jsonpath "$.code" == "invalid_source"

PUT {{base}}/api/v1/workspaces/{{ws}}/document
Authorization: Bearer {{owner_token}}
{"source": "x", "base_version": 2, "owner_id": "{{member_uid}}"}
HTTP 400
[Asserts]
jsonpath "$.code" == "invalid_body"

# ── Access follows workspace roles ────────────────────────────────────────
GET {{base}}/api/v1/workspaces/{{ws}}/document
Authorization: Bearer {{member_token}}
HTTP 404
[Asserts]
jsonpath "$.code" == "not_found"

PUT {{base}}/api/v1/workspaces/{{ws}}/document
Authorization: Bearer {{member_token}}
{"source": "x", "base_version": 2}
HTTP 404

POST {{base}}/api/v1/workspaces/{{ws}}/invites
Authorization: Bearer {{owner_token}}
{"role": "viewer", "expires_in_hours": 24, "max_uses": 1}
HTTP 201
[Captures]
viewer_invite: jsonpath "$.token"

POST {{base}}/api/v1/invites/accept
Authorization: Bearer {{member_token}}
{"token": "{{viewer_invite}}"}
HTTP 200

# A viewer reads but cannot save.
GET {{base}}/api/v1/workspaces/{{ws}}/document
Authorization: Bearer {{member_token}}
HTTP 200
[Asserts]
jsonpath "$.role" == "viewer"
jsonpath "$.source" contains "Second draft."

PUT {{base}}/api/v1/workspaces/{{ws}}/document
Authorization: Bearer {{member_token}}
{"source": "viewer edit", "base_version": 2}
HTTP 403
[Asserts]
jsonpath "$.code" == "edit_forbidden"

# Promoted to editor, the member saves.
PATCH {{base}}/api/v1/workspaces/{{ws}}/members/{{member_uid}}
Authorization: Bearer {{owner_token}}
{"role": "editor"}
HTTP 200

PUT {{base}}/api/v1/workspaces/{{ws}}/document
Authorization: Bearer {{member_token}}
{"source": "\\documentclass{revtex4-2}\n\\begin{document}\nEdited by a co-author.\n\\end{document}\n", "base_version": 2}
HTTP 200
[Asserts]
jsonpath "$.version" == 3
jsonpath "$.role" == "editor"
jsonpath "$.updated_by" == "{{member_uid}}"

# The document goes with its workspace.
DELETE {{base}}/api/v1/workspaces/{{ws}}
Authorization: Bearer {{owner_token}}
HTTP 204

GET {{base}}/api/v1/workspaces/{{ws}}/document
Authorization: Bearer {{member_token}}
HTTP 404
