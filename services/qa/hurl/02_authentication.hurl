# Authentication: every protected route refuses missing or bad credentials
# with 401 and a Bearer challenge (RFC 6750), before doing any work.

GET {{base}}/api/v1/workspaces
HTTP 401
[Asserts]
header "WWW-Authenticate" == "Bearer"
jsonpath "$.code" == "token_missing"
header "Cache-Control" == "no-store"

POST {{base}}/api/v1/workspaces
{"title": "x"}
HTTP 401

POST {{base}}/api/v1/colab/compile
{"latex_source": "x"}
HTTP 401

POST {{base}}/api/v1/invites/accept
{"token": "inv_x"}
HTTP 401

DELETE {{base}}/api/v1/users/{{owner_uid}}
HTTP 401

# Wrong scheme.
GET {{base}}/api/v1/workspaces
Authorization: Basic {{owner_token}}
HTTP 401

# Garbage token: invalid_token challenge.
GET {{base}}/api/v1/workspaces
Authorization: Bearer not.a.jwt
HTTP 401
[Asserts]
header "WWW-Authenticate" contains "invalid_token"

# Unsigned (alg=none) token claiming to be the owner.
GET {{base}}/api/v1/workspaces
Authorization: Bearer eyJhbGciOiJub25lIiwidHlwIjoiSldUIn0.eyJzdWIiOiJ7e293bmVyX3VpZH19IiwiZXhwIjo5OTk5OTk5OTk5fQ.
HTTP 401

# The real token works.
GET {{base}}/api/v1/workspaces
Authorization: Bearer {{owner_token}}
HTTP 200
[Asserts]
jsonpath "$.workspaces" isCollection
header "Cache-Control" == "no-store"
