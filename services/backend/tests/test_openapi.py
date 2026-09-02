"""OpenAPI schema: builds, is well-formed, and does not drift from the snapshot.

The snapshot at ``api-contracts/openapi.snapshot.json`` is the committed copy of
``app.openapi()``. A contract change is meant to show up as a reviewable diff to
that file. If the snapshot is absent it is written and the diff assertion skips
with an instruction to generate it via ``scripts/gen_openapi_snapshot.py`` and
commit it.
"""

import json
from pathlib import Path

import pytest

from app.main import app

# tests/ -> backend -> services -> repo root (which holds api-contracts/).
_SNAPSHOT = (
    Path(__file__).resolve().parents[3] / "api-contracts" / "openapi.snapshot.json"
)


def _schema() -> dict:
    return app.openapi()


def test_openapi_builds_without_error():
    schema = _schema()
    assert isinstance(schema, dict)
    assert schema.get("openapi", "").startswith("3.")


def test_openapi_has_info_version():
    assert _schema().get("info", {}).get("version")


def test_every_path_has_at_least_one_response():
    schema = _schema()
    paths = schema.get("paths", {})
    assert paths, "no paths in the generated schema"
    missing = []
    for path, item in paths.items():
        for method, op in item.items():
            if method.lower() not in {
                "get",
                "post",
                "put",
                "patch",
                "delete",
            }:
                continue
            responses = {
                code for code in (op.get("responses") or {}) if code != "default"
            }
            if not responses:
                missing.append(f"{method.upper()} {path}")
    assert not missing, f"path operations with no documented response: {missing}"


def test_schema_matches_committed_snapshot():
    serialised = json.dumps(_schema(), indent=2, sort_keys=True) + "\n"
    if not _SNAPSHOT.exists():
        _SNAPSHOT.parent.mkdir(parents=True, exist_ok=True)
        _SNAPSHOT.write_text(serialised, encoding="utf-8")
        pytest.skip(
            "openapi.snapshot.json bootstrapped — run "
            "`python scripts/gen_openapi_snapshot.py` and commit "
            "api-contracts/openapi.snapshot.json to arm this guard"
        )
    current = _SNAPSHOT.read_text(encoding="utf-8")
    assert current == serialised, (
        "Generated OpenAPI schema differs from api-contracts/openapi.snapshot.json. "
        "If the change is intended, regenerate: "
        "`cd services/backend && python scripts/gen_openapi_snapshot.py` and commit "
        "the updated snapshot."
    )
