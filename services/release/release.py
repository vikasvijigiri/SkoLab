"""Deploy master to Render, wait until it is live, then smoke-test production.

Runs as the last job of CI on master (.github/workflows/release.yml), so a
merge is finished only when the commit is serving traffic and the checks
below pass against the real services. Nothing runs on a developer machine.

  python services/release/release.py --commit <sha>

Order matters: skolab-backend-py runs the alembic migrations on start, so it
goes live before the gateway that reads the new tables. A service whose
directory did not change since its live commit is not rebuilt.
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "observability"))
from provision import (
    FIREBASE_SIGN_IN,
    ensure_monitoring_workspace,
    request_json,
)

RENDER_API = "https://api.render.com/v1"
GATEWAY = os.environ.get("SKOLAB_GATEWAY_URL", "https://skolab-gateway.onrender.com").rstrip("/")
BACKEND = os.environ.get("SKOLAB_BACKEND_URL", "https://skolab-backend-py.onrender.com").rstrip("/")

# (Render service name, the source directory its image is built from)
SERVICES = [("skolab-backend-py", "services/backend"), ("skolab-gateway", "services/backend-go")]

IN_PROGRESS = {"created", "queued", "build_in_progress", "update_in_progress", "pre_deploy_in_progress"}
FAILED = {"build_failed", "update_failed", "pre_deploy_failed"}
SUPERSEDED = {"canceled", "deactivated"}


class Render:
    def __init__(self, token: str):
        self.token = token

    def call(self, method: str, path: str, body=None):
        request = urllib.request.Request(
            RENDER_API + path, method=method, data=json.dumps(body).encode() if body is not None else None,
            headers={"Authorization": f"Bearer {self.token}", "Accept": "application/json",
                     "Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                content = response.read()
                return json.loads(content) if content else None
        except urllib.error.HTTPError as exc:
            # Never print the response body or the key.
            raise RuntimeError(f"Render {method} {path.split('?')[0]}: HTTP {exc.code}") from None

    def service_id(self, name: str) -> str:
        matches = [row["service"]["id"] for row in self.call("GET", f"/services?name={name}&limit=20")
                   if row["service"]["name"] == name]
        if len(matches) != 1:
            raise RuntimeError(f"expected one Render service named {name}, found {len(matches)}")
        return matches[0]

    def deploys(self, service: str) -> list[dict]:
        return [row["deploy"] for row in self.call("GET", f"/services/{service}/deploys?limit=20")]

    def deploy(self, service: str, commit: str) -> dict:
        return self.call("POST", f"/services/{service}/deploys", {"commitId": commit, "clearCache": "do_not_clear"})

    def get_deploy(self, service: str, deploy: str) -> dict:
        return self.call("GET", f"/services/{service}/deploys/{deploy}")


def live_commit(deploys: list[dict]) -> str | None:
    return next((d["commit"]["id"] for d in deploys if d["status"] == "live" and d.get("commit")), None)


def source_changed(since: str | None, commit: str, path: str) -> bool:
    """True unless git proves `path` is identical at both commits."""
    if not since:
        return True
    result = subprocess.run(["git", "diff", "--quiet", since, commit, "--", path], capture_output=True, check=False)
    return result.returncode != 0  # 1 = differs, anything else = unknown -> deploy


def ship(render: Render, name: str, path: str, commit: str, *, timeout: float = 1500,
         poll: float = 15, sleep=time.sleep, clock=time.monotonic, changed=source_changed) -> str:
    """Bring `name` to `commit` (or confirm its live image is equivalent) and
    return what happened. Reuses a deploy of the same commit already running
    (e.g. one Render started itself) instead of starting a second."""
    service = render.service_id(name)
    deploys = render.deploys(service)
    current = live_commit(deploys)
    if current == commit:
        return "already live"
    if not changed(current, commit, path):
        return f"unchanged since {current[:7]}, not rebuilt"

    ours = next((d for d in deploys if d.get("commit", {}).get("id") == commit and d["status"] in IN_PROGRESS), None)
    deploy_id = (ours or render.deploy(service, commit))["id"]
    deadline = clock() + timeout
    while clock() < deadline:
        status = render.get_deploy(service, deploy_id)["status"]
        if status == "live":
            return "deployed"
        if status in FAILED:
            raise RuntimeError(f"{name}: deploy {deploy_id} ended {status} (see its Render logs)")
        if status in SUPERSEDED:
            # Replaced by a newer deploy: follow it if it is the same commit.
            same = [d for d in render.deploys(service) if d.get("commit", {}).get("id") == commit
                    and (d["status"] == "live" or d["status"] in IN_PROGRESS)]
            if not same:
                raise RuntimeError(f"{name}: deploy {deploy_id} was {status} by a deploy of another commit")
            if same[0]["status"] == "live":
                return "deployed"
            deploy_id = same[0]["id"]
        sleep(poll)
    raise RuntimeError(f"{name}: deploy {deploy_id} not live after {timeout:.0f}s")


def status_of(url: str, token: str | None = None, body: dict | None = None) -> int:
    headers = {"Content-Type": "application/json", **({"Authorization": f"Bearer {token}"} if token else {})}
    request = urllib.request.Request(url, method="POST" if body is not None else "GET",
                                     data=json.dumps(body).encode() if body is not None else None, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            return response.status
    except urllib.error.HTTPError as exc:
        return exc.code


def expect(label: str, actual, wanted) -> None:
    if actual != wanted:
        raise RuntimeError(f"smoke: {label}: got {actual!r}, want {wanted!r}")
    print(f"  ok  {label}")


def smoke(api_key: str, email: str, password: str) -> None:
    """Health, an anonymous refusal, and the invite lifecycle as the verified
    monitoring account on its own workspace. The link it creates is revoked."""
    for url in (f"{GATEWAY}/gateway-health", f"{GATEWAY}/readyz", f"{BACKEND}/livez", f"{BACKEND}/health"):
        expect(url.split("//")[1], status_of(url), 200)
    expect("anonymous /api/v1/workspaces refused", status_of(f"{GATEWAY}/api/v1/workspaces"), 401)

    workspace = ensure_monitoring_workspace(api_key, email, password)
    token = request_json("Firebase sign-in", "POST", f"{FIREBASE_SIGN_IN}?key={api_key}",
                         {"email": email, "password": password, "returnSecureToken": True})["idToken"]
    api = f"{GATEWAY}/api/v1"
    expect("signed-in list workspaces", status_of(f"{api}/workspaces", token), 200)

    options = request_json("Invite options", "GET", f"{api}/workspaces/{workspace}/invite-options", token=token)
    expect("invite options offer viewer", "viewer" in options["roles"], True)
    invite = request_json("Create invite", "POST", f"{api}/workspaces/{workspace}/invites",
                          {"role": "viewer", "expires_in_hours": 24, "max_uses": 1}, token)
    try:
        preview = request_json("Preview invite", "POST", f"{api}/invites/preview", {"token": invite["token"]}, token)
        expect("preview shows the granted role", preview["role"], "viewer")
        joined = request_json("Accept invite", "POST", f"{api}/invites/accept", {"token": invite["token"]}, token)
        expect("owner accepting own link is a no-op", (joined["role"], joined["changed"]), ("owner", False))
        members = request_json("List members", "GET", f"{api}/workspaces/{workspace}/members", token=token)["members"]
        expect("owner listed first", members[0]["role"], "owner")
    finally:
        request_json("Revoke invite", "DELETE", f"{api}/workspaces/{workspace}/invites/{invite['id']}", token=token)
    expect("revoked link is invalid", status_of(f"{api}/invites/preview", token, {"token": invite["token"]}), 404)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--commit", required=True)
    parser.add_argument("--smoke-only", action="store_true", help="skip deploying; only run the smoke checks")
    args = parser.parse_args()

    missing = [k for k in ("RENDER_API_KEY", "SKOLAB_FIREBASE_API_KEY", "SKOLAB_SYNTHETIC_EMAIL",
                           "SKOLAB_SYNTHETIC_PASSWORD") if not os.environ.get(k)]
    if missing:
        print("Missing secrets: " + ", ".join(missing), file=sys.stderr)
        return 2
    if not args.smoke_only:
        render = Render(os.environ["RENDER_API_KEY"])
        for name, path in SERVICES:
            print(f"{name}: {ship(render, name, path, args.commit)}", flush=True)
    print("Smoke checks against production:", flush=True)
    smoke(os.environ["SKOLAB_FIREBASE_API_KEY"], os.environ["SKOLAB_SYNTHETIC_EMAIL"],
          os.environ["SKOLAB_SYNTHETIC_PASSWORD"])
    print(f"Release {args.commit[:7]} is live and healthy.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
