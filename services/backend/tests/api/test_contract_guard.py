"""The mechanism that makes "every route is typed" a test, not a promise.

``UNTYPED_ALLOWLIST`` is seeded with every route that lacks a ``response_model``
today. Each wiring task removes its paths. The plan is done when it is empty
(Task 11 flips the final assertion). ``PERMANENT_ALLOWLIST`` holds infra routes
that legitimately return a raw response.
"""

from __future__ import annotations

from fastapi.routing import APIRoute

from app.main import app

_V1_PREFIX = "/api/v1"
_HTTP_METHODS = {"GET", "POST", "PUT", "PATCH", "DELETE"}

# Infra routes that return a raw Response / plain text by design — never typed.
PERMANENT_ALLOWLIST: set[str] = {
    "/health",  # returns Response with a dynamic 200/503 status code
    "/metrics",  # Prometheus text/plain exposition format, not JSON
}

# Routes still awaiting a response_model. Shrank to empty across Tasks 4–11 —
# it MUST stay empty. A new untyped route either gets a response_model or a
# justified PERMANENT_ALLOWLIST entry; it does not come back here.
UNTYPED_ALLOWLIST: set[str] = set()


def _norm(path: str) -> str:
    if path.startswith(_V1_PREFIX) and len(path) > len(_V1_PREFIX):
        return path[len(_V1_PREFIX) :]
    return path


def _api_routes() -> list[APIRoute]:
    out = []
    for route in app.routes:
        if not isinstance(route, APIRoute):
            continue
        if not (route.methods or set()) & _HTTP_METHODS:
            continue
        out.append(route)
    return out


def test_every_route_has_a_response_model_or_is_allowlisted():
    offenders: list[tuple[str, list[str]]] = []
    for route in _api_routes():
        path = _norm(route.path)
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
    # The plan's terminal invariant: every APIRoute is typed or permanently
    # allow-listed with a reason. Nothing sits in the shrinking list any more.
    assert UNTYPED_ALLOWLIST == set()


def test_allowlist_has_no_entry_that_is_already_typed():
    typed = {_norm(r.path) for r in _api_routes() if r.response_model is not None}
    # A path is "typed" only if it has NO untyped variant left.
    untyped = {_norm(r.path) for r in _api_routes() if r.response_model is None}
    fully_typed = typed - untyped
    stale = (UNTYPED_ALLOWLIST | PERMANENT_ALLOWLIST) & fully_typed
    assert not stale, f"allow-list entries that are already typed: {sorted(stale)}"


def test_every_allowlist_entry_points_at_a_real_route():
    all_paths = {_norm(r.path) for r in _api_routes()}
    missing = (UNTYPED_ALLOWLIST | PERMANENT_ALLOWLIST) - all_paths
    assert not missing, f"allow-list entries with no matching route: {sorted(missing)}"
