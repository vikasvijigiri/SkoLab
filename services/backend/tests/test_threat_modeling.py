import asyncio
import pytest
import httpx
from pydantic import ValidationError
from sqlalchemy import select

from app.main import app
from app.db.database import AsyncSessionLocal, engine, generate_record_signature
from app.models.user_models import User, UserPreference
from app.schemas.core import AgentChatRequest
from app.domains.quest.service import QuestsService

# Configure HTTPX AsyncClient transport compatibility
try:
    transport = httpx.ASGITransport(app=app)
    client_args = {"transport": transport}
except AttributeError:
    client_args = {"app": app}


@pytest.fixture(autouse=True)
def cleanup_test_context():
    """Dispose the engine after each test."""
    yield
    asyncio.run(engine.dispose())


async def force_cleanup_user(db, user_id):
    """Clean up all tables containing user_id directly or indirectly."""
    await db.execute(
        UserPreference.__table__.delete().where(UserPreference.user_id == user_id)
    )
    res_u = await db.execute(select(User).where(User.id == user_id))
    user_obj = res_u.scalar_one_or_none()
    if user_obj:
        await db.delete(user_obj)
    await db.commit()


@pytest.mark.asyncio
async def test_scraper_user_agent_is_no_longer_blocked():
    """The User-Agent substring "WAF" block was removed (Stream B hardening):
    it blocked the repo's own k6 / Playwright / uptime tooling and stopped
    nothing real. A scraper-looking UA now gets a normal response."""
    async with httpx.AsyncClient(base_url="http://testserver", **client_args) as ac:
        response = await ac.get(
            "/health", headers={"user-agent": "python-requests/2.28.1"}
        )
        assert response.status_code != 403
        assert response.status_code in (200, 503)  # health's own dynamic status

        response_valid = await ac.get(
            "/", headers={"user-agent": "SkoLabMobileApp/1.0"}
        )
        assert response_valid.status_code == 200


@pytest.mark.asyncio
async def test_xss_query_string_is_not_waf_blocked():
    """The query-string "XSS signature" block was removed too — a `<script>` in
    a query param is defended by output encoding + Pydantic validation, not a
    400 from a substring scan. The request reaches routing (404 here), not a
    WAF 400."""
    async with httpx.AsyncClient(base_url="http://testserver", **client_args) as ac:
        response = await ac.get("/api/v1/does-not-exist?q=<script>alert(1)</script>")
        assert response.status_code != 400
        assert response.status_code == 404


@pytest.mark.asyncio
async def test_metrics_endpoint_is_gone():
    """The admin-subnet gate existed to protect GET /metrics. Both were retired
    (docs/plans/2026-09-04-retire-python-infra.md) — /metrics now 404s and the
    request never hits an admin guard."""
    async with httpx.AsyncClient(base_url="http://testserver", **client_args) as ac:
        assert (await ac.get("/metrics")).status_code == 404
        # A spoofed SRE token changes nothing — there is no guard left to bypass.
        resp = await ac.get("/metrics", headers={"X-SRE-Token": "anything"})
        assert resp.status_code == 404


@pytest.mark.asyncio
async def test_device_signature_headers_are_ignored():
    """The device-signature guard was theatre — keyed by a server-only secret no
    client can hold — and is removed. A mutating request carrying X-User-Id but
    no signature headers is no longer rejected with 401 by middleware; it just
    routes normally (404 here, since no such route exists)."""
    user_id = "test_device_sig_user"
    async with httpx.AsyncClient(base_url="http://testserver", **client_args) as ac:
        resp = await ac.post(
            f"/api/v1/users/{user_id}/export", headers={"X-User-Id": user_id}
        )
        assert resp.status_code != 401
        assert resp.status_code == 404


@pytest.mark.asyncio
async def test_python_no_longer_rate_limits():
    """The per-process token-bucket limiter was retired — the Go gateway
    rate-limits per IP. A burst that used to trip Python's 5/min strict limit
    on /agent/chat now never returns 429 from this service."""
    async with httpx.AsyncClient(base_url="http://testserver", **client_args) as ac:
        statuses = []
        for _ in range(12):
            try:
                statuses.append((await ac.get("/api/v1/agent/chat")).status_code)
            except Exception:
                pass
        assert 429 not in statuses


@pytest.mark.asyncio
async def test_input_length_validation():
    """Verify that chat messages > 2000 characters fail Pydantic model validation."""
    long_msg = "A" * 2001
    with pytest.raises(ValidationError):
        AgentChatRequest(message=long_msg, history=[])


@pytest.mark.asyncio
async def test_quest_database_tampering_check():
    """Verify database integrity checks detect quest record modifications."""
    user_id = "test_tampering_user"
    async with AsyncSessionLocal() as db:
        await force_cleanup_user(db, user_id)

        # Insert a user
        u = User(id=user_id, display_name="Test Tamper")
        db.add(u)
        await db.commit()

        # Initialize quests using QuestsService (which auto-signs it)
        # We will mock openalex and generate mock quests instead of LLM to keep test fast
        service = QuestsService(db)
        mock_quests = [
            {
                "id": "q1",
                "title": "Read 5 papers",
                "reward_entropy": 25,
                "is_completed": False,
            }
        ]

        sig = generate_record_signature(user_id, mock_quests)

        pref = UserPreference(
            user_id=user_id, preference_key="quests", preference_value=mock_quests
        )
        sig_pref = UserPreference(
            user_id=user_id, preference_key="quests_signature", preference_value=sig
        )
        db.add_all([pref, sig_pref])
        await db.commit()

    # Now load it, should verify successfully
    async with AsyncSessionLocal() as db:
        service = QuestsService(db)
        quests = await service.get_user_quests(user_id, None)
        assert len(quests) == 1
        assert quests[0].id == "q1"

        # Tamper the quest record directly in database (e.g. mark it complete unauthorized)
        stmt = select(UserPreference).where(
            UserPreference.user_id == user_id, UserPreference.preference_key == "quests"
        )
        res = await db.execute(stmt)
        pref_obj = res.scalars().first()
        pref_obj.preference_value = [
            {
                "id": "q1",
                "title": "Read 5 papers",
                "reward_entropy": 1000,
                "is_completed": True,
            }  # Tampered reward/status
        ]
        from sqlalchemy.orm.attributes import flag_modified

        flag_modified(pref_obj, "preference_value")
        await db.commit()

    # Reading should now throw ValueError due to signature mismatch
    async with AsyncSessionLocal() as db:
        service = QuestsService(db)
        with pytest.raises(ValueError) as excinfo:
            await service.get_user_quests(user_id, None)
        assert "tampered with" in str(excinfo.value)

    # Clean up user
    async with AsyncSessionLocal() as db:
        await force_cleanup_user(db, user_id)
