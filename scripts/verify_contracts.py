"""Verify language-neutral contracts against their current producers.

This check intentionally uses only the Python standard library so it can run
in a clean CI job before the backend's heavier dependency installation.
"""

from __future__ import annotations

import ast
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCHEMA_PATH = ROOT / "packages" / "contracts" / "error-response.schema.json"
PYTHON_ERRORS_PATH = ROOT / "services" / "backend" / "app" / "api" / "errors.py"


def python_error_fields() -> tuple[set[str], set[str]]:
    tree = ast.parse(PYTHON_ERRORS_PATH.read_text(encoding="utf-8"))
    for node in tree.body:
        if isinstance(node, ast.ClassDef) and node.name == "ErrorResponse":
            required: set[str] = set()
            optional: set[str] = set()
            for statement in node.body:
                if not isinstance(statement, ast.AnnAssign) or not isinstance(
                    statement.target, ast.Name
                ):
                    continue
                name = statement.target.id
                (optional if statement.value is not None else required).add(name)
            return required, optional
    raise SystemExit("ErrorResponse class was not found")


def main() -> None:
    schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
    schema_required = set(schema["required"])
    schema_fields = set(schema["properties"])
    python_required, python_optional = python_error_fields()
    python_fields = python_required | python_optional

    if schema_required != python_required:
        raise SystemExit(
            f"required fields diverge: schema={sorted(schema_required)} "
            f"python={sorted(python_required)}"
        )
    if schema_fields != python_fields:
        raise SystemExit(
            f"properties diverge: schema={sorted(schema_fields)} "
            f"python={sorted(python_fields)}"
        )
    print(
        "contract compatibility: ErrorResponse matches "
        f"({len(schema_fields)} properties)"
    )


if __name__ == "__main__":
    main()
