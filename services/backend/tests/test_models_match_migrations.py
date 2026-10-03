"""Every table a migration creates must also be an ORM model.

New environments (CI staging, a fresh project) create their schema from the
models and stamp Alembic at head (scripts/run_migrations.py), so a table
that exists only as a migration is silently missing there. The 2026-10-03
QA suite caught exactly that: websocket_tickets, so WebSocket tickets
failed with 503 on staging while production (migrated) worked.
"""

import re
from pathlib import Path

import app.models.user_models  # noqa: F401 - registers every table on Base
from app.db.database import Base

VERSIONS = Path(__file__).resolve().parents[1] / "alembic" / "versions"


def test_models_define_every_migrated_table():
    created = set()
    for migration in VERSIONS.glob("*.py"):
        created |= set(re.findall(r"create_table\(\s*[\"']([a-z_]+)", migration.read_text(encoding="utf-8")))
    assert created, "no create_table calls found; did the migrations move?"
    missing = sorted(created - set(Base.metadata.tables))
    assert not missing, f"tables created by migrations but missing from the models: {missing}"
