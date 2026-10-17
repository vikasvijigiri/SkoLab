"""Live check of the AI writing analytics against the real providers.

Signs in as the monitoring account (the synthetic journey's), gets back its
own "AI live check" workspace (a fixed Idempotency-Key: created once, reused
after), writes a short manuscript into it, and then:

1. GET  /api/v1/ai/status                 both features available
2. POST /api/v1/workspaces/:id/ai/improve  a passage with planted errors gets
   verified suggestions whose offsets hold their original text, and the
   math and the citation come back untouched
3. POST /api/v1/workspaces/:id/ai/originality, polled to the end: the
   paragraph copied from a well-known abstract is found and credited

Costs one model call and at most ten searches, within the daily budgets.
Writes a summary for the run (GITHUB_STEP_SUMMARY) and exits 1 on any
failure. Needs SKOLAB_FIREBASE_API_KEY, SKOLAB_SYNTHETIC_EMAIL and
SKOLAB_SYNTHETIC_PASSWORD; --base-url defaults to production.

    python services/qa/ai_live.py --base-url https://skolab-api.onrender.com
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

SIGN_IN = "https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword"
WORKSPACE_KEY = "skolab-ai-live-check-workspace"
WORKSPACE_TITLE = "SkoLab AI live check (automated, do not use)"

# The opening of "Attention Is All You Need" (Vaswani et al., 2017), whose
# abstract OpenAlex holds: the originality check must find it.
COPIED = (
    "The dominant sequence transduction models are based on complex recurrent or "
    "convolutional neural networks that include an encoder and a decoder. The best "
    "performing models also connect the encoder and decoder through an attention mechanism."
)
OWN = (
    "In this short note we describe the calibration of a home-built optical bench that our "
    "group assembled during the spring semester using salvaged laboratory components. "
    "We report the alignment procedure step by step and the residual drift we measured "
    "over three weeks of daily observations in an unheated basement room."
)
FLAWED = r"The measurements was repeated three time and the results \cite{smith2020} shows a clear trend when $T < T_c$, which are consistent with our expectation."

MANUSCRIPT = rf"""\documentclass{{article}}
\begin{{document}}
\section{{Introduction}}
{COPIED}

{OWN}

{FLAWED}
\end{{document}}
"""


def call(method: str, url: str, body=None, token: str | None = None, headers: dict | None = None) -> tuple[int, dict]:
    request = urllib.request.Request(
        url,
        method=method,
        data=json.dumps(body).encode() if body is not None else None,
        headers={
            "Content-Type": "application/json",
            **({"Authorization": f"Bearer {token}"} if token else {}),
            **(headers or {}),
        },
    )
    try:
        # Generous: a sleeping free-tier service cold-starts.
        with urllib.request.urlopen(request, timeout=120) as response:
            raw = response.read()
            return response.status, json.loads(raw) if raw else {}
    except urllib.error.HTTPError as exc:
        raw = exc.read()
        try:
            return exc.code, json.loads(raw) if raw else {}
        except ValueError:
            return exc.code, {}


class Failed(Exception):
    pass


def expect(condition: bool, message: str) -> None:
    if not condition:
        raise Failed(message)


def utf16_slice(text: str, start: int, end: int) -> str:
    encoded = text.encode("utf-16-le")
    return encoded[start * 2 : end * 2].decode("utf-16-le")


def run(base: str, api_key: str, email: str, password: str) -> list[str]:
    lines: list[str] = []
    status, login = call("POST", f"{SIGN_IN}?key={urllib.parse.quote(api_key)}", {"email": email, "password": password, "returnSecureToken": True})
    expect(status == 200, f"Firebase sign-in: HTTP {status}")
    token, uid = login["idToken"], login["localId"]
    call("POST", f"{base}/api/v1/users/profile/sync", {"uid": uid, "name": "SkoLab monitoring"}, token)
    status, workspace = call("POST", f"{base}/api/v1/workspaces", {"title": WORKSPACE_TITLE}, token, {"Idempotency-Key": WORKSPACE_KEY})
    expect(status in (200, 201), f"workspace: HTTP {status}")
    ws = workspace["id"]
    status, doc = call("GET", f"{base}/api/v1/workspaces/{ws}/document", token=token)
    expect(status == 200, f"read document: HTTP {status}")
    if doc.get("source") != MANUSCRIPT:
        status, _ = call("PUT", f"{base}/api/v1/workspaces/{ws}/document", {"source": MANUSCRIPT, "base_version": doc.get("version", 0)}, token)
        expect(status == 200, f"save document: HTTP {status}")

    status, ai = call("GET", f"{base}/api/v1/ai/status", token=token)
    expect(status == 200, f"status: HTTP {status}")
    expect(ai["improve"]["available"] and ai["originality"]["available"], f"features unavailable: {ai}")
    lines.append(f"- Status: improver {ai['improve']['left_today']} left, originality {ai['originality']['left_today']} left today")

    started = time.monotonic()
    status, improved = call("POST", f"{base}/api/v1/workspaces/{ws}/ai/improve", {"text": FLAWED, "goal": "grammar"}, token)
    took = time.monotonic() - started
    expect(status == 200, f"improve: HTTP {status} {improved}")
    suggestions = improved["suggestions"]
    expect(len(suggestions) >= 1, "improve: no suggestions for a passage with planted errors")
    for s in suggestions:
        expect(utf16_slice(FLAWED, s["start"], s["end"]) == s["original"], f"improve: offsets do not hold {s['original']!r}")
        if r"\cite{smith2020}" in s["original"]:
            expect(r"\cite{smith2020}" in s["replacement"], "improve: the citation was changed")
        if "$T < T_c$" in s["original"]:
            expect("$T < T_c$" in s["replacement"], "improve: the math was changed")
    lines.append(f"- Improver ({improved['model']}, {took:.1f} s): {len(suggestions)} verified suggestions")
    lines.extend(f"  - `{s['original']}` → `{s['replacement']}` ({s['category']})" for s in suggestions[:6])

    status, started_check = call("POST", f"{base}/api/v1/workspaces/{ws}/ai/originality", token=token)
    expect(status == 202, f"originality: HTTP {status} {started_check}")
    report_id = started_check["report"]["id"]
    deadline = time.monotonic() + 180
    report: dict = {}
    while time.monotonic() < deadline:
        status, report = call("GET", f"{base}/api/v1/workspaces/{ws}/ai/originality/{report_id}", token=token)
        expect(status == 200, f"originality report: HTTP {status}")
        if report["status"] in ("done", "failed"):
            break
        time.sleep(3)
    expect(report.get("status") == "done", f"originality: ended {report.get('status')}: {report.get('error')}")
    result = report["result"]
    titles = [s["title"] for s in result["sources"]]
    lines.append(
        f"- Originality: {result['score']}% matched, {result['probes']} searches, {result['sources_checked']} works compared{' (partial)' if result['partial'] else ''}"
    )
    lines.extend(f"  - {s['title']} ({s.get('year', '')}): {s['sentences']} sentence(s)" for s in result["sources"][:5])
    expect(result["score"] > 0 and any("attention" in t.lower() for t in titles), f"originality: the copied abstract was not found (sources: {titles})")
    expect(not any(OWN.split(".")[0] in m["sentence"] for m in result["matches"]), "originality: original prose was reported as matched")
    return lines


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base-url", default="https://skolab-api.onrender.com")
    args = parser.parse_args()
    missing = [k for k in ("SKOLAB_FIREBASE_API_KEY", "SKOLAB_SYNTHETIC_EMAIL", "SKOLAB_SYNTHETIC_PASSWORD") if not os.environ.get(k)]
    if missing:
        print("Missing: " + ", ".join(missing), file=sys.stderr)
        return 2
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    try:
        lines = run(args.base_url.rstrip("/"), os.environ["SKOLAB_FIREBASE_API_KEY"], os.environ["SKOLAB_SYNTHETIC_EMAIL"], os.environ["SKOLAB_SYNTHETIC_PASSWORD"])
        ok, text = True, "### AI live check passed\n\n" + "\n".join(lines)
    except (Failed, KeyError, urllib.error.URLError) as exc:
        ok, text = False, f"### AI live check failed\n\n{exc}"
    print(text)
    if summary:
        with open(summary, "a", encoding="utf-8") as handle:
            handle.write(text + "\n")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
