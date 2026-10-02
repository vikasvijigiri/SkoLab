"""Session validity: local token verification plus a cached revocation check.

A fake firebase_admin stands in for the real SDK, so these run anywhere.
"""

import sys
import threading
from types import ModuleType, SimpleNamespace

import pytest
from fastapi import HTTPException
from fastapi.security import HTTPAuthorizationCredentials

from app.api import dependencies


class UserNotFoundError(Exception):
    pass


class FakeFirebase:
    def __init__(self, *, auth_time=2_000, valid_after_ms=0, disabled=False):
        self.auth_time, self.valid_after_ms, self.disabled = (
            auth_time,
            valid_after_ms,
            disabled,
        )
        self.get_user_error: Exception | None = None
        self.bad_token = False
        self.provider: str | None = None
        self.email_verified = True
        self.lookups = 0
        self.threads: set[int] = set()

    def verify_id_token(self, token):
        self.threads.add(threading.get_ident())
        if self.bad_token:
            raise ValueError("bad signature")
        claims = {"uid": "ada", "auth_time": self.auth_time}
        if self.provider:
            claims["firebase"] = {"sign_in_provider": self.provider}
            claims["email_verified"] = self.email_verified
        return claims

    def get_user(self, uid):
        self.lookups += 1
        self.threads.add(threading.get_ident())
        if self.get_user_error:
            raise self.get_user_error
        return SimpleNamespace(
            tokens_valid_after_timestamp=self.valid_after_ms, disabled=self.disabled
        )


@pytest.fixture
def firebase(monkeypatch):
    fake = FakeFirebase()
    firebase_module = ModuleType("firebase_admin")
    firebase_module._apps = [object()]
    auth_module = ModuleType("firebase_admin.auth")
    auth_module.verify_id_token = fake.verify_id_token
    auth_module.get_user = fake.get_user
    auth_module.UserNotFoundError = UserNotFoundError
    firebase_module.auth = auth_module
    monkeypatch.setitem(sys.modules, "firebase_admin", firebase_module)
    monkeypatch.setitem(sys.modules, "firebase_admin.auth", auth_module)
    clock = [1_000.0]
    monkeypatch.setattr(dependencies.time, "monotonic", lambda: clock[0])
    dependencies._user_status.clear()
    fake.clock = clock
    yield fake
    dependencies._user_status.clear()


async def verify():
    return await dependencies.get_verified_user(
        HTTPAuthorizationCredentials(scheme="Bearer", credentials="token")
    )


async def status_of(coro) -> int:
    try:
        await coro
    except HTTPException as exc:
        return exc.status_code
    return 200


async def test_valid_session_is_looked_up_once_then_cached(firebase):
    for _ in range(5):
        assert (await verify())["uid"] == "ada"
    assert firebase.lookups == 1


async def test_firebase_calls_never_run_on_the_event_loop_thread(firebase):
    await verify()
    assert threading.get_ident() not in firebase.threads


@pytest.mark.parametrize(
    "setup",
    [
        pytest.param(lambda f: setattr(f, "valid_after_ms", 3_000_000), id="revoked"),
        pytest.param(lambda f: setattr(f, "disabled", True), id="disabled"),
        pytest.param(
            lambda f: setattr(f, "get_user_error", UserNotFoundError()), id="deleted"
        ),
    ],
)
async def test_revoked_disabled_and_deleted_accounts_are_rejected(firebase, setup):
    setup(firebase)
    assert await status_of(verify()) == 401


async def test_revocation_takes_effect_when_the_cache_expires(firebase):
    await verify()
    firebase.valid_after_ms = 3_000_000  # "sign out everywhere"
    firebase.clock[0] += dependencies._USER_STATUS_TTL_SECONDS
    assert await status_of(verify()) == 401


async def test_known_user_survives_a_brief_firebase_outage(firebase):
    await verify()
    firebase.get_user_error = ConnectionError("firebase down")
    firebase.clock[0] += dependencies._USER_STATUS_TTL_SECONDS + 60
    assert await status_of(verify()) == 200


async def test_outage_fails_closed_for_unknown_or_long_stale_users(firebase):
    firebase.get_user_error = ConnectionError("firebase down")
    assert await status_of(verify()) == 503

    firebase.get_user_error = None
    await verify()
    firebase.get_user_error = ConnectionError("firebase down")
    firebase.clock[0] += dependencies._USER_STATUS_MAX_STALE_SECONDS
    assert await status_of(verify()) == 503


async def test_invalid_token_is_401_without_an_account_lookup(firebase):
    firebase.bad_token = True
    assert await status_of(verify()) == 401
    assert firebase.lookups == 0


async def test_missing_header_is_401():
    assert await status_of(dependencies.get_verified_user(None)) == 401


async def test_firebase_misconfiguration_is_503_not_401(monkeypatch):
    def broken():
        raise RuntimeError("no credentials")

    monkeypatch.setattr(dependencies, "_firebase_auth", broken)
    assert await status_of(verify()) == 503


@pytest.mark.parametrize(
    ("provider", "verified", "switch", "expected"),
    [
        ("password", True, None, 200),
        ("password", False, None, 403),
        ("password", False, "false", 200),
        ("anonymous", True, None, 403),
        ("google.com", False, None, 200),
    ],
    ids=[
        "verified-password",
        "unverified-password",
        "rollout-switch-off",
        "anonymous",
        "federated-vouches-for-itself",
    ],
)
async def test_account_policy(
    firebase, monkeypatch, provider, verified, switch, expected
):
    firebase.provider, firebase.email_verified = provider, verified
    if switch is not None:
        monkeypatch.setenv("AUTH_REQUIRE_VERIFIED_EMAIL", switch)
    assert await status_of(verify()) == expected
    if expected == 403:
        assert firebase.lookups == 0, "policy refusals must not cost a Firebase lookup"


async def test_security_events_are_logged_and_counted(firebase, caplog):
    from opentelemetry.sdk.metrics import MeterProvider
    from opentelemetry.sdk.metrics.export import InMemoryMetricReader

    from app.core import security_events

    reader = InMemoryMetricReader()
    security_events.use_meter(MeterProvider(metric_readers=[reader]).get_meter("t"))
    try:
        firebase.provider, firebase.email_verified = "password", False
        caplog.set_level("WARNING", logger="skolab")
        assert await status_of(verify()) == 403
        firebase.bad_token = True
        assert await status_of(verify()) == 401
    finally:
        security_events._counter = None

    events = [r for r in caplog.records if r.getMessage() == "security_event"]
    assert [(r.event, r.outcome) for r in events] == [
        ("auth.email_unverified", "denied"),
        ("auth.token_invalid", "denied"),
    ]
    points = {
        (p.attributes["event"], p.attributes["outcome"]): p.value
        for rm in reader.get_metrics_data().resource_metrics
        for sm in rm.scope_metrics
        for m in sm.metrics
        for p in m.data.data_points
    }
    assert points == {
        ("auth.email_unverified", "denied"): 1,
        ("auth.token_invalid", "denied"): 1,
    }
