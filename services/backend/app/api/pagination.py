"""Shared pagination contract for list routes.

``PaginationParams`` is a FastAPI dependency (``limit``/``offset`` query params
with bounds); ``Page[T]`` is the response envelope; ``paginate`` slices an
already-materialised sequence. Routes that page in SQL construct ``Page``
directly with an explicit ``total``.
"""

from __future__ import annotations

from typing import Generic, Sequence, TypeVar

from fastapi import Query
from pydantic import BaseModel

T = TypeVar("T")


class PaginationParams:
    """``Depends()``-able query params: ``?limit=&offset=``.

    Plain class (FastAPI's documented "classes as dependencies" form) rather than
    a dataclass, so ``limit``/``offset`` are unambiguously resolved as bounded
    query parameters.
    """

    def __init__(
        self,
        limit: int = Query(20, ge=1, le=100),
        offset: int = Query(0, ge=0),
    ) -> None:
        self.limit = limit
        self.offset = offset


class Page(BaseModel, Generic[T]):
    """Envelope for a paginated list response."""

    items: list[T]
    total: int
    limit: int
    offset: int


def paginate(seq: Sequence[T], params: PaginationParams) -> Page[T]:
    """Slice ``seq`` by ``params`` and wrap it, reporting the pre-slice total."""
    total = len(seq)
    window = list(seq[params.offset : params.offset + params.limit])
    return Page(items=window, total=total, limit=params.limit, offset=params.offset)
