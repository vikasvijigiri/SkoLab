"""Generate ``api-contracts/openapi.snapshot.json`` from the live app.

Run once (and after any change that alters a route, parameter, or
``response_model``) in an environment with the backend deps installed, then
commit the file:

    cd services/backend
    pip install -r requirements-dev.txt
    python scripts/gen_openapi_snapshot.py
    git add ../../api-contracts/openapi.snapshot.json

``tests/test_openapi.py::test_schema_matches_committed_snapshot`` skips while the
file is absent and enforces byte-equality once it exists. This script and that
test's bootstrap branch write identical bytes (same ``json.dumps`` args, trailing
newline), so either path produces a committable, guard-arming snapshot.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

# Make `app` importable no matter the working directory this is launched from.
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.main import app  # noqa: E402

# scripts/ -> backend -> services -> repo root (which holds api-contracts/).
_SNAPSHOT = (
    Path(__file__).resolve().parents[3] / "api-contracts" / "openapi.snapshot.json"
)


def main() -> None:
    serialised = json.dumps(app.openapi(), indent=2, sort_keys=True) + "\n"
    _SNAPSHOT.parent.mkdir(parents=True, exist_ok=True)
    _SNAPSHOT.write_text(serialised, encoding="utf-8")
    print(f"wrote {_SNAPSHOT} ({len(serialised)} bytes)")


if __name__ == "__main__":
    main()
