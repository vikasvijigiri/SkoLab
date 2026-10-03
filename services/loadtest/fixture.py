"""Sign the monitoring account in to a staging API and write what the load
test needs to $GITHUB_ENV: TOKEN (masked in the log), USER_ID, WORKSPACE_ID.

    python services/loadtest/fixture.py --base-url http://127.0.0.1:8080
"""
from __future__ import annotations

import argparse
import os
import sys
import urllib.parse
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "observability"))
from provision import FIREBASE_SIGN_IN, ensure_monitoring_workspace, request_json


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--base-url", required=True)
    args = parser.parse_args()
    key, email, password = (os.environ[k] for k in
                            ("SKOLAB_FIREBASE_API_KEY", "SKOLAB_SYNTHETIC_EMAIL", "SKOLAB_SYNTHETIC_PASSWORD"))
    workspace = ensure_monitoring_workspace(key, email, password, args.base_url.rstrip("/"))
    login = request_json("Firebase sign-in", "POST", f"{FIREBASE_SIGN_IN}?key={urllib.parse.quote(key)}",
                         {"email": email, "password": password, "returnSecureToken": True})
    print(f"::add-mask::{login['idToken']}")
    with open(os.environ["GITHUB_ENV"], "a", encoding="utf-8") as env:
        env.write(f"TOKEN={login['idToken']}\nUSER_ID={login['localId']}\nWORKSPACE_ID={workspace}\n")
    print("Load test fixture ready (token valid for one hour).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
