"""Compile every editor template through a running API, the way the web app does.

    python services/qa/templates_compile.py --base-url http://127.0.0.1:8080 \
        --token "$TOKEN" --out qa-reports/templates

Reads the catalog from GET /api/v1/templates, fetches each template's source
from GET /api/v1/templates/{id}, and sends it to POST /api/v1/colab/compile.
Each must come back "compiled" with a PDF; any other answer fails the run and
prints the compiler's errors. The PDFs are written to --out so a reviewer can
open them from the run's artifacts. Also checks the compiler resolved
cross-references (no "??" left by a single pass) where the template uses \\ref.
"""
from __future__ import annotations

import argparse
import base64
import json
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path


def get_json(base_url: str, token: str, path: str) -> dict:
    request = urllib.request.Request(f"{base_url}{path}", headers={"Authorization": f"Bearer {token}"})
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)


def compile_source(base_url: str, token: str, source: str) -> tuple[int, dict]:
    body = json.dumps({"latex_source": source, "engine": "pdflatex"}).encode()
    request = urllib.request.Request(
        f"{base_url}/api/v1/colab/compile",
        data=body,
        method="POST",
        headers={"Content-Type": "application/json", "Authorization": f"Bearer {token}"},
    )
    try:
        with urllib.request.urlopen(request, timeout=90) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as error:
        return error.code, json.loads(error.read() or b"{}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--token", required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)

    base = args.base_url.rstrip("/")
    templates = get_json(base, args.token, "/api/v1/templates")["templates"]
    if not templates:
        print("::error::the template catalog is empty")
        return 1
    failures = 0
    for summary in templates:
        name = f"{summary['domain']}/{summary['id']}"
        source = get_json(base, args.token, f"/api/v1/templates/{summary['id']}")["source"]
        started = time.monotonic()
        status, result = compile_source(base, args.token, source)
        # One compile at a time per user: wait out a 429 from the previous one.
        while status == 429 and time.monotonic() - started < 60:
            time.sleep(2)
            status, result = compile_source(base, args.token, source)
        seconds = time.monotonic() - started
        if status == 200 and result.get("status") == "compiled" and result.get("pdf_base64"):
            pdf = base64.b64decode(result["pdf_base64"])
            (args.out / f"{summary['id']}.pdf").write_bytes(pdf)
            log = result.get("log", "")
            unresolved = "\\ref{" in source and "LaTeX Warning: Reference" in log and "undefined" in log
            if unresolved:
                failures += 1
                print(f"::error::{name}: compiled but references are still undefined after the last pass")
            else:
                print(f"ok   {name:36} {len(pdf):>8} bytes  {seconds:5.1f}s")
        else:
            failures += 1
            detail = result.get("errors") or result.get("error") or result
            print(f"::error::{name}: HTTP {status}, {result.get('status')}: {detail}")
            print(result.get("log", "")[-3000:])
    print(f"{len(templates) - failures}/{len(templates)} templates compiled")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
