"""Deploy master to Render, wait until it is live, then smoke-test production.

Runs as the last job of CI on master (.github/workflows/release.yml), so a
merge is finished only when the commit is serving traffic and the checks
below pass against the real service. Nothing runs on a developer machine.

  python services/release/release.py --commit <sha>
  python services/release/release.py --commit <sha> --smoke-only [--base-url URL]

Production is one Render service, skolab-api: the Go gateway and the Python
service in one container (deploy/skolab-api). It is not rebuilt when none of
its source directories changed since its live commit.

It replaced two services (skolab-gateway, skolab-backend-py). Render does not
carry secrets over to a service a Blueprint adds, so the first release copies
them from those services (adopt_settings) and, once the new service passes
the smoke checks, suspends them (retire_legacy) -- reversibly; deleting them
is a separate, manual decision. Both steps are no-ops once that is done.
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
SERVICE = "skolab-api"
SOURCES = ("services/backend", "services/backend-go", "deploy/skolab-api", ".dockerignore")
# Replaced services, oldest-precedence first: on a conflicting key the
# gateway's value wins (both must already agree on shared secrets).
LEGACY = ("skolab-backend-py", "skolab-gateway")
# Set per process by deploy/skolab-api/entrypoint.sh or fixed by the image;
# never copied from the replaced services.
PER_PROCESS = {"OTEL_SERVICE_NAME", "PORT", "PYTHON_BACKEND_URL"}

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

    def find_service(self, name: str) -> dict | None:
        matches = [row["service"] for row in self.call("GET", f"/services?name={name}&limit=20")
                   if row["service"]["name"] == name]
        if len(matches) > 1:
            raise RuntimeError(f"expected one Render service named {name}, found {len(matches)}")
        return matches[0] if matches else None

    def _pages(self, path: str, key: str) -> list[dict]:
        items, cursor = [], None
        while True:
            page = self.call("GET", f"{path}?limit=100" + (f"&cursor={cursor}" if cursor else ""))
            items += [row[key] for row in page]
            if len(page) < 100:
                return items
            cursor = page[-1]["cursor"]

    def env_vars(self, service: str) -> dict[str, str]:
        return {item["key"]: item["value"] for item in self._pages(f"/services/{service}/env-vars", "envVar")}

    def set_env_var(self, service: str, key: str, value: str) -> None:
        self.call("PUT", f"/services/{service}/env-vars/{key}", {"value": value})

    def secret_files(self, service: str) -> dict[str, str]:
        return {item["name"]: item["content"] for item in self._pages(f"/services/{service}/secret-files", "secretFile")}

    def set_secret_file(self, service: str, name: str, content: str) -> None:
        self.call("PUT", f"/services/{service}/secret-files/{name}", {"content": content})

    def env_groups(self) -> list[dict]:
        """Every environment group, with the IDs of the services it is linked to."""
        groups = []
        for row in self.call("GET", "/env-groups?limit=100"):
            row = row.get("envGroup", row)  # tolerate both list shapes
            detail = self.call("GET", f"/env-groups/{row['id']}")
            groups.append({"id": row["id"], "name": detail.get("name", row["id"]),
                           "services": [link["id"] for link in detail.get("serviceLinks") or []]})
        return groups

    def link_env_group(self, group: str, service: str) -> None:
        self.call("POST", f"/env-groups/{group}/services/{service}")

    def suspend(self, service: str) -> None:
        self.call("POST", f"/services/{service}/suspend")

    def deploys(self, service: str) -> list[dict]:
        return [row["deploy"] for row in self.call("GET", f"/services/{service}/deploys?limit=20")]

    def deploy(self, service: str, commit: str) -> dict:
        return self.call("POST", f"/services/{service}/deploys", {"commitId": commit, "clearCache": "do_not_clear"})

    def cancel(self, service: str, deploy: str) -> None:
        self.call("POST", f"/services/{service}/deploys/{deploy}/cancel")

    def get_deploy(self, service: str, deploy: str) -> dict:
        return self.call("GET", f"/services/{service}/deploys/{deploy}")

    def recent_logs(self, owner: str, service: str, limit: int = 60) -> list[str]:
        page = self.call("GET", f"/logs?ownerId={owner}&resource={service}&limit={limit}&direction=backward")
        return [entry["message"] for entry in reversed(page.get("logs") or [])]


def wait_for_service(render: Render, name: str, *, timeout: float = 600, poll: float = 15,
                     sleep=time.sleep, clock=time.monotonic) -> dict:
    """The Blueprint creates the service when it syncs this commit, which
    can trail the merge by a minute or two."""
    deadline = clock() + timeout
    while True:
        service = render.find_service(name)
        if service:
            return service
        if clock() >= deadline:
            raise RuntimeError(f"Render service {name} does not exist. Sync the Blueprint "
                               "(Render > Blueprints > skolab.ai > Manual Sync), then re-run this workflow.")
        sleep(poll)


def adopt_settings(render: Render, target: str, legacy=LEGACY) -> list[str]:
    """Give the target what the services it replaced had: link the
    environment groups they were linked to, then copy env vars and secret
    files the target lacks (absent or empty: a Blueprint creates `sync:
    false` keys without values). Values set on the target are never
    overwritten, and a service's own value beats a group's on Render.
    Returns what was adopted, by name; values are never printed."""
    sources = [s for s in (render.find_service(name) for name in legacy) if s]
    if not sources:
        return []
    adopted = []
    source_ids = {source["id"] for source in sources}
    for group in render.env_groups():
        if source_ids & set(group["services"]) and target not in group["services"]:
            render.link_env_group(group["id"], target)
            adopted.append(f"env group {group['name']}")
    have_env, have_files = render.env_vars(target), render.secret_files(target)
    env: dict[str, str] = {}
    files: dict[str, str] = {}
    for source in sources:
        env.update(render.env_vars(source["id"]))
        files.update(render.secret_files(source["id"]))
    copied = adopted
    for key, value in sorted(env.items()):
        if not have_env.get(key) and value and key not in PER_PROCESS:
            render.set_env_var(target, key, value)
            copied.append(key)
    for name, content in sorted(files.items()):
        if not have_files.get(name):
            render.set_secret_file(target, name, content)
            copied.append(f"secret file {name}")
    return copied


def retire_legacy(render: Render, legacy=LEGACY) -> list[str]:
    """Suspend the replaced services so they stop spending free hours.
    Suspension is reversible (Render dashboard > Resume)."""
    retired = []
    for name in legacy:
        service = render.find_service(name)
        if service and service.get("suspended") != "suspended":
            render.suspend(service["id"])
            retired.append(name)
    return retired


def live_commit(deploys: list[dict]) -> str | None:
    return next((d["commit"]["id"] for d in deploys if d["status"] == "live" and d.get("commit")), None)


def source_changed(since: str | None, commit: str, paths=SOURCES) -> bool:
    """True unless git proves every path is identical at both commits."""
    if not since:
        return True
    result = subprocess.run(["git", "diff", "--quiet", since, commit, "--", *paths], capture_output=True, check=False)
    return result.returncode != 0  # 1 = differs, anything else = unknown -> deploy


def ship(render: Render, service: str, commit: str, *, fresh: bool = False, timeout: float = 1500,
         poll: float = 15, sleep=time.sleep, clock=time.monotonic, changed=source_changed,
         owner: str | None = None) -> str:
    """Bring the service to `commit` (or confirm its live image is
    equivalent) and return what happened. Reuses a deploy of the same commit
    already running instead of starting a second -- unless `fresh`: settings
    just changed, so deploys started before that are cancelled."""
    deploys = render.deploys(service)
    current = live_commit(deploys)
    if not fresh:
        if current == commit:
            return "already live"
        if current and not changed(current, commit):
            return f"unchanged since {current[:7]}, not rebuilt"

    running = [d for d in deploys if d["status"] in IN_PROGRESS]
    ours = next((d for d in running if d.get("commit", {}).get("id") == commit), None)
    if fresh:
        for d in running:
            render.cancel(service, d["id"])
        ours = None
    deploy_id = (ours or render.deploy(service, commit))["id"]
    deadline = clock() + timeout
    while clock() < deadline:
        status = render.get_deploy(service, deploy_id)["status"]
        if status == "live":
            return "deployed"
        if status in FAILED:
            print_failure_logs(render, owner, service)
            raise RuntimeError(f"deploy {deploy_id} ended {status}")
        if status in SUPERSEDED:
            # Replaced by a newer deploy: follow it if it is the same commit.
            same = [d for d in render.deploys(service) if d.get("commit", {}).get("id") == commit
                    and (d["status"] == "live" or d["status"] in IN_PROGRESS)]
            if not same:
                raise RuntimeError(f"deploy {deploy_id} was {status} by a deploy of another commit")
            if same[0]["status"] == "live":
                return "deployed"
            deploy_id = same[0]["id"]
        sleep(poll)
    raise RuntimeError(f"deploy {deploy_id} not live after {timeout:.0f}s")


def print_failure_logs(render: Render, owner: str | None, service: str) -> None:
    """Show the service's last log lines, so a failed deploy explains itself
    in the workflow run. Best effort: never masks the original failure."""
    if not owner:
        return
    try:
        lines = render.recent_logs(owner, service)
    except RuntimeError as exc:
        print(f"(could not fetch Render logs: {exc})")
        return
    print("Last log lines from the failed deploy:")
    for line in lines:
        print("  | " + line[:300])


def status_of(url: str, token: str | None = None, body: dict | None = None) -> int:
    headers = {"Content-Type": "application/json", **({"Authorization": f"Bearer {token}"} if token else {})}
    request = urllib.request.Request(url, method="POST" if body is not None else "GET",
                                     data=json.dumps(body).encode() if body is not None else None, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            return response.status
    except urllib.error.HTTPError as exc:
        return exc.code


def oversized_body_status(base: str) -> int:
    """Status for a 2 MiB body (the limit is 1 MiB). Through Render's edge
    the 413 always arrives. Talking to the server directly (CI staging), Go
    answers 413 and closes without reading the rest, so the sender may see
    the connection reset first; then the server must still be up."""
    try:
        return status_of(f"{base}/api/v1/invites/preview", None, {"token": "x" * (2 << 20)})
    except (urllib.error.URLError, ConnectionError) as exc:
        reason = getattr(exc, "reason", exc)
        direct = base.startswith(("http://127.0.0.1:", "http://localhost:"))
        if direct and isinstance(reason, ConnectionError) and status_of(f"{base}/gateway-health") == 200:
            return 413
        raise


def expect(label: str, actual, wanted) -> None:
    if actual != wanted:
        raise RuntimeError(f"smoke: {label}: got {actual!r}, want {wanted!r}")
    print(f"  ok  {label}")


def smoke(base: str, api_key: str, email: str, password: str) -> None:
    """Health (gateway, database, Python), security headers, refusals
    (anonymous, oversized), a real compile, and the invite lifecycle as the
    verified monitoring account on its own workspace. The link it creates is
    revoked."""
    expect("liveness", status_of(f"{base}/gateway-health"), 200)
    ready = request_json("Readiness", "GET", f"{base}/readyz")
    expect("readiness: database and python", (ready["database"], ready["python"]), ("healthy", "healthy"))
    expect("anonymous /api/v1/workspaces refused", status_of(f"{base}/api/v1/workspaces"), 401)
    with urllib.request.urlopen(f"{base}/gateway-health", timeout=120) as response:
        expect("HSTS and nosniff headers", (bool(response.headers.get("Strict-Transport-Security")),
                                            response.headers.get("X-Content-Type-Options")), (True, "nosniff"))
    expect("oversized body refused", oversized_body_status(base), 413)

    workspace = ensure_monitoring_workspace(api_key, email, password, base)
    token = request_json("Firebase sign-in", "POST", f"{FIREBASE_SIGN_IN}?key={api_key}",
                         {"email": email, "password": password, "returnSecureToken": True})["idToken"]
    api = f"{base}/api/v1"
    expect("signed-in list workspaces", status_of(f"{api}/workspaces", token), 200)
    compiled = request_json("Compile", "POST", f"{api}/colab/compile",
                            {"latex_source": r"\documentclass{article}\begin{document}Release check.\end{document}"}, token)
    expect("compile produces a PDF", (compiled["status"], (compiled.get("pdf_base64") or "")[:4]), ("compiled", "JVBE"))

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
    parser.add_argument("--base-url", help="smoke-test this URL instead of the Render service's own")
    args = parser.parse_args()

    needed = ["SKOLAB_FIREBASE_API_KEY", "SKOLAB_SYNTHETIC_EMAIL", "SKOLAB_SYNTHETIC_PASSWORD"]
    if not args.base_url:
        needed.append("RENDER_API_KEY")
    missing = [k for k in needed if not os.environ.get(k)]
    if missing:
        print("Missing secrets: " + ", ".join(missing), file=sys.stderr)
        return 2

    render = Render(os.environ["RENDER_API_KEY"]) if not args.base_url else None
    base = args.base_url
    if render:
        service = wait_for_service(render, SERVICE)
        base = base or service["serviceDetails"]["url"]
        if not args.smoke_only:
            copied = adopt_settings(render, service["id"])
            if copied:
                print(f"{SERVICE}: copied from the replaced services: {', '.join(copied)}", flush=True)
            result = ship(render, service["id"], args.commit, fresh=bool(copied), owner=service.get("ownerId"))
            print(f"{SERVICE}: {result}", flush=True)
    base = base.rstrip("/")
    print(f"Smoke checks against {base}:", flush=True)
    smoke(base, os.environ["SKOLAB_FIREBASE_API_KEY"], os.environ["SKOLAB_SYNTHETIC_EMAIL"],
          os.environ["SKOLAB_SYNTHETIC_PASSWORD"])
    if render and not args.smoke_only:
        retired = retire_legacy(render)
        if retired:
            print(f"Suspended the replaced services: {', '.join(retired)}")
    print(f"Release {args.commit[:7]} is live and healthy at {base}.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
