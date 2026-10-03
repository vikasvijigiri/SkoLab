"""Temporary, verified Firebase test users for the QA suite.

The QA tools (Hurl, Schemathesis) need several real signed-in identities --
an owner, a second member, an outsider, and one whose account gets deleted
-- which no tool provides. This creates them with the Firebase Admin SDK,
email-verified (the API refuses unverified email accounts), signs each in,
and writes their ID tokens and uids as Hurl variables. Cleanup deletes them
through the API's own account deletion (DELETE /api/v1/users/{uid}: data and
sign-in), so no rows are left behind, then makes sure the identity is gone.

    python services/qa/users.py create --base-url URL --out qa.env
    python services/qa/users.py cleanup --base-url URL --state qa.state.json

Names start with "qa-": `cleanup` also removes qa- users older than two
hours left by an aborted run. Tokens are masked in GitHub Actions logs.
Needs FIREBASE_SERVICE_ACCOUNT (Admin SDK JSON) and SKOLAB_FIREBASE_API_KEY.
"""
from __future__ import annotations

import argparse
import json
import os
import secrets
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

ROLES = ("owner", "member", "outsider", "doomed")
PREFIX = "qa-"
IDENTITY = "https://identitytoolkit.googleapis.com/v1/accounts"


def admin():
    import firebase_admin
    from firebase_admin import auth, credentials

    try:
        app = firebase_admin.get_app("skolab-qa")
    except ValueError:
        app = firebase_admin.initialize_app(
            credentials.Certificate(json.loads(os.environ["FIREBASE_SERVICE_ACCOUNT"])), name="skolab-qa")
    return auth, app


def post(url: str, body: dict, token: str | None = None, method: str = "POST") -> tuple[int, dict]:
    request = urllib.request.Request(
        url, method=method, data=json.dumps(body).encode() if body is not None else None,
        headers={"Content-Type": "application/json", **({"Authorization": f"Bearer {token}"} if token else {})})
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            raw = response.read()
            return response.status, json.loads(raw) if raw else {}
    except urllib.error.HTTPError as exc:
        return exc.code, {}


def sign_in(api_key: str, email: str, password: str) -> dict:
    status, body = post(f"{IDENTITY}:signInWithPassword?key={urllib.parse.quote(api_key)}",
                        {"email": email, "password": password, "returnSecureToken": True})
    if status != 200:
        raise RuntimeError(f"sign-in failed for {email}: HTTP {status}")
    return body


def sign_in_as(api_key: str, uid: str) -> str:
    """An ID token for an existing user without its password (custom token)."""
    auth, app = admin()
    custom = auth.create_custom_token(uid, app=app).decode()
    status, body = post(f"{IDENTITY}:signInWithCustomToken?key={urllib.parse.quote(api_key)}",
                        {"token": custom, "returnSecureToken": True})
    if status != 200:
        raise RuntimeError(f"custom sign-in failed for {uid}: HTTP {status}")
    return body["idToken"]


def mask(value: str) -> None:
    if os.environ.get("GITHUB_ACTIONS"):
        print(f"::add-mask::{value}")


def create(base: str, out: str, state: str) -> None:
    auth, app = admin()
    api_key = os.environ["SKOLAB_FIREBASE_API_KEY"]
    run = f"{int(time.time())}-{secrets.token_hex(3)}"
    variables, created = {"base": base.rstrip("/"), "unknown_id": "00000000-0000-4000-8000-000000000000"}, []
    for role in ROLES:
        email, password = f"{PREFIX}{run}-{role}@skolab-qa.example.com", secrets.token_urlsafe(24)
        user = auth.create_user(email=email, password=password, email_verified=True,
                                display_name=f"QA {role}", app=app)
        created.append(user.uid)
        token = sign_in(api_key, email, password)["idToken"]
        mask(token)
        variables[f"{role}_token"], variables[f"{role}_uid"] = token, user.uid
    with open(state, "w", encoding="utf-8") as f:
        json.dump({"base": base, "uids": created}, f)
    with open(out, "w", encoding="utf-8") as f:
        f.writelines(f"{key}={value}\n" for key, value in variables.items())
    print(f"Created {len(created)} temporary QA users (run {run}).")


def remove(base: str, api_key: str, uid: str) -> str:
    """Delete through the API (data and sign-in), then make sure the
    identity is gone even if the API step could not run."""
    auth, app = admin()
    outcome = "already gone"
    try:
        token = sign_in_as(api_key, uid)
        status, _ = post(f"{base.rstrip('/')}/api/v1/users/{uid}", None, token, method="DELETE")
        outcome = f"API delete {status}"
    except Exception as exc:  # noqa: BLE001 - cleanup must continue
        outcome = f"API delete skipped ({type(exc).__name__})"
    try:
        auth.delete_user(uid, app=app)
    except auth.UserNotFoundError:
        pass
    return outcome


def cleanup(base: str, state: str | None) -> None:
    auth, app = admin()
    api_key = os.environ["SKOLAB_FIREBASE_API_KEY"]
    uids = []
    if state and os.path.exists(state):
        with open(state, encoding="utf-8") as f:
            uids = json.load(f)["uids"]
    cutoff = (time.time() - 2 * 3600) * 1000
    for user in auth.list_users(app=app).iterate_all():
        if (user.email or "").startswith(PREFIX) and user.user_metadata.creation_timestamp < cutoff:
            uids.append(user.uid)  # left behind by an aborted run
    for uid in dict.fromkeys(uids):
        print(f"cleanup {uid[:6]}...: {remove(base, api_key, uid)}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("action", choices=["create", "cleanup"])
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--out", default="qa.env")
    parser.add_argument("--state", default="qa.state.json")
    args = parser.parse_args()
    for key in ("FIREBASE_SERVICE_ACCOUNT", "SKOLAB_FIREBASE_API_KEY"):
        if not os.environ.get(key):
            print(f"Missing {key}", file=sys.stderr)
            return 2
    if args.action == "create":
        create(args.base_url, args.out, args.state)
    else:
        cleanup(args.base_url, args.state)
    return 0


if __name__ == "__main__":
    sys.exit(main())
