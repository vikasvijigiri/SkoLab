"""Unit behaviour of the ``require_owner`` dependency factory.

Route-level application (which real endpoints adopt it) is covered by
``test_auth_posture.py`` + the per-endpoint suites; this file pins the
dependency's own 401 / 403 / 400 / 200 semantics against a throwaway app.
"""

from __future__ import annotations

import httpx
import pytest
import pytest_asyncio
from fastapi import Depends, FastAPI

from app.api.dependencies import get_verified_user, require_owner


@pytest.fixture
def probe_app():
    app = FastAPI()

    @app.get("/thing")
    async def _thing(_owner: dict = Depends(require_owner("user_id"))):
        return {"ok": True}

    @app.get("/u/{user_id}")
    async def _thing_path(
        user_id: str, _owner: dict = Depends(require_owner("user_id"))
    ):
        return {"ok": True}

    return app


@pytest_asyncio.fixture
async def probe_client(probe_app):
    transport = httpx.ASGITransport(app=probe_app, raise_app_exceptions=False)
    async with httpx.AsyncClient(
        transport=transport, base_url="http://probe"
    ) as c:
        yield c


async def test_missing_token_is_401(probe_client):
    r = await probe_client.get("/thing", params={"user_id": "abc"})
    assert r.status_code == 401


async def test_uid_mismatch_is_403(probe_app, probe_client):
    probe_app.dependency_overrides[get_verified_user] = lambda: {"uid": "real-uid"}
    try:
        r = await probe_client.get("/thing", params={"user_id": "someone-else"})
        assert r.status_code == 403
    finally:
        probe_app.dependency_overrides.clear()


async def test_uid_match_is_200(probe_app, probe_client):
    probe_app.dependency_overrides[get_verified_user] = lambda: {"uid": "real-uid"}
    try:
        r = await probe_client.get("/thing", params={"user_id": "real-uid"})
        assert r.status_code == 200
    finally:
        probe_app.dependency_overrides.clear()


async def test_missing_identifier_is_400(probe_app, probe_client):
    probe_app.dependency_overrides[get_verified_user] = lambda: {"uid": "real-uid"}
    try:
        r = await probe_client.get("/thing")
        assert r.status_code == 400
    finally:
        probe_app.dependency_overrides.clear()


async def test_matches_on_path_param(probe_app, probe_client):
    probe_app.dependency_overrides[get_verified_user] = lambda: {"uid": "real-uid"}
    try:
        ok = await probe_client.get("/u/real-uid")
        bad = await probe_client.get("/u/other")
        assert ok.status_code == 200
        assert bad.status_code == 403
    finally:
        probe_app.dependency_overrides.clear()
