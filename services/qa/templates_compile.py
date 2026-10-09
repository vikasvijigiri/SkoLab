"""Compile every editor template through a running API, the way the web app does.

    python services/qa/templates_compile.py --base-url http://127.0.0.1:8080 \
        --token "$TOKEN" --out qa-reports/templates

Each template in apps/web/src/editor/templates must come back "compiled" with
a PDF; any other answer fails the run and prints the compiler's errors. The
PDFs are written to --out so a reviewer can open them from the run's
artifacts. Also checks the compiler resolved cross-references (no "??" left
by a single pass) where the template uses \\ref.
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

TEMPLATES = Path(__file__).resolve().parents[2] / "apps" / "web" / "src" / "editor" / "templates"


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

    templates = sorted(TEMPLATES.glob("*.tex"))
    if not templates:
        print(f"::error::no templates found in {TEMPLATES}")
        return 1
    failures = 0
    for path in templates:
        source = path.read_text(encoding="utf-8")
        started = time.monotonic()
        status, result = compile_source(args.base_url.rstrip("/"), args.token, source)
        # One compile at a time per user: wait out a 429 from the previous one.
        while status == 429 and time.monotonic() - started < 60:
            time.sleep(2)
            status, result = compile_source(args.base_url.rstrip("/"), args.token, source)
        seconds = time.monotonic() - started
        if status == 200 and result.get("status") == "compiled" and result.get("pdf_base64"):
            pdf = base64.b64decode(result["pdf_base64"])
            (args.out / f"{path.stem}.pdf").write_bytes(pdf)
            log = result.get("log", "")
            unresolved = "\\ref{" in source and "LaTeX Warning: Reference" in log and "undefined" in log
            if unresolved:
                failures += 1
                print(f"::error file={path}::{path.name}: compiled but references are still undefined after the last pass")
            else:
                print(f"ok   {path.name:28} {len(pdf):>8} bytes  {seconds:5.1f}s")
        else:
            failures += 1
            detail = result.get("errors") or result.get("error") or result
            print(f"::error file={path}::{path.name}: HTTP {status}, {result.get('status')}: {detail}")
            print(result.get("log", "")[-3000:])
    print(f"{len(templates) - failures}/{len(templates)} templates compiled")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
