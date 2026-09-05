"""The slow-query auto-EXPLAIN listener must not EXPLAIN DDL, and must
actually run a working EXPLAIN for the reads it does target.

`EXPLAIN CREATE TABLE ...` / `EXPLAIN CREATE INDEX ...` are syntax errors in
PostgreSQL, and issuing a failing statement from inside `after_cursor_execute`
aborts the caller's transaction — which broke `init_db()` against a real
Postgres in CI (first-run DDL on a cold container exceeds the 100 ms slow
threshold). Regression guard: only SELECT / WITH get auto-EXPLAIN'd.

Separately (2026-09-05): every "slow query" log line was carrying a *broken*
EXPLAIN attempt — "Failed to execute EXPLAIN automatically: List argument
must consist only of dictionaries" — because the listener re-ran the
statement through `Connection.execute(text(...), parameters)`, which expects
a named-bind dict, while `parameters` here is captured straight off the wire
in the DBAPI driver's own native (positional) form. Fixed by executing
through the raw DBAPI `cursor` already used for the original statement, which
speaks that same native form. `_RecordingCursor` below stands in for it.
"""

from __future__ import annotations

import time
from types import SimpleNamespace

import pytest

from app.db import database as dbmod


class _RecordingCursor:
    def __init__(self) -> None:
        self.executed: list[str] = []

    def execute(self, statement, parameters=None):  # noqa: ARG002
        self.executed.append(str(statement))

    def fetchall(self):
        return [("Seq Scan",)]


def _fire(statement: str, executemany: bool = False) -> _RecordingCursor:
    cursor = _RecordingCursor()
    # _query_start_time well in the past -> total_time_ms > 100 -> slow path.
    ctx = SimpleNamespace(_query_start_time=time.perf_counter() - 5.0)
    dbmod.after_cursor_execute(
        conn=None,
        cursor=cursor,
        statement=statement,
        parameters=None,
        context=ctx,
        executemany=executemany,
    )
    return cursor


@pytest.mark.parametrize(
    "statement",
    [
        "CREATE TABLE users (id INTEGER, name VARCHAR(100))",
        "CREATE INDEX ix_users_name ON users (name)",
        "ALTER TABLE users ADD COLUMN IF NOT EXISTS phone VARCHAR(50)",
        "INSERT INTO users (id) VALUES (1)",
        "  drop table users",
    ],
)
def test_ddl_and_writes_are_not_explained(statement):
    assert _fire(statement).executed == []


def test_executemany_reads_are_not_explained():
    """A batch of parameter sets has no single EXPLAIN-able plan the way
    this listener runs it -- skip rather than mis-EXPLAIN the first one."""
    assert _fire("SELECT * FROM users WHERE name = %s", executemany=True).executed == []


@pytest.mark.parametrize(
    "statement",
    ["SELECT * FROM users WHERE name = 'x'", "  with t as (select 1) select * from t"],
)
def test_reads_are_explained(statement):
    executed = _fire(statement).executed
    assert len(executed) == 1
    assert executed[0].upper().startswith("EXPLAIN")
