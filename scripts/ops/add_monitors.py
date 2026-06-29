"""
Reads all GET endpoints from FastAPI's OpenAPI spec and bulk-adds them
as HTTP monitors in Uptime Kuma. Author-parameterised endpoints are seeded
with a real OpenAlex author ID resolved at startup from MONITOR_AUTHOR_NAME
in backend/.env — no IDs hardcoded anywhere.

Usage:
    python add_monitors.py --username admin --password yourpass
"""

import sys
import re
import argparse
from pathlib import Path

import requests
from dotenv import load_dotenv

# ── Load backend/.env so settings picks up MONITOR_AUTHOR_NAME ───────────────
_BACKEND_ENV = Path(__file__).resolve().parent / "backend" / ".env"
load_dotenv(_BACKEND_ENV)

sys.path.insert(0, str(Path(__file__).resolve().parent / "backend"))
from app.core.config import settings
from uptime_kuma_api import UptimeKumaApi, MonitorType

# ── Constants ─────────────────────────────────────────────────────────────────
FASTAPI_BASE = "http://localhost:8000"
UPTIME_KUMA = "http://localhost:3002"
DOCKER_HOST = "http://host.docker.internal:8000"

HEADERS = {"User-Agent": "SkoLab-Monitor/1.0 (internal health check)"}

# 2xx = success; 4xx = server up but missing auth/params
ACCEPTED_CODES = ["200-299", "400-499"]


# ── Resolve author identity from MONITOR_AUTHOR_NAME at module load ───────────
def _resolve_openalex_id(name: str) -> str:
    """Look up the first OpenAlex author match for *name* and return their ID."""
    if not name:
        return ""
    email = settings.openalex_email or "monitor@skolab.local"
    url = "https://api.openalex.org/authors"
    params = {"search": name, "per-page": 1, "mailto": email}
    try:
        resp = requests.get(
            url, params=params, timeout=10, headers={"User-Agent": "SkoLab-Monitor/1.0"}
        )
        resp.raise_for_status()
        results = resp.json().get("results", [])
        if results:
            raw_id = results[0].get("id", "")
            return raw_id.split("/")[-1]
    except Exception as exc:
        print(f"  WARNING: OpenAlex lookup failed for '{name}': {exc}")
    return ""


def _resolve_author_context(author_id: str) -> tuple[str, str]:
    """
    Returns (top_paper_title, top_concept) for the resolved author.
    Used so all monitor params are real, not dummy values.
    """
    email = settings.openalex_email or "monitor@skolab.local"
    hdrs = {"User-Agent": "SkoLab-Monitor/1.0"}
    top_title = ""
    top_concept = ""

    if not author_id:
        return top_title, top_concept

    # Most-cited paper title (strip MathML/XML tags OpenAlex sometimes embeds)
    try:
        r = requests.get(
            "https://api.openalex.org/works",
            params={
                "filter": f"authorships.author.id:{author_id}",
                "sort": "cited_by_count:desc",
                "per_page": 1,
                "mailto": email,
            },
            timeout=10,
            headers=hdrs,
        )
        r.raise_for_status()
        results = r.json().get("results", [])
        if results:
            raw_title = results[0].get("title", "")
            top_title = re.sub(r"<[^>]+>", "", raw_title).strip()
    except Exception as exc:
        print(f"  WARNING: Could not fetch author works: {exc}")

    # Top concept / topic
    try:
        r = requests.get(
            f"https://api.openalex.org/authors/{author_id}",
            params={"mailto": email},
            timeout=10,
            headers=hdrs,
        )
        r.raise_for_status()
        data = r.json()
        topics = data.get("topics") or []
        concepts = data.get("x_concepts") or []
        if topics:
            top_concept = topics[0].get("display_name", "")
        elif concepts:
            top_concept = concepts[0].get("display_name", "")
    except Exception as exc:
        print(f"  WARNING: Could not fetch author concepts: {exc}")

    return top_title, top_concept


AUTHOR_OPENALEX_ID: str = _resolve_openalex_id(settings.monitor_author_name)
_AUTHOR_TOP_TITLE, _AUTHOR_TOP_CONCEPT = _resolve_author_context(AUTHOR_OPENALEX_ID)


# ── Helpers ───────────────────────────────────────────────────────────────────
def _fetch_routes() -> list[tuple[str, str]]:
    spec = requests.get(
        f"{FASTAPI_BASE}/openapi.json", timeout=30, headers=HEADERS
    ).json()
    return [(m.upper(), p) for p, ms in spec.get("paths", {}).items() for m in ms]


def _is_monitorable(method: str, path: str) -> bool:
    if method != "GET":
        return False
    if re.search(r"\{[^}]+\}", path):  # skip dynamic path segments
        return False
    return True


def _friendly(path: str) -> str:
    p = re.sub(r"^/api/v1", "", path).strip("/")
    if not p:
        return "Root"
    return " ".join(w.replace("_", " ").title() for w in p.split("/"))


def _build_url(path: str) -> str | None:
    """
    Return a fully-parameterised monitor URL, or None if the endpoint cannot
    be meaningfully monitored (e.g. requires a signed one-time token).
    """
    aid = AUTHOR_OPENALEX_ID

    # Endpoints that need a signed expiring token — not monitorable with 200
    if "download-export" in path:
        return None

    # author_id-based endpoints
    author_id_paths = {
        "/author_metrics",
        "/citation_heatmap",
        "/journal_advisor",
        "/match_grants",
        "/network_collaborators",
        "/orbit_metrics",
        "/semantic_trending",
    }
    norm = re.sub(r"^/api/v1", "", path)
    if norm in author_id_paths:
        if not aid:
            return f"{DOCKER_HOST}{path}"  # fallback — will return 422
        return f"{DOCKER_HOST}{path}?author_id={aid}"

    if norm == "/collaborator_synergy":
        if not aid:
            return f"{DOCKER_HOST}{path}"
        # Use the same ID twice as a harmless smoke-test
        return f"{DOCKER_HOST}{path}?author_id={aid}&collaborator_id={aid}"

    # name-based endpoints
    if norm in {"/refresh_author", "/resolve_email", "/search_author"}:
        name_val = settings.monitor_author_name
        if not name_val:
            return None
        return f"{DOCKER_HOST}{path}?name={requests.utils.quote(name_val)}"

    # title-based endpoints
    if norm in {"/analyze_paper", "/summarize_work", "/presentation_outline"}:
        title_val = _AUTHOR_TOP_TITLE or settings.monitor_author_name
        if not title_val:
            return None
        return f"{DOCKER_HOST}{path}?title={requests.utils.quote(title_val)}"

    # query-based
    if norm == "/author_suggestions":
        query_val = _AUTHOR_TOP_CONCEPT or settings.monitor_author_name
        if not query_val:
            return None
        return f"{DOCKER_HOST}{path}?query={requests.utils.quote(query_val)}"

    # user_id-based endpoints
    if norm in {"/industry_academic_tieups", "/users/quests"}:
        if not aid:
            return None
        return f"{DOCKER_HOST}{path}?user_id={aid}"

    # author_id-required endpoints (raise 400/404 without it)
    if norm == "/daily_conjecture":
        if not aid:
            return None
        return f"{DOCKER_HOST}{path}?author_id={aid}"

    if norm == "/assistant_professor_roadmap":
        if not aid:
            return None
        focus_val = _AUTHOR_TOP_CONCEPT or settings.monitor_author_name
        if not focus_val:
            return f"{DOCKER_HOST}{path}?author_id={aid}"
        return (
            f"{DOCKER_HOST}{path}"
            f"?author_id={aid}&focus={requests.utils.quote(focus_val)}"
        )

    return f"{DOCKER_HOST}{path}"


# ── Main ──────────────────────────────────────────────────────────────────────
def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--username", required=True)
    parser.add_argument("--password", required=True)
    args = parser.parse_args()

    if AUTHOR_OPENALEX_ID:
        print(
            f"Resolved OpenAlex ID for '{settings.monitor_author_name}': {AUTHOR_OPENALEX_ID}"
        )
    else:
        print(
            "WARNING: Could not resolve OpenAlex author ID — author endpoints will fall back to 422."
        )

    print(f"\nFetching routes from {FASTAPI_BASE} ...")
    try:
        all_routes = _fetch_routes()
    except Exception as exc:
        print(f"ERROR: Could not reach FastAPI: {exc}")
        sys.exit(1)

    seen: set[str] = set()
    candidates: list[tuple[str, str]] = []
    for method, path in all_routes:
        if not _is_monitorable(method, path):
            continue
        normalised = re.sub(r"^/api/v1", "", path)
        if normalised in seen:
            continue
        seen.add(normalised)
        candidates.append((method, path))

    print(f"Found {len(candidates)} monitorable endpoints.\n")

    api = UptimeKumaApi(UPTIME_KUMA)
    api.login(args.username, args.password)

    # Index existing monitors by both the exact base URL and the /api/v1-stripped
    # variant so stale monitors at either path are found and cleaned up.
    all_monitors = api.get_monitors()
    existing_monitors: dict[str, dict] = {}
    for m in all_monitors:
        base = m["url"].split("?")[0]
        existing_monitors[base] = m
        alt = re.sub(r"^http://[^/]+", "", base)  # path only
        alt_no_prefix = re.sub(r"^/api/v1", "", alt)  # strip /api/v1
        alt_url = f"{DOCKER_HOST}{alt_no_prefix}"
        existing_monitors.setdefault(alt_url, m)

    deleted = added = skipped = 0

    for method, path in candidates:
        url = _build_url(path)
        name = _friendly(path)
        base_url = url.split("?")[0] if url else f"{DOCKER_HOST}{path}"
        # Also check the /api/v1-stripped base
        norm_base = f"{DOCKER_HOST}{re.sub(r'^/api/v1', '', path)}"
        existing = existing_monitors.get(base_url) or existing_monitors.get(norm_base)

        if url is None:
            if existing:
                try:
                    api.delete_monitor(existing["id"])
                    print(f"  -    (removed un-monitorable): {name}")
                    deleted += 1
                except Exception as exc:
                    print(f"  ERROR deleting {name}: {exc}")
            else:
                print(f"  SKIP (un-monitorable): {name}")
            skipped += 1
            continue

        # Always delete stale monitor then re-add fresh — edit_monitor does NOT
        # reset heartbeat history so old 422 timestamps would stay frozen.
        if existing:
            try:
                api.delete_monitor(existing["id"])
                deleted += 1
            except Exception as exc:
                print(f"  ERROR deleting stale {name}: {exc}")

        try:
            api.add_monitor(
                type=MonitorType.HTTP,
                name=name,
                url=url,
                method=method,
                interval=60,
                retryInterval=30,
                maxretries=2,
                accepted_statuscodes=ACCEPTED_CODES,
            )
            print(f"  +    (added):  {name}  ->  {url}")
            added += 1
        except Exception as exc:
            print(f"  ERROR adding {name}: {exc}")

    api.disconnect()
    print(f"\nDone — {added} added, {deleted} stale deleted, {skipped} skipped.")
    print(f"Dashboard: {UPTIME_KUMA}")


if __name__ == "__main__":
    main()
