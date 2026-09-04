"""The mechanism that makes "every route is typed" a test, not a promise.

``PERMANENT_ALLOWLIST`` holds infra routes that legitimately return a raw
response. ``UNTYPED_ALLOWLIST`` is empty and must stay empty.

Routes are enumerated with ``_route_walk.iter_api_routes`` — FastAPI 0.141
includes sub-routers lazily, so ``app.routes`` alone shows only ``/`` and the
infra probes (``/health``, ``/livez``, ``/readyz``).
``test_route_table_is_populated`` is the backstop against a vacuous pass.
"""

from __future__ import annotations

from ._route_walk import iter_api_routes

_V1_PREFIX = "/api/v1"
_HTTP_METHODS = {"GET", "POST", "PUT", "PATCH", "DELETE"}

# Infra routes that return a raw Response / plain text by design — never typed.
PERMANENT_ALLOWLIST: set[str] = {
    "/health",  # returns Response with a dynamic 200/503 status code
    "/readyz",  # infra: raw Response, dynamic 200/503
}

# Routes still awaiting a response_model — MUST stay empty.
UNTYPED_ALLOWLIST: set[str] = set()


def _norm(path: str) -> str:
    if path.startswith(_V1_PREFIX) and len(path) > len(_V1_PREFIX):
        return path[len(_V1_PREFIX) :]
    return path


def _routes(app):
    """(normalised path, route) for every HTTP APIRoute in the app."""
    out = []
    for full_path, route in iter_api_routes(app.routes):
        if (route.methods or set()) & _HTTP_METHODS:
            out.append((_norm(full_path), route))
    return out


def test_route_table_is_populated(app):
    """Backstop against a vacuous pass — the walk must reach the real surface."""
    n = len(_routes(app))
    assert n > 20, (
        f"only {n} API routes reachable — the lazy router tree is not being "
        f"resolved; every other assertion here would pass vacuously. "
        f"paths={sorted(p for p, _ in _routes(app))}"
    )


def test_every_route_has_a_response_model_or_is_allowlisted(app):
    offenders: list[tuple[str, list[str]]] = []
    for path, route in _routes(app):
        if route.response_model is not None:
            continue
        if path in PERMANENT_ALLOWLIST or path in UNTYPED_ALLOWLIST:
            continue
        offenders.append((path, sorted(route.methods)))

    assert not offenders, (
        "routes without response_model and not allow-listed: "
        + ", ".join(f"{p} {m}" for p, m in sorted(set(offenders)))
    )


def test_untyped_allowlist_is_empty():
    assert UNTYPED_ALLOWLIST == set()


def test_allowlist_has_no_entry_that_is_already_typed(app):
    routes = _routes(app)
    typed = {p for p, r in routes if r.response_model is not None}
    untyped = {p for p, r in routes if r.response_model is None}
    fully_typed = typed - untyped
    stale = (UNTYPED_ALLOWLIST | PERMANENT_ALLOWLIST) & fully_typed
    assert not stale, f"allow-list entries that are already typed: {sorted(stale)}"


def test_every_allowlist_entry_points_at_a_real_route(app):
    all_paths = {p for p, _ in _routes(app)}
    missing = (UNTYPED_ALLOWLIST | PERMANENT_ALLOWLIST) - all_paths
    assert not missing, f"allow-list entries with no matching route: {sorted(missing)}"
