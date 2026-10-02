"""Run the same k6 journey locally with secrets in a temporary file, not argv."""
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

from provision import ROOT, journey_secrets, load_environment


def main():
    load_environment(ROOT.parents[1] / ".env")
    executable = os.environ.get("K6_BIN") or shutil.which("k6")
    if not executable:
        raise ValueError("Install k6 or set K6_BIN to its executable path")
    values = journey_secrets()
    if any("\n" in value or "\r" in value for value in values.values()):
        raise ValueError("Journey fixture values must be single-line")
    with tempfile.TemporaryDirectory() as directory:
        path = Path(directory) / "secrets.txt"
        path.write_text("\n".join(f"{name}={value}" for name, value in values.items()), encoding="utf-8")
        result = subprocess.run([executable, "run", "--quiet", "--secret-source", f"file={path}", str(ROOT / "journey.js")],
            capture_output=True, text=True, timeout=100, check=False)
    output = result.stdout + result.stderr
    for value in values.values():
        output = output.replace(value, "[REDACTED]")
    output = re.sub(r"ticket=[^\s\"&]+", "ticket=[REDACTED]", output)
    output = re.sub(r"eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+", "[REDACTED_TOKEN]", output)
    print(output)
    return result.returncode


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (ValueError, subprocess.TimeoutExpired) as exc:
        print(f"Journey verification failed: {exc}", file=sys.stderr)
        sys.exit(1)
