# Research quality audit, evidence matrix, reproducibility notes and archive.

POST {{base}}/api/v1/workspaces
Authorization: Bearer {{owner_token}}
{"title": "QA research quality"}
HTTP 201
[Captures]
research_ws: jsonpath "$.id"

GET {{base}}/api/v1/workspaces/{{research_ws}}/research
Authorization: Bearer {{owner_token}}
HTTP 200
[Asserts]
jsonpath "$.version" == 0
jsonpath "$.items" count == 0
jsonpath "$.quality.cited_keys" count == 0

PUT {{base}}/api/v1/workspaces/{{research_ws}}/document
Authorization: Bearer {{owner_token}}
{"source": "\\documentclass{article}\n\\begin{document}Claim \\cite{alpha,beta}.\\end{document}", "base_version": 0}
HTTP 200

POST {{base}}/api/v1/workspaces/{{research_ws}}/files
Authorization: Bearer {{owner_token}}
{"path": "refs.bib", "kind": "text", "content": "@article{alpha, title={A}}\n@article{unused, title={U}}"}
HTTP 201

GET {{base}}/api/v1/workspaces/{{research_ws}}/research
Authorization: Bearer {{owner_token}}
HTTP 200
[Asserts]
jsonpath "$.quality.cited_keys" count == 2
jsonpath "$.quality.unresolved_keys" == ["beta"]
jsonpath "$.quality.citations_without_evidence" == ["alpha", "beta"]
jsonpath "$.quality.unused_references" == ["unused"]

PUT {{base}}/api/v1/workspaces/{{research_ws}}/research
Authorization: Bearer {{owner_token}}
{"base_version": 0, "items": [{"id": "alpha-evidence", "claim": "Claim", "citation_key": "alpha", "quote": "The source passage.", "locator": "p. 4", "study_type": "Observational", "sample": "n=40", "findings": "Finding", "limitations": "Small sample", "relevance": "Directly addresses the claim", "status": "supported_by_author"}], "reproducibility": {"data_availability": "data.csv", "code_availability": "analysis.R", "software": "R 4.4", "analysis_steps": "Run analysis.R", "preregistration": "https://osf.io/example", "notes": "Review before submission"}}
HTTP 200
[Asserts]
jsonpath "$.version" == 1
jsonpath "$.quality.citations_without_evidence" == ["beta"]

# An outdated save is rejected with the current version.
PUT {{base}}/api/v1/workspaces/{{research_ws}}/research
Authorization: Bearer {{owner_token}}
{"base_version": 0, "items": [], "reproducibility": {}}
HTTP 409
[Asserts]
jsonpath "$.code" == "version_conflict"
jsonpath "$.current_version" == 1

POST {{base}}/api/v1/workspaces/{{research_ws}}/invites
Authorization: Bearer {{owner_token}}
{"role": "viewer", "expires_in_hours": 24, "max_uses": 1}
HTTP 201
[Captures]
research_viewer_invite: jsonpath "$.token"

POST {{base}}/api/v1/invites/accept
Authorization: Bearer {{member_token}}
{"token": "{{research_viewer_invite}}"}
HTTP 200

GET {{base}}/api/v1/workspaces/{{research_ws}}/research
Authorization: Bearer {{member_token}}
HTTP 200
[Asserts]
jsonpath "$.role" == "viewer"
jsonpath "$.items[0].citation_key" == "alpha"

PUT {{base}}/api/v1/workspaces/{{research_ws}}/research
Authorization: Bearer {{member_token}}
{"base_version": 1, "items": [], "reproducibility": {}}
HTTP 403
[Asserts]
jsonpath "$.code" == "edit_forbidden"

GET {{base}}/api/v1/workspaces/{{research_ws}}/archive
Authorization: Bearer {{owner_token}}
HTTP 200
[Asserts]
header "Content-Type" == "application/zip"

DELETE {{base}}/api/v1/workspaces/{{research_ws}}
Authorization: Bearer {{owner_token}}
HTTP 204
