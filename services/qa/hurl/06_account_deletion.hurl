# Account deletion, on the dedicated "doomed" account (runs last). Its
# workspace goes with it and its session stops working immediately.

POST {{base}}/api/v1/users/profile/sync
Authorization: Bearer {{doomed_token}}
{"uid": "{{doomed_uid}}", "name": "QA doomed"}
HTTP 200

POST {{base}}/api/v1/workspaces
Authorization: Bearer {{doomed_token}}
{"title": "QA doomed workspace"}
HTTP 201

# Nobody deletes someone else's account.
DELETE {{base}}/api/v1/users/{{owner_uid}}
Authorization: Bearer {{doomed_token}}
HTTP 403
[Asserts]
jsonpath "$.code" == "forbidden"

DELETE {{base}}/api/v1/users/{{doomed_uid}}
Authorization: Bearer {{doomed_token}}
HTTP 204

# The deleted account's still-unexpired token is refused at once.
GET {{base}}/api/v1/workspaces
Authorization: Bearer {{doomed_token}}
HTTP 401
