"""Anti-noise toolkit from the 2026-09 Sentry audit — app/core/observability.py.

Covers the disk alerter's band/cooldown logic, the AI-degradation log routing,
the Sentry ``before_send`` filter, and the download-artefact TTL sweep.
"""

from __future__ import annotations

import logging
import time
from types import SimpleNamespace

import pytest

from app.core import observability as obs


# ── DiskUsageAlerter ───────────────────────────────────────────────────────


def test_disk_alerter_fires_once_on_crossing_then_stays_quiet():
    a = obs.DiskUsageAlerter(realert_seconds=3600)
    t = 1000.0

    assert a.evaluate(60.0, now=t) == (False, 0)
    # first time above 80 → alert
    assert a.evaluate(81.0, now=t) == (True, 80)
    # still in the same band a minute later → no re-alert
    assert a.evaluate(82.0, now=t + 60) == (False, 80)
    assert a.evaluate(84.9, now=t + 120) == (False, 80)


def test_disk_alerter_re_alerts_a_standing_condition_after_the_cooldown():
    a = obs.DiskUsageAlerter(realert_seconds=3600)
    assert a.evaluate(83.0, now=0.0) == (True, 80)
    assert a.evaluate(83.0, now=1800.0) == (False, 80)
    # an hour later the standing condition is worth another line
    assert a.evaluate(83.0, now=3600.0) == (True, 80)


def test_disk_alerter_fires_again_when_usage_climbs_into_a_higher_band():
    a = obs.DiskUsageAlerter(realert_seconds=3600)
    assert a.evaluate(81.0, now=0.0) == (True, 80)
    assert a.evaluate(86.0, now=30.0) == (True, 85)  # crossed up, ignores cooldown
    assert a.evaluate(91.0, now=45.0) == (True, 90)
    assert a.evaluate(92.0, now=60.0) == (False, 90)  # same band, within cooldown


def test_disk_alerter_resets_when_usage_recovers_below_the_floor():
    a = obs.DiskUsageAlerter(realert_seconds=3600)
    assert a.evaluate(88.0, now=0.0) == (True, 85)
    assert a.evaluate(50.0, now=10.0) == (False, 0)
    # climbing back into a band that was already alerted counts as a fresh cross
    assert a.evaluate(86.0, now=20.0) == (True, 85)


# ── AI-degradation log routing ─────────────────────────────────────────────


@pytest.mark.parametrize(
    "message",
    [
        "LLM services are currently unavailable or rate-limited.",
        "LLM query failed across all attempted models. Errors: ...",
        'Groq returned 429: {"error":{"message":"Rate limit reached ..."}}',
        "Circuit breaker 'groq' is OPEN. Retry after 12.0s.",
    ],
)
def test_transient_ai_errors_are_recognised(message):
    assert obs.is_transient_ai_error(message) is True
    assert obs.is_transient_ai_error(RuntimeError(message)) is True


def test_a_real_bug_is_not_treated_as_transient():
    assert obs.is_transient_ai_error(KeyError("topic_toughness")) is False
    assert obs.is_transient_ai_error("unexpected token at line 4") is False


def test_log_ai_degradation_uses_warning_for_transient_and_error_otherwise(caplog):
    log = logging.getLogger("test.aidegr")
    with caplog.at_level(logging.WARNING, logger="test.aidegr"):
        obs.log_ai_degradation(
            log, "scrape", RuntimeError("LLM services are currently unavailable")
        )
    assert caplog.records[-1].levelno == logging.WARNING

    caplog.clear()
    with caplog.at_level(logging.WARNING, logger="test.aidegr"):
        obs.log_ai_degradation(log, "scrape", KeyError("boom"))
    assert caplog.records[-1].levelno == logging.ERROR


# ── before_send ───────────────────────────────────────────────────────────


def test_before_send_drops_non_production_environments():
    assert obs._before_send({"environment": "development", "message": "x"}, {}) is None
    assert obs._before_send({"environment": "local", "message": "x"}, {}) is None
    kept = obs._before_send({"environment": "production", "message": "x"}, {})
    assert kept is not None


def test_before_send_drops_transient_ai_events():
    exc = RuntimeError("LLM query failed across all attempted models")
    event = {"environment": "production", "message": "boom"}
    assert obs._before_send(event, {"exc_info": (RuntimeError, exc, None)}) is None

    by_message = {
        "environment": "production",
        "logentry": {"message": "Groq returned 429: Rate limit reached"},
    }
    assert obs._before_send(by_message, {}) is None


def test_before_send_scrubs_secrets_from_the_message():
    event = {
        "environment": "production",
        "message": "ValueError: db password = hunter2",
        "exception": {"values": [{"value": "api_key=sk-live-abcdef"}]},
    }
    out = obs._before_send(event, {})
    assert out is not None
    assert "hunter2" not in out["message"]
    assert "sk-live-abcdef" not in out["exception"]["values"][0]["value"]
    assert "[REDACTED]" in out["message"]


# ── prune_downloads_dir ───────────────────────────────────────────────────


def test_prune_downloads_dir_expires_old_generated_files_only(tmp_path):
    old = tmp_path / "cover_letter_abc.pdf"
    old.write_text("x")
    fresh = tmp_path / "cover_letter_def.pdf"
    fresh.write_text("x")
    template = tmp_path / "research_statement_template.md"
    template.write_text("x")

    old_ts = time.time() - 200_000
    import os

    os.utime(old, (old_ts, old_ts))
    os.utime(template, (old_ts, old_ts))  # old, but a template → kept

    removed = obs.prune_downloads_dir(tmp_path, max_age_seconds=86_400)

    assert removed == 1
    assert not old.exists()
    assert fresh.exists()
    assert template.exists()


def test_prune_downloads_dir_is_a_safe_noop_on_a_missing_directory(tmp_path):
    assert obs.prune_downloads_dir(tmp_path / "nope") == 0


# ── init_observability pytest guard ──────────────────────────────────────────


def test_init_observability_is_a_noop_under_pytest_even_with_a_dsn(monkeypatch):
    """The pytest guard: a developer's local DSN must not ship test exceptions
    to the production project (that is how `/boom` and `/readyz` "db is down
    (injected)" became prod issues)."""
    calls: list = []
    monkeypatch.setattr(obs.sentry_sdk, "init", lambda *a, **kw: calls.append(kw))
    monkeypatch.setattr(
        obs,
        "settings",
        SimpleNamespace(
            sentry_dsn="https://public@sentry.example.invalid/1",
            environment="production",
            sentry_traces_sample_rate=0.2,
        ),
    )

    obs.init_observability()  # no force → guarded, must not call sentry_sdk.init
    assert calls == []

    obs.init_observability(force=True)  # force bypasses only the pytest guard
    assert len(calls) == 1 and "before_send" in calls[0]
