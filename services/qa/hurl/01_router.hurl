# Router, health and response contract: no sign-in needed.

# Liveness, with the security headers every response carries.
GET {{base}}/gateway-health
HTTP 200
[Asserts]
jsonpath "$.status" == "online"
header "Strict-Transport-Security" contains "max-age="
header "X-Content-Type-Options" == "nosniff"
header "X-Frame-Options" == "DENY"
header "Referrer-Policy" == "no-referrer"
header "Content-Security-Policy" contains "default-src 'none'"
header "X-Request-ID" exists

# HEAD for uptime monitors.
HEAD {{base}}/gateway-health
HTTP 200

# Readiness covers the database and the Python process in the container.
GET {{base}}/readyz
HTTP 200
[Asserts]
jsonpath "$.status" == "ready"
jsonpath "$.database" == "healthy"
jsonpath "$.python" == "healthy"

# Unknown path: JSON 404 in the standard error body.
GET {{base}}/no-such-endpoint
HTTP 404
[Asserts]
jsonpath "$.code" == "not_found"
jsonpath "$.error" isString

# Known path, wrong method: 405 with Allow.
PUT {{base}}/gateway-health
HTTP 405
[Asserts]
header "Allow" contains "GET"
jsonpath "$.code" == "method_not_allowed"

DELETE {{base}}/api/v1/workspaces
HTTP 405
[Asserts]
header "Allow" contains "POST"

# Python's own routes are not public.
GET {{base}}/livez
HTTP 404

GET {{base}}/docs
HTTP 404

# An undecodable query string is refused, not silently dropped.
GET {{base}}/gateway-health?page_token=%zz
HTTP 400
[Asserts]
jsonpath "$.code" == "invalid_query"

# A caller-supplied request ID is echoed only when log-safe.
GET {{base}}/gateway-health
X-Request-ID: qa-trace-123
HTTP 200
[Asserts]
header "X-Request-ID" == "qa-trace-123"

GET {{base}}/gateway-health
X-Request-ID: {{base}}/not safe
HTTP 200
[Asserts]
header "X-Request-ID" != "{{base}}/not safe"

# CORS: an origin outside the allow-list gets no CORS grant.
OPTIONS {{base}}/api/v1/workspaces
Origin: https://evil.example
Access-Control-Request-Method: GET
HTTP 204
[Asserts]
header "Access-Control-Allow-Origin" not exists
