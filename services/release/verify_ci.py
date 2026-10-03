"""Require the exact commit's test/security jobs before a manual deployment."""

from __future__ import annotations

import argparse
import json
import os
import urllib.request

REQUIRED = {"build-and-test", "images", "staging", "slo-rules"}


def passed(jobs: list[dict]) -> bool:
    completed = {job["name"] for job in jobs if job.get("conclusion") == "success"}
    security = any(name.startswith("security /") for name in completed)
    return REQUIRED <= completed and security


def get(path: str) -> dict:
    req = urllib.request.Request(
        "https://api.github.com" + path,
        headers={
            "Authorization": "Bearer " + os.environ["GH_TOKEN"],
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
        },
    )
    with urllib.request.urlopen(req, timeout=30) as response:
        return json.load(response)


def verified(repo: str, commit: str, fetch=get) -> bool:
    runs = fetch(
        f"/repos/{repo}/actions/workflows/ci.yml/runs?head_sha={commit}&per_page=100"
    )
    for run in runs["workflow_runs"]:
        if run["head_sha"] != commit or run["event"] != "push":
            continue
        jobs = []
        page = 1
        while True:
            result = fetch(
                f"/repos/{repo}/actions/runs/{run['id']}/jobs?per_page=100&page={page}"
            )
            jobs.extend(result["jobs"])
            if len(jobs) >= result["total_count"]:
                break
            page += 1
        if passed(jobs):
            return True
    return False


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--commit", required=True)
    args = parser.parse_args()
    if not verified(os.environ["GITHUB_REPOSITORY"], args.commit):
        raise SystemExit(
            "Manual release refused: exact commit lacks successful CI test/security jobs"
        )
    print("Exact commit passed required CI jobs")
