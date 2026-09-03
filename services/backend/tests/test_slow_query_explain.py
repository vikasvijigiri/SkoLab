"""The slow-query auto-EXPLAIN listener must not EXPLAIN DDL.

`EXPLAIN CREATE TABLE ...` / `EXPLAIN CREATE INDEX ...` are syntax errors in
PostgreSQL, and issuing a failing statement from inside `after_cursor_execute`
aborts the caller's transaction — which broke `init_db()` against a real
Postgres in CI (first-run DDL on a cold container exceeds the 100 ms slow
threshold). Regression guard: only SELECT / WITH get auto-EXPLAIN'd.
"""

from __future__ import annotations

import time
from types import SimpleNamespace

import pytest

from app.db import database as dbmod


class _RecordingConn:
    def __init__(self) -> None:
        self.executed: list[str] = []

    def execute(self, clause, parameters=None):  # noqa: ARG002
        self.executed.append(str(clause))

        class _R:
            @staticmethod
            def fetchall():
                return [("Seq Scan",)]

        return _R()


def _fire(statement: str) -> _RecordingConn:
    conn = _RecordingConn()
    # _query_start_time well in the past -> total_time_ms > 100 -> slow path.
    ctx = SimpleNamespace(_query_start_time=time.perf_counter() - 5.0)
    dbmod.after_cursor_execute(
        conn,
        cursor=None,
        statement=statement,
        parameters=None,
        context=ctx,
        executemany=False,
    )
    return conn


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


@pytest.mark.parametrize(
    "statement",
    ["SELECT * FROM users WHERE name = 'x'", "  with t as (select 1) select * from t"],
)
def test_reads_are_explained(statement):
    executed = _fire(statement).executed
    assert len(executed) == 1
    assert executed[0].upper().startswith("EXPLAIN")
