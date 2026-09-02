"""Flatten a FastAPI app's route tree.

FastAPI 0.141 includes sub-routers *lazily*: ``app.routes`` holds
``_IncludedRouter`` placeholders, not the concrete ``APIRoute`` objects, which
are resolved on first request. Introspection tests that iterate ``app.routes``
directly therefore see only the handful of routes declared straight on the app
(``/``, ``/health``, ``/metrics``, the docs routes). This walker recurses
through the placeholders to yield every real ``APIRoute`` with its full path.
"""

from __future__ import annotations

from collections.abc import Iterator

from fastapi.routing import APIRoute


def iter_api_routes(routes, prefix: str = "") -> Iterator[tuple[str, APIRoute]]:
    for route in routes:
        if isinstance(route, APIRoute):
            yield prefix + route.path, route
            continue
        if type(route).__name__ == "_IncludedRouter":
            ctx = getattr(route, "include_context", None)
            sub_prefix = prefix + (getattr(ctx, "prefix", "") or "")
            src = getattr(route, "original_router", None) or getattr(
                ctx, "included_router", None
            )
            if src is not None:
                yield from iter_api_routes(src.routes, sub_prefix)
            continue
        sub = getattr(route, "routes", None)
        if sub:
            yield from iter_api_routes(
                sub, prefix + (getattr(route, "prefix", "") or "")
            )
