# Invites and every role, with real second and third accounts: what an
# owner, editor, viewer, former member and outsider can and cannot do.

POST {{base}}/api/v1/users/profile/sync
Authorization: Bearer {{owner_token}}
{"uid": "{{owner_uid}}", "name": "QA owner"}
HTTP 200

POST {{base}}/api/v1/users/profile/sync
Authorization: Bearer {{member_token}}
{"uid": "{{member_uid}}", "name": "QA member"}
HTTP 200

POST {{base}}/api/v1/users/profile/sync
Authorization: Bearer {{outsider_token}}
{"uid": "{{outsider_uid}}", "name": "QA outsider"}
HTTP 200

POST {{base}}/api/v1/workspaces
Authorization: Bearer {{owner_token}}
{"title": "QA shared workspace"}
HTTP 201
[Captures]
ws: jsonpath "$.id"

# ── Invite options and creation (owner) ───────────────────────────────────
GET {{base}}/api/v1/workspaces/{{ws}}/invite-options
Authorization: Bearer {{owner_token}}
HTTP 200
[Asserts]
jsonpath "$.roles" contains "editor"
jsonpath "$.roles" not contains "owner"
jsonpath "$.expires_in_hours" contains 168

POST {{base}}/api/v1/workspaces/{{ws}}/invites
Authorization: Bearer {{owner_token}}
{"role": "owner", "expires_in_hours": 24}
HTTP 400
[Asserts]
jsonpath "$.code" == "invalid_role"

POST {{base}}/api/v1/workspaces/{{ws}}/invites
Authorization: Bearer {{owner_token}}
{"role": "viewer", "expires_in_hours": 5}
HTTP 400
[Asserts]
jsonpath "$.code" == "invalid_expiry"

POST {{base}}/api/v1/workspaces/{{ws}}/invites
Authorization: Bearer {{owner_token}}
{"role": "editor", "expires_in_hours": 24, "max_uses": 10}
HTTP 201
[Captures]
editor_invite: jsonpath "$.token"
editor_invite_id: jsonpath "$.id"
[Asserts]
jsonpath "$.token" startsWith "inv_"
header "Cache-Control" == "no-store"

# Listing never reveals tokens.
GET {{base}}/api/v1/workspaces/{{ws}}/invites
Authorization: Bearer {{owner_token}}
HTTP 200
[Asserts]
jsonpath "$.invites[0].token" not exists

# ── The member joins as editor ────────────────────────────────────────────
POST {{base}}/api/v1/invites/preview
Authorization: Bearer {{member_token}}
{"token": "{{editor_invite}}"}
HTTP 200
[Asserts]
jsonpath "$.role" == "editor"
jsonpath "$.workspace_title" == "QA shared workspace"

POST {{base}}/api/v1/invites/accept
Authorization: Bearer {{member_token}}
{"token": "{{editor_invite}}"}
HTTP 200
[Asserts]
jsonpath "$.role" == "editor"
jsonpath "$.changed" == true

GET {{base}}/api/v1/workspaces/{{ws}}
Authorization: Bearer {{member_token}}
HTTP 200
[Asserts]
jsonpath "$.role" == "editor"

GET {{base}}/api/v1/workspaces/{{ws}}/members
Authorization: Bearer {{member_token}}
HTTP 200
[Asserts]
jsonpath "$.members" count == 2
jsonpath "$.members[0].role" == "owner"

# An editor may invite, but may not rename, delete or manage roles.
POST {{base}}/api/v1/workspaces/{{ws}}/invites
Authorization: Bearer {{member_token}}
{"role": "viewer", "expires_in_hours": 24, "max_uses": 1}
HTTP 201
[Captures]
editor_made_invite_id: jsonpath "$.id"

DELETE {{base}}/api/v1/workspaces/{{ws}}/invites/{{editor_made_invite_id}}
Authorization: Bearer {{member_token}}
HTTP 204

PATCH {{base}}/api/v1/workspaces/{{ws}}
Authorization: Bearer {{member_token}}
{"title": "Editor rename"}
HTTP 403
[Asserts]
jsonpath "$.code" == "owner_required"

DELETE {{base}}/api/v1/workspaces/{{ws}}
Authorization: Bearer {{member_token}}
HTTP 403

PATCH {{base}}/api/v1/workspaces/{{ws}}/members/{{member_uid}}
Authorization: Bearer {{member_token}}
{"role": "viewer"}
HTTP 403

# A collaboration ticket is issued to members.
POST {{base}}/api/v1/ws/colab/{{ws}}/tickets
Authorization: Bearer {{member_token}}
HTTP 201
[Asserts]
jsonpath "$.ticket" isString
jsonpath "$.expires_in_seconds" > 0

# ── The owner demotes the member to viewer ────────────────────────────────
PATCH {{base}}/api/v1/workspaces/{{ws}}/members/{{member_uid}}
Authorization: Bearer {{owner_token}}
{"role": "viewer"}
HTTP 200
[Asserts]
jsonpath "$.role" == "viewer"

# A viewer may read, but not invite.
GET {{base}}/api/v1/workspaces/{{ws}}
Authorization: Bearer {{member_token}}
HTTP 200
[Asserts]
jsonpath "$.role" == "viewer"

GET {{base}}/api/v1/workspaces/{{ws}}/members
Authorization: Bearer {{member_token}}
HTTP 200

GET {{base}}/api/v1/workspaces/{{ws}}/invite-options
Authorization: Bearer {{member_token}}
HTTP 403
[Asserts]
jsonpath "$.code" == "invite_forbidden"

POST {{base}}/api/v1/workspaces/{{ws}}/invites
Authorization: Bearer {{member_token}}
{"role": "viewer", "expires_in_hours": 24}
HTTP 403

# ── Owner rules ───────────────────────────────────────────────────────────
PATCH {{base}}/api/v1/workspaces/{{ws}}/members/{{owner_uid}}
Authorization: Bearer {{owner_token}}
{"role": "viewer"}
HTTP 409
[Asserts]
jsonpath "$.code" == "owner_immutable"

DELETE {{base}}/api/v1/workspaces/{{ws}}/members/{{owner_uid}}
Authorization: Bearer {{owner_token}}
HTTP 409
[Asserts]
jsonpath "$.code" == "owner_cannot_leave"

PATCH {{base}}/api/v1/workspaces/{{ws}}/members/{{member_uid}}
Authorization: Bearer {{owner_token}}
{"role": "owner"}
HTTP 400

# ── The outsider sees nothing ─────────────────────────────────────────────
GET {{base}}/api/v1/workspaces/{{ws}}/members
Authorization: Bearer {{outsider_token}}
HTTP 404

GET {{base}}/api/v1/workspaces/{{ws}}/invite-options
Authorization: Bearer {{outsider_token}}
HTTP 404

POST {{base}}/api/v1/ws/colab/{{ws}}/tickets
Authorization: Bearer {{outsider_token}}
HTTP 404
[Asserts]
jsonpath "$.code" == "not_found"

# ── A single-use link admits exactly one person ───────────────────────────
POST {{base}}/api/v1/workspaces/{{ws}}/invites
Authorization: Bearer {{owner_token}}
{"role": "viewer", "expires_in_hours": 24, "max_uses": 1}
HTTP 201
[Captures]
single_use: jsonpath "$.token"

POST {{base}}/api/v1/invites/accept
Authorization: Bearer {{outsider_token}}
{"token": "{{single_use}}"}
HTTP 200
[Asserts]
jsonpath "$.changed" == true

POST {{base}}/api/v1/invites/preview
Authorization: Bearer {{owner_token}}
{"token": "{{single_use}}"}
HTTP 404
[Asserts]
jsonpath "$.code" == "invite_invalid"

# The newcomer leaves; access ends.
DELETE {{base}}/api/v1/workspaces/{{ws}}/members/{{outsider_uid}}
Authorization: Bearer {{outsider_token}}
HTTP 204

GET {{base}}/api/v1/workspaces/{{ws}}
Authorization: Bearer {{outsider_token}}
HTTP 404

# ── Revocation ────────────────────────────────────────────────────────────
DELETE {{base}}/api/v1/workspaces/{{ws}}/invites/{{editor_invite_id}}
Authorization: Bearer {{owner_token}}
HTTP 204

POST {{base}}/api/v1/invites/accept
Authorization: Bearer {{outsider_token}}
{"token": "{{editor_invite}}"}
HTTP 404

DELETE {{base}}/api/v1/workspaces/{{ws}}/invites/{{editor_invite_id}}
Authorization: Bearer {{owner_token}}
HTTP 404

# Malformed and unknown tokens look the same.
POST {{base}}/api/v1/invites/preview
Authorization: Bearer {{owner_token}}
{"token": "not-an-invite"}
HTTP 404
[Asserts]
jsonpath "$.code" == "invite_invalid"

# ── The owner removes the member; the member loses access ─────────────────
DELETE {{base}}/api/v1/workspaces/{{ws}}/members/{{member_uid}}
Authorization: Bearer {{owner_token}}
HTTP 204

GET {{base}}/api/v1/workspaces/{{ws}}
Authorization: Bearer {{member_token}}
HTTP 404

DELETE {{base}}/api/v1/workspaces/{{ws}}
Authorization: Bearer {{owner_token}}
HTTP 204
