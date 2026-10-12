# Project files: folders, text files and uploads beside main.tex, a
# multi-file compile (\input, \includegraphics, BibTeX) whose PDF the project
# keeps, the .zip download, and a new project imported from a .zip.

POST {{base}}/api/v1/workspaces
Authorization: Bearer {{owner_token}}
{"title": "QA project files"}
HTTP 201
[Captures]
ws: jsonpath "$.id"

GET {{base}}/api/v1/workspaces/{{ws}}/files
Authorization: Bearer {{owner_token}}
HTTP 200
[Asserts]
header "Cache-Control" == "no-store"
jsonpath "$.role" == "owner"
jsonpath "$.main.path" == "main.tex"
jsonpath "$.main.version" == 0
jsonpath "$.files" count == 0
jsonpath "$.output" == null
jsonpath "$.usage.limit_bytes" == 10485760
jsonpath "$.usage.limit_entries" == 200

# ── Text files and folders ────────────────────────────────────────────────
POST {{base}}/api/v1/workspaces/{{ws}}/files
Authorization: Bearer {{owner_token}}
{"path": "chapters/intro.tex", "kind": "text", "content": "The introduction."}
HTTP 201
[Captures]
intro: jsonpath "$.id"
[Asserts]
header "ETag" == "\"v1\""
jsonpath "$.kind" == "text"
jsonpath "$.version" == 1
jsonpath "$.size" == 17
jsonpath "$.content" == "The introduction."
jsonpath "$.updated_by" == "{{owner_uid}}"

POST {{base}}/api/v1/workspaces/{{ws}}/files
Authorization: Bearer {{owner_token}}
{"path": "Chapters/INTRO.tex", "kind": "text"}
HTTP 409
[Asserts]
jsonpath "$.code" == "file_exists"

POST {{base}}/api/v1/workspaces/{{ws}}/files
Authorization: Bearer {{owner_token}}
{"path": "main.tex", "kind": "text"}
HTTP 400
[Asserts]
jsonpath "$.code" == "reserved_path"

POST {{base}}/api/v1/workspaces/{{ws}}/files
Authorization: Bearer {{owner_token}}
{"path": "../escape.tex", "kind": "text"}
HTTP 400
[Asserts]
jsonpath "$.code" == "invalid_path"

POST {{base}}/api/v1/workspaces/{{ws}}/files
Authorization: Bearer {{owner_token}}
{"path": "drafts", "kind": "folder"}
HTTP 201
[Captures]
drafts: jsonpath "$.id"
[Asserts]
jsonpath "$.kind" == "folder"
jsonpath "$.size" == 0

# ── Uploads ───────────────────────────────────────────────────────────────
POST {{base}}/api/v1/workspaces/{{ws}}/files/upload
Authorization: Bearer {{owner_token}}
[Multipart]
folder: fig
file: file,fixtures/dot.png; image/png
HTTP 201
[Captures]
dot: jsonpath "$.files[0].id"
[Asserts]
jsonpath "$.files" count == 1
jsonpath "$.files[0].path" == "fig/dot.png"
jsonpath "$.files[0].kind" == "binary"
jsonpath "$.files[0].content_type" == "image/png"

POST {{base}}/api/v1/workspaces/{{ws}}/files/upload
Authorization: Bearer {{owner_token}}
[Multipart]
folder: fig
file: file,fixtures/dot.png; image/png
HTTP 409
[Asserts]
jsonpath "$.code" == "file_exists"

POST {{base}}/api/v1/workspaces/{{ws}}/files/upload
Authorization: Bearer {{owner_token}}
[Multipart]
folder: fig
replace: true
file: file,fixtures/dot.png; image/png
HTTP 201
[Asserts]
jsonpath "$.files[0].id" == "{{dot}}"
jsonpath "$.files[0].version" == 2

# An SVG renamed .png is not an image TeX can use (nor one to serve).
POST {{base}}/api/v1/workspaces/{{ws}}/files/upload
Authorization: Bearer {{owner_token}}
[Multipart]
file: file,fixtures/not-a-png.png; image/png
HTTP 415
[Asserts]
jsonpath "$.code" == "unsupported_file_type"

GET {{base}}/api/v1/workspaces/{{ws}}/files/{{dot}}/raw
Authorization: Bearer {{owner_token}}
HTTP 200
[Asserts]
header "Content-Type" == "image/png"
header "Content-Disposition" == "inline; filename=dot.png"
header "X-Content-Type-Options" == "nosniff"
header "Content-Security-Policy" contains "sandbox"
bytes startsWith hex,89504e470d0a1a0a;

GET {{base}}/api/v1/workspaces/{{ws}}/files/{{drafts}}/raw
Authorization: Bearer {{owner_token}}
HTTP 400
[Asserts]
jsonpath "$.code" == "not_a_file"

# ── Saving, moving, deleting ──────────────────────────────────────────────
PUT {{base}}/api/v1/workspaces/{{ws}}/files/{{intro}}
Authorization: Bearer {{owner_token}}
{"content": "The introduction, revised.", "base_version": 1}
HTTP 200
[Asserts]
header "ETag" == "\"v2\""
jsonpath "$.version" == 2

PUT {{base}}/api/v1/workspaces/{{ws}}/files/{{intro}}
Authorization: Bearer {{owner_token}}
{"content": "stale", "base_version": 1}
HTTP 409
[Asserts]
jsonpath "$.code" == "version_conflict"
jsonpath "$.current_version" == 2

PUT {{base}}/api/v1/workspaces/{{ws}}/files/{{dot}}
Authorization: Bearer {{owner_token}}
{"content": "x", "base_version": 2}
HTTP 400
[Asserts]
jsonpath "$.code" == "not_a_text_file"

PATCH {{base}}/api/v1/workspaces/{{ws}}/files/{{dot}}
Authorization: Bearer {{owner_token}}
{"path": "drafts/old.png"}
HTTP 200
[Asserts]
jsonpath "$.path" == "drafts/old.png"

PATCH {{base}}/api/v1/workspaces/{{ws}}/files/{{drafts}}
Authorization: Bearer {{owner_token}}
{"path": "figures"}
HTTP 200

GET {{base}}/api/v1/workspaces/{{ws}}/files/{{dot}}
Authorization: Bearer {{owner_token}}
HTTP 200
[Asserts]
jsonpath "$.path" == "figures/old.png"

PATCH {{base}}/api/v1/workspaces/{{ws}}/files/{{dot}}
Authorization: Bearer {{owner_token}}
{"path": "figures/dot.png"}
HTTP 200

PATCH {{base}}/api/v1/workspaces/{{ws}}/files/{{drafts}}
Authorization: Bearer {{owner_token}}
{"path": "figures/inside"}
HTTP 400
[Asserts]
jsonpath "$.code" == "invalid_path"

# ── Roles ─────────────────────────────────────────────────────────────────
GET {{base}}/api/v1/workspaces/{{ws}}/files
Authorization: Bearer {{member_token}}
HTTP 404
[Asserts]
jsonpath "$.code" == "not_found"

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

GET {{base}}/api/v1/workspaces/{{ws}}/files/{{intro}}
Authorization: Bearer {{member_token}}
HTTP 200
[Asserts]
jsonpath "$.content" == "The introduction, revised."

POST {{base}}/api/v1/workspaces/{{ws}}/files
Authorization: Bearer {{member_token}}
{"path": "viewer.tex", "kind": "text"}
HTTP 403
[Asserts]
jsonpath "$.code" == "edit_forbidden"

DELETE {{base}}/api/v1/workspaces/{{ws}}/files/{{intro}}
Authorization: Bearer {{member_token}}
HTTP 403

# ── Compile the whole project ─────────────────────────────────────────────
PUT {{base}}/api/v1/workspaces/{{ws}}/document
Authorization: Bearer {{owner_token}}
{"source": "\\documentclass{article}\n\\usepackage{graphicx}\n\\begin{document}\n\\input{chapters/intro}\n\\includegraphics[width=1cm]{figures/dot.png}\nAs shown by \\cite{knuth1984}.\n\\bibliographystyle{plain}\n\\bibliography{refs}\n\\end{document}\n", "base_version": 0}
HTTP 200

POST {{base}}/api/v1/workspaces/{{ws}}/files
Authorization: Bearer {{owner_token}}
{"path": "refs.bib", "kind": "text", "content": "@book{knuth1984, author={Donald Knuth}, title={The {\\TeX}book}, publisher={Addison-Wesley}, year={1984}}\n"}
HTTP 201

GET {{base}}/api/v1/workspaces/{{ws}}/output.pdf
Authorization: Bearer {{owner_token}}
HTTP 404
[Asserts]
jsonpath "$.code" == "no_output"

# Any member may compile; the viewer's PDF is kept for everyone.
POST {{base}}/api/v1/workspaces/{{ws}}/compile
Authorization: Bearer {{member_token}}
HTTP 200
[Asserts]
jsonpath "$.status" == "compiled"
jsonpath "$.pdf_base64" isString
# BibTeX ran: the citation resolved, so no "Citation undefined" warning.
jsonpath "$.log" not contains "Citation `knuth1984' on page 1 undefined"

GET {{base}}/api/v1/workspaces/{{ws}}/output.pdf
Authorization: Bearer {{owner_token}}
HTTP 200
[Asserts]
header "Content-Type" == "application/pdf"
header "Content-Disposition" == "attachment; filename=\"QA project files.pdf\""
bytes startsWith hex,255044462d;

GET {{base}}/api/v1/workspaces/{{ws}}/files
Authorization: Bearer {{owner_token}}
HTTP 200
[Asserts]
jsonpath "$.main.version" == 1
jsonpath "$.output.status" == "compiled"
jsonpath "$.output.compiled_by" == "{{member_uid}}"
jsonpath "$.files[*].path" includes "figures/dot.png"
jsonpath "$.files[*].path" includes "chapters/intro.tex"

GET {{base}}/api/v1/workspaces/{{ws}}/archive
Authorization: Bearer {{member_token}}
HTTP 200
[Asserts]
header "Content-Type" == "application/zip"
header "Content-Disposition" == "attachment; filename=\"QA project files.zip\""
bytes startsWith hex,504b0304;

# Deleting a folder deletes what is in it.
DELETE {{base}}/api/v1/workspaces/{{ws}}/files/{{drafts}}
Authorization: Bearer {{owner_token}}
HTTP 204

GET {{base}}/api/v1/workspaces/{{ws}}/files/{{dot}}
Authorization: Bearer {{owner_token}}
HTTP 404

DELETE {{base}}/api/v1/workspaces/{{ws}}
Authorization: Bearer {{owner_token}}
HTTP 204

# ── Import a project from a .zip ──────────────────────────────────────────
POST {{base}}/api/v1/workspaces/import
Authorization: Bearer {{owner_token}}
[Multipart]
file: file,fixtures/project.zip; application/zip
HTTP 201
[Captures]
imported: jsonpath "$.id"
[Asserts]
header "Location" startsWith "/api/v1/workspaces/"
jsonpath "$.title" == "project"
jsonpath "$.role" == "owner"
jsonpath "$.skipped" includes "notes.docx"
jsonpath "$.skipped" count == 1

GET {{base}}/api/v1/workspaces/{{imported}}/files
Authorization: Bearer {{owner_token}}
HTTP 200
[Asserts]
jsonpath "$.main.version" == 1
jsonpath "$.files[*].path" includes "figures/dot.png"
jsonpath "$.files[*].path" includes "sections/intro.tex"
jsonpath "$.files[*].path" includes "refs.bib"

GET {{base}}/api/v1/workspaces/{{imported}}/document
Authorization: Bearer {{owner_token}}
HTTP 200
[Asserts]
jsonpath "$.source" contains "\\input{sections/intro}"

POST {{base}}/api/v1/workspaces/import
Authorization: Bearer {{owner_token}}
[Multipart]
file: file,fixtures/dot.png; application/zip
HTTP 400
[Asserts]
jsonpath "$.code" == "invalid_archive"

DELETE {{base}}/api/v1/workspaces/{{imported}}
Authorization: Bearer {{owner_token}}
HTTP 204
