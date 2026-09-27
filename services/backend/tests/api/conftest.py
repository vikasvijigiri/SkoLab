"""Fixtures for the API contract suite.

The parent ``tests/conftest.py`` already points ``DATABASE_URL`` at a live
Postgres (Supabase) and runs ``init_db``. This conftest adds an ASGI client
bound to the real application. Nothing here calls the network.
"""

from __future__ import annotations

from typing import AsyncIterator

import httpx
import pytest
import pytest_asyncio


@pytest.fixture(scope="session")
def app():
    from app.main import app as fastapi_app

    return fastapi_app


@pytest_asyncio.fixture
async def client(app) -> AsyncIterator[httpx.AsyncClient]:
    transport = httpx.ASGITransport(app=app, raise_app_exceptions=False)
    async with httpx.AsyncClient(
        transport=transport, base_url="http://testserver"
    ) as c:
        yield c
