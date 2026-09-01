"""Task 2 — PaginationParams + Page[T]."""

import httpx
import pytest
from fastapi import Depends, FastAPI

from app.api.pagination import Page, PaginationParams, paginate


def test_paginate_slices_and_reports_total():
    page = paginate(list(range(50)), PaginationParams(limit=10, offset=20))
    assert page.items == list(range(20, 30))
    assert page.total == 50
    assert page.limit == 10
    assert page.offset == 20


def test_paginate_past_end_is_empty():
    page = paginate(list(range(5)), PaginationParams(limit=10, offset=100))
    assert page.items == []
    assert page.total == 5


@pytest.mark.asyncio
async def test_limit_over_max_is_rejected_in_a_route():
    app = FastAPI()

    @app.get("/things", response_model=Page[int])
    async def things(p: PaginationParams = Depends()):
        return paginate(list(range(200)), p)

    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://t") as c:
        ok = await c.get("/things", params={"limit": 50, "offset": 0})
        too_big = await c.get("/things", params={"limit": 200})

    assert ok.status_code == 200
    assert ok.json()["limit"] == 50
    assert too_big.status_code == 422
