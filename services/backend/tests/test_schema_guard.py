"""app/db/schema_guard.py — the startup drift check.

The prod incident it exists for: the DB sat behind ``alembic head`` and every
read swallowed its own ``Could not locate column in row`` error, so nothing
alerted for days. This asserts the check now logs one loud line on drift.
"""

from __future__ import annotations

import logging
from types import SimpleNamespace

import pytest

from app.db import schema_guard


@pytest.fixture(autouse=True)
def _prod_settings(monkeypatch):
    # Force the "production, alembic-owned schema" branch so the check runs.
    monkeypatch.setattr(
        schema_guard,
        "settings",
        SimpleNamespace(environment="production", run_schema_create_all=False),
    )


async def _run(monkeypatch, *, head, current):
    monkeypatch.setattr(schema_guard, "_alembic_head", lambda: head)

    async def _cur():
        return current

    monkeypatch.setattr(schema_guard, "_current_db_revision", _cur)
    return await schema_guard.check_schema_current()


async def test_returns_true_and_stays_quiet_when_at_head(monkeypatch, caplog):
    with caplog.at_level(logging.ERROR, logger="skolab"):
        ok = await _run(monkeypatch, head="abc123", current="abc123")
    assert ok is True
    assert not [r for r in caplog.records if r.levelno >= logging.ERROR]


async def test_reports_drift_with_an_actionable_line(monkeypatch, caplog):
    with caplog.at_level(logging.ERROR, logger="skolab"):
        ok = await _run(monkeypatch, head="newhead", current="oldrev")
    assert ok is False
    msg = caplog.records[-1].getMessage()
    assert "SCHEMA DRIFT" in msg
    assert "oldrev" in msg and "newhead" in msg
    assert "run_migrations" in msg or "alembic upgrade head" in msg


async def test_reports_drift_when_the_db_was_never_migrated(monkeypatch, caplog):
    with caplog.at_level(logging.ERROR, logger="skolab"):
        ok = await _run(monkeypatch, head="newhead", current=None)
    assert ok is False
    assert "never migrated" in caplog.records[-1].getMessage()


async def test_is_a_safe_noop_when_alembic_cannot_be_read(monkeypatch):
    ok = await _run(monkeypatch, head=None, current="whatever")
    assert ok is True


async def test_skips_the_check_for_local_create_all_dev(monkeypatch):
    monkeypatch.setattr(
        schema_guard,
        "settings",
        SimpleNamespace(environment="development", run_schema_create_all=True),
    )
    # _alembic_head must not even be consulted
    monkeypatch.setattr(
        schema_guard,
        "_alembic_head",
        lambda: (_ for _ in ()).throw(AssertionError("should not be called")),
    )
    assert await schema_guard.check_schema_current() is True
