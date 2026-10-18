# The Projects and Templates pages, notifications and AI, end to end: what a
# project card carries, the compiled first pages of projects and templates,
# co-authors' activity, and the AI routes up to the model (CI staging has no
# model key, by design).

POST {{base}}/api/v1/users/profile/sync
Authorization: Bearer {{owner_token}}
{"uid": "{{owner_uid}}", "name": "QA owner"}
HTTP 200

POST {{base}}/api/v1/users/profile/sync
Authorization: Bearer {{member_token}}
{"uid": "{{member_uid}}", "name": "QA member"}
HTTP 200

# ── A project with a co-author ────────────────────────────────────────────
POST {{base}}/api/v1/workspaces
Authorization: Bearer {{owner_token}}
{"title": "QA card project"}
HTTP 201
[Captures]
card: jsonpath "$.id"

POST {{base}}/api/v1/workspaces/{{card}}/invites
Authorization: Bearer {{owner_token}}
{"role": "viewer", "expires_in_hours": 24, "max_uses": 1}
HTTP 201
[Captures]
card_invite: jsonpath "$.token"

POST {{base}}/api/v1/invites/accept
Authorization: Bearer {{member_token}}
{"token": "{{card_invite}}"}
HTTP 200

# Only the list carries card facts; before any save they are empty.
GET {{base}}/api/v1/workspaces/{{card}}
Authorization: Bearer {{owner_token}}
HTTP 200
[Asserts]
jsonpath "$.authors" not exists
jsonpath "$.words" not exists

GET {{base}}/api/v1/workspaces?page_size=100
Authorization: Bearer {{member_token}}
HTTP 200
[Asserts]
jsonpath "$.workspaces[?(@.id == '{{card}}')].authors_total" == 2
jsonpath "$.workspaces[?(@.id == '{{card}}')].authors[0].role" == "owner"
jsonpath "$.workspaces[?(@.id == '{{card}}')].authors[0].display_name" == "QA owner"
jsonpath "$.workspaces[?(@.id == '{{card}}')].authors[1].display_name" == "QA member"
jsonpath "$.workspaces[?(@.id == '{{card}}')].role" == "viewer"
jsonpath "$.workspaces[?(@.id == '{{card}}')].words" == 0
jsonpath "$.workspaces[?(@.id == '{{card}}')].updated_at" == null
jsonpath "$.workspaces[?(@.id == '{{card}}')].compiled_at" == null

# No compile yet: no first page.
GET {{base}}/api/v1/workspaces/{{card}}/preview.jpg
Authorization: Bearer {{owner_token}}
HTTP 404
[Asserts]
jsonpath "$.code" == "no_output"

# ── The owner writes, and the card follows ────────────────────────────────
PUT {{base}}/api/v1/workspaces/{{card}}/document
Authorization: Bearer {{owner_token}}
```json
{"source": "\\documentclass{article}\n\\title{Spin waves in thin films}\n\\author{QA owner}\n\\begin{document}\n\\maketitle\n\\section{Introduction}\nWe measured the dispersion of spin waves in thin magnetic films at room temperature.\n\\end{document}\n", "base_version": 0}
```
HTTP 200

GET {{base}}/api/v1/workspaces?page_size=100
Authorization: Bearer {{member_token}}
HTTP 200
[Asserts]
jsonpath "$.workspaces[?(@.id == '{{card}}')].words" > 10
jsonpath "$.workspaces[?(@.id == '{{card}}')].progress" > 0
jsonpath "$.workspaces[?(@.id == '{{card}}')].updated_at" isIsoDate
jsonpath "$.workspaces[?(@.id == '{{card}}')].updated_by.display_name" == "QA owner"

# The co-author hears about it.
GET {{base}}/api/v1/activity?page_size=50
Authorization: Bearer {{member_token}}
HTTP 200
[Asserts]
jsonpath "$.items[?(@.workspace.id == '{{card}}')].kind" contains "document_edited"
jsonpath "$.unread" >= 1

POST {{base}}/api/v1/activity/seen
Authorization: Bearer {{member_token}}
HTTP 200
[Asserts]
jsonpath "$.unread" == 0

GET {{base}}/api/v1/activity?page_size=1&nonsense=1
Authorization: Bearer {{member_token}}
HTTP 400

# ── Compile: the card gets its first page ─────────────────────────────────
POST {{base}}/api/v1/workspaces/{{card}}/compile
Authorization: Bearer {{owner_token}}
HTTP 200
[Asserts]
jsonpath "$.status" == "compiled"

GET {{base}}/api/v1/workspaces/{{card}}/preview.jpg
Authorization: Bearer {{member_token}}
HTTP 200
[Captures]
card_tag: header "ETag"
[Asserts]
header "Content-Type" == "image/jpeg"
header "Cache-Control" == "private, no-cache"
bytes startsWith hex,ffd8ff;

# The same compile: nothing to send again.
GET {{base}}/api/v1/workspaces/{{card}}/preview.jpg
Authorization: Bearer {{owner_token}}
If-None-Match: {{card_tag}}
HTTP 304

GET {{base}}/api/v1/workspaces?page_size=100
Authorization: Bearer {{owner_token}}
HTTP 200
[Asserts]
jsonpath "$.workspaces[?(@.id == '{{card}}')].compiled_at" isIsoDate

# Without a token, or for someone else's project: nothing.
GET {{base}}/api/v1/workspaces/{{card}}/preview.jpg
HTTP 401

# ── Template pictures ─────────────────────────────────────────────────────
GET {{base}}/api/v1/templates
Authorization: Bearer {{owner_token}}
HTTP 200
[Captures]
nature_preview: jsonpath "$.templates[?(@.id == 'nature')].preview"
[Asserts]
jsonpath "$.templates[*].preview" count >= 8
jsonpath "$.templates[?(@.id == 'nature')].preview" matches "^/api/v1/templates/nature/preview\\.jpg\\?v=[0-9a-f]{16}$"

GET {{base}}{{nature_preview}}
Authorization: Bearer {{owner_token}}
HTTP 200
[Asserts]
header "Content-Type" == "image/jpeg"
header "Cache-Control" == "private, max-age=31536000, immutable"
bytes startsWith hex,ffd8ff;

GET {{base}}/api/v1/templates/nature/preview.jpg?v=not-a-version
Authorization: Bearer {{owner_token}}
HTTP 400
[Asserts]
jsonpath "$.code" == "invalid_version"

GET {{base}}/api/v1/templates/no-such-journal/preview.jpg
Authorization: Bearer {{owner_token}}
HTTP 404

# ── AI, up to the model ───────────────────────────────────────────────────
GET {{base}}/api/v1/ai/status
Authorization: Bearer {{owner_token}}
HTTP 200
[Asserts]
jsonpath "$.improve.available" isBoolean
jsonpath "$.originality.daily_limit" isInteger

# A viewer may read, not have AI rewrite.
POST {{base}}/api/v1/workspaces/{{card}}/ai/improve
Authorization: Bearer {{member_token}}
{"text": "Teh results is good.", "goal": "grammar"}
HTTP 403
[Asserts]
jsonpath "$.code" == "edit_forbidden"

POST {{base}}/api/v1/workspaces/{{card}}/ai/improve
Authorization: Bearer {{owner_token}}
{"text": "Teh results is good.", "goal": "poetry"}
HTTP 400
[Asserts]
jsonpath "$.code" == "invalid_goal"

POST {{base}}/api/v1/workspaces/{{card}}/ai/improve
Authorization: Bearer {{owner_token}}
{"text": "   ", "goal": "grammar"}
HTTP 400
[Asserts]
jsonpath "$.code" == "invalid_text"

GET {{base}}/api/v1/workspaces/{{card}}/ai/originality
Authorization: Bearer {{member_token}}
HTTP 200
[Asserts]
jsonpath "$.reports" count == 0

GET {{base}}/api/v1/workspaces/{{card}}/ai/originality/00000000-0000-4000-8000-000000000000
Authorization: Bearer {{owner_token}}
HTTP 404

# ── Clean up ──────────────────────────────────────────────────────────────
DELETE {{base}}/api/v1/workspaces/{{card}}
Authorization: Bearer {{owner_token}}
HTTP 204

GET {{base}}/api/v1/workspaces/{{card}}/preview.jpg
Authorization: Bearer {{owner_token}}
HTTP 404
