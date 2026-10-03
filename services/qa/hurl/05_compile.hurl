# Compile: invalid requests are refused before any quota is spent; a valid
# one produces a real PDF.

POST {{base}}/api/v1/colab/compile
Authorization: Bearer {{owner_token}}
{"latex_source": ""}
HTTP 400
[Asserts]
jsonpath "$.code" == "invalid_source"

POST {{base}}/api/v1/colab/compile
Authorization: Bearer {{owner_token}}
{"engine": "pdflatex"}
HTTP 400
[Asserts]
jsonpath "$.code" == "invalid_body"

POST {{base}}/api/v1/colab/compile
Authorization: Bearer {{owner_token}}
{"latex_source": "x", "engine": "lualatex"}
HTTP 400
[Asserts]
jsonpath "$.code" == "invalid_engine"

POST {{base}}/api/v1/colab/compile
Authorization: Bearer {{owner_token}}
```
{bad json
```
HTTP 400

POST {{base}}/api/v1/colab/compile
Authorization: Bearer {{owner_token}}
{"latex_source": "\\documentclass{article}\\begin{document}QA suite.\\end{document}"}
HTTP 200
[Asserts]
jsonpath "$.status" == "compiled"
jsonpath "$.pdf_base64" startsWith "JVBER"

# A TeX error is a completed compile with a failure result, not a server error.
POST {{base}}/api/v1/colab/compile
Authorization: Bearer {{owner_token}}
{"latex_source": "\\documentclass{article}\\begin{document}\\undefinedmacro\\end{document}"}
HTTP 200
[Asserts]
jsonpath "$.status" == "error"
jsonpath "$.errors" isCollection
