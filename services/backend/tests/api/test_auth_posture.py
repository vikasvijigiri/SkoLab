"""Set-level auth guard.

Every FastAPI route is classified by its dependency tree into exactly one of
``authed`` / ``optional`` / ``public``. The first two sets are pinned to a
checked-in expectation; ``public`` is asserted to be the remainder. A route that
silently omits auth turns the build red.

Routes are enumerated with ``_route_walk.iter_api_routes`` because FastAPI 0.141
includes sub-routers lazily — ``app.routes`` alone shows only ``/``, ``/health``
and ``/metrics``.

See ``docs/backend-auth-posture.md`` for the model and the "adding a route"
checklist.
"""

from __future__ import annotations

import inspect
from collections.abc import Callable

from fastapi import Depends, FastAPI
from fastapi import params as fastapi_params
from fastapi.routing import APIRoute

from app.api.dependencies import get_optional_user, get_verified_user

from ._route_walk import iter_api_routes

_V1_PREFIX = "/api/v1"
_HTTP_METHODS = {"GET", "POST", "PUT", "PATCH", "DELETE"}

# Routes that require a verified Firebase uid (keyed data, e.g. chat history).
# ``require_owner`` pulls in ``get_verified_user``, so owner-scoped routes land
# here too — they additionally assert token uid == the request's user_id.
EXPECTED_AUTHED: set[str] = {
    "/agent/chat",
    "/industry_academic_tieups",  # require_owner("user_id") — private memory profile
    "/users/quests",  # require_owner("user_id") — private quest records
    # NOTE: /daily_feed/dismiss moved to the Go gateway in Phase 2
    # (services/backend-go/internal/feed/feed.go) — see docs/backend-auth-posture.md.
}

# Routes that personalise when a token is present but work anonymously.
EXPECTED_OPTIONAL: set[str] = {
    "/chat_with_author",
    "/discovery/predict",
    "/discovery/nexus-chat",
}


def _norm(path: str) -> str:
    if path.startswith(_V1_PREFIX) and len(path) > len(_V1_PREFIX):
        return path[len(_V1_PREFIX) :]
    return path


def _calls_from_signature(fn: Callable, _seen: set | None = None) -> set[Callable]:
    """Every callable reached via ``Depends(...)`` defaults in ``fn``'s signature."""
    seen = _seen if _seen is not None else set()
    out: set[Callable] = set()
    try:
        sig = inspect.signature(fn)
    except (TypeError, ValueError):
        return out
    for param in sig.parameters.values():
        dep = param.default
        if isinstance(dep, fastapi_params.Depends) and dep.dependency is not None:
            call = dep.dependency
            if call not in seen:
                seen.add(call)
                out.add(call)
                out |= _calls_from_signature(call, seen)
    return out


def _calls_from_dependant(dependant) -> set[Callable]:
    found: set[Callable] = set()
    stack = [dependant]
    while stack:
        d = stack.pop()
        if d is None:
            continue
        call = getattr(d, "call", None)
        if call is not None:
            found.add(call)
        stack.extend(getattr(d, "dependencies", []) or [])
    return found


def _route_calls(route: APIRoute) -> set[Callable]:
    calls = _calls_from_dependant(getattr(route, "dependant", None))
    calls |= _calls_from_signature(route.endpoint)
    for dep in getattr(route, "dependencies", []) or []:
        if isinstance(dep, fastapi_params.Depends) and dep.dependency is not None:
            calls.add(dep.dependency)
            calls |= _calls_from_signature(dep.dependency)
    return calls


def _classify(app) -> dict[str, set[str]]:
    authed: set[str] = set()
    optional: set[str] = set()
    public: set[str] = set()
    for full_path, route in iter_api_routes(app.routes):
        if not (route.methods or set()) & _HTTP_METHODS:
            continue
        path = _norm(full_path)
        calls = _route_calls(route)
        if get_verified_user in calls:
            authed.add(path)
        elif get_optional_user in calls:
            optional.add(path)
        else:
            public.add(path)
    return {"authed": authed, "optional": optional, "public": public}


def _dump(classes: dict[str, set[str]]) -> str:
    return (
        f"\n  authed={sorted(classes['authed'])}"
        f"\n  optional={sorted(classes['optional'])}"
        f"\n  public ({len(classes['public'])})={sorted(classes['public'])}"
    )


def test_route_table_is_actually_populated(app):
    """Guards against the vacuous pass: the walk must reach the real surface."""
    classes = _classify(app)
    total = sum(len(v) for v in classes.values())
    assert total > 20, (
        f"only {total} API routes reachable — the walk is not resolving the "
        f"lazy router tree; every assertion here would pass vacuously.{_dump(classes)}"
    )


def test_authed_set_is_locked(app):
    classes = _classify(app)
    assert classes["authed"] == EXPECTED_AUTHED, (
        f"authed routes changed: got {sorted(classes['authed'])}, expected "
        f"{sorted(EXPECTED_AUTHED)}. Update EXPECTED_AUTHED and justify in review "
        f"— see docs/backend-auth-posture.md.{_dump(classes)}"
    )


def test_optional_set_is_locked(app):
    classes = _classify(app)
    assert classes["optional"] == EXPECTED_OPTIONAL, (
        f"optional-auth routes changed: got {sorted(classes['optional'])}, "
        f"expected {sorted(EXPECTED_OPTIONAL)}. See docs/backend-auth-posture.md."
        f"{_dump(classes)}"
    )


def test_public_is_the_remainder(app):
    classes = _classify(app)
    all_paths = classes["authed"] | classes["optional"] | classes["public"]
    expected_public = all_paths - EXPECTED_AUTHED - EXPECTED_OPTIONAL
    assert classes["public"] == expected_public, (
        "a route's auth class does not match the pinned expectation — a new "
        f"route may have silently landed public.{_dump(classes)}"
    )


def test_classifier_detects_a_known_authed_route():
    """Vacuous-pass guard for the detector itself, both dependency styles."""
    probe = FastAPI()

    @probe.post("/_probe_sig")
    async def _p_sig(_u: dict = Depends(get_verified_user)):  # pragma: no cover
        return {}

    @probe.get("/_probe_dec", dependencies=[Depends(get_verified_user)])
    async def _p_dec():  # pragma: no cover
        return {}

    by_path = {r.path: r for r in probe.routes if isinstance(r, APIRoute)}
    assert get_verified_user in _route_calls(by_path["/_probe_sig"])
    assert get_verified_user in _route_calls(by_path["/_probe_dec"])
