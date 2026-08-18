#!/usr/bin/env python3
"""
pre_shift_check.py
------------------
Automated pre-shift readiness gate for SkoLab on-call engineers.

Checks:
  1. Backend /health endpoint is reachable.
  2. Prometheus /metrics endpoint is reachable.
  3. Alertmanager API is reachable and healthy.
  4. All required environment variables are set (key scope audit).
  5. PagerDuty routing keys are configured.

Usage:
    .\\venv\\Scripts\\python scripts/pre_shift_check.py [--host <backend-host>]

Options:
    --host   Backend base URL. Default: http://127.0.0.1:8000
    --prom   Prometheus base URL. Default: http://127.0.0.1:9090
    --am     Alertmanager base URL. Default: http://127.0.0.1:9093

Exit codes:
    0 = all checks passed — shift may begin
    1 = one or more checks failed — DO NOT begin shift
"""

import argparse
import os
import re
import sys
import urllib.error
import urllib.request


def _require_http_url(url: str) -> str:
    """urlopen honours file:, ftp: and custom schemes, so a URL that arrives
    from the environment can read a local file instead of making a request.
    Only http/https are ever intended here (ruff S310)."""
    if not url.startswith(("http://", "https://")):
        raise ValueError(f"refusing non-http(s) URL: {url!r}")
    return url


# ---------------------------------------------------------------------------
# Resolve project root and load .env
# ---------------------------------------------------------------------------
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.dirname(SCRIPT_DIR)

# Load backend/.env — this is where production credentials live
_ENV_CANDIDATES = [
    os.path.join(PROJECT_ROOT, "services", "backend", ".env"),
    os.path.join(PROJECT_ROOT, ".env"),
]
for _env_file in _ENV_CANDIDATES:
    if os.path.isfile(_env_file):
        with open(_env_file, encoding="utf-8") as _f:
            for _line in _f:
                _line = _line.strip()
                if not _line or _line.startswith("#") or "=" not in _line:
                    continue
                _key, _, _value = _line.partition("=")
                _value = _value.strip().strip('"').strip("'")
                os.environ.setdefault(_key.strip(), _value)
        break


# ---------------------------------------------------------------------------
# HTTP Helper
# ---------------------------------------------------------------------------


def _http_get(url: str, timeout: int = 5) -> tuple[int, str]:
    """Perform a GET request. Returns (status_code, body_text)."""
    try:
        with urllib.request.urlopen(  # noqa: S310 - scheme checked above
            _require_http_url(url), timeout=timeout
        ) as resp:
            return resp.status, resp.read().decode("utf-8", errors="replace")
    except urllib.error.HTTPError as e:
        return e.code, str(e)
    except Exception as e:
        return 0, str(e)


# ---------------------------------------------------------------------------
# Individual Checks
# ---------------------------------------------------------------------------


def check_backend_health(backend_url: str) -> tuple[bool, str]:
    url = backend_url.rstrip("/") + "/health"
    status, body = _http_get(url)
    if status == 200:
        return True, f"HTTP {status} — backend /health OK"
    return (
        False,
        f"HTTP {status} — backend /health not OK (expected 200). Body: {body[:100]}",
    )


def check_prometheus(prom_url: str) -> tuple[bool, str]:
    url = prom_url.rstrip("/") + "/-/healthy"
    status, body = _http_get(url)
    if status == 200:
        return True, f"HTTP {status} — Prometheus healthy"
    return False, f"HTTP {status} — Prometheus not healthy. Body: {body[:100]}"


def check_alertmanager(am_url: str) -> tuple[bool, str]:
    url = am_url.rstrip("/") + "/-/healthy"
    status, body = _http_get(url)
    if status == 200:
        return True, f"HTTP {status} — Alertmanager healthy"
    return False, f"HTTP {status} — Alertmanager not healthy. Body: {body[:100]}"


def check_env_variables() -> tuple:
    """Verify critical on-call environment variables are set."""
    required = [
        "DATABASE_URL",
        "DATABASE_ENCRYPTION_KEY",
        "GROQ_API",
        "OPENROUTER_API_KEY",
        "GOOGLE_APPLICATION_CREDENTIALS",
        "PAGERDUTY_PRIMARY_ONCALL_KEY",
    ]
    missing = [k for k in required if not os.environ.get(k, "").strip()]
    if missing:
        return False, f"Missing required env vars: {', '.join(missing)}"
    # Check DATABASE_ENCRYPTION_KEY is not the known weak default
    weak_default = "MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MTI="
    if os.environ.get("DATABASE_ENCRYPTION_KEY", "") == weak_default:
        return (
            False,
            "DATABASE_ENCRYPTION_KEY is still the insecure hardcoded default. Rotate it.",
        )
    return True, "All required environment variables are set"


def check_pagerduty_keys() -> tuple[bool, str]:
    """Verify PagerDuty routing keys are present and non-placeholder."""
    placeholder_re = re.compile(r"^\${.*}$")
    keys = ["PAGERDUTY_PRIMARY_ONCALL_KEY", "PAGERDUTY_DB_SRE_KEY"]
    for key in keys:
        value = os.environ.get(key, "")
        if not value:
            return False, f"PagerDuty key not set: {key}"
        if placeholder_re.match(value):
            return False, f"PagerDuty key is still a placeholder: {key}='{value}'"
    return True, "PagerDuty routing keys are configured"


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

CHECKS = [
    ("Backend health endpoint reachable", check_backend_health, "backend"),
    ("Prometheus metrics endpoint reachable", check_prometheus, "prom"),
    ("Alertmanager status OK", check_alertmanager, "am"),
    ("All required environment variables are set", check_env_variables, None),
    ("PagerDuty routing keys configured", check_pagerduty_keys, None),
]


def run_checks(backend_url: str, prom_url: str, am_url: str) -> bool:
    print("=" * 60)
    print(" SkoLab Pre-Shift Readiness Check")
    print("=" * 60)
    print()

    url_map = {"backend": backend_url, "prom": prom_url, "am": am_url}
    passed = 0
    failed = 0

    for label, fn, url_key in CHECKS:
        if url_key is not None:
            ok, message = fn(url_map[url_key])
        else:
            ok, message = fn()

        if ok:
            print(f"[PASS] {label}")
            passed += 1
        else:
            print(f"[FAIL] {label}")
            print(f"         x {message}")
            failed += 1

    print()
    if failed == 0:
        print(f"[PASS] Pre-shift readiness check complete ({passed}/{passed}).")
        print("       Shift may begin.")
        return True
    else:
        print(f"[FAIL] Pre-shift readiness check FAILED ({failed} failure(s)).")
        print("       DO NOT begin shift. Resolve all failures first.")
        return False


def main() -> None:
    parser = argparse.ArgumentParser(description="SkoLab pre-shift readiness gate")
    parser.add_argument(
        "--host", default="http://127.0.0.1:8000", help="Backend base URL"
    )
    parser.add_argument(
        "--prom", default="http://127.0.0.1:9090", help="Prometheus base URL"
    )
    parser.add_argument(
        "--am", default="http://127.0.0.1:9093", help="Alertmanager base URL"
    )
    args = parser.parse_args()

    ok = run_checks(args.host, args.prom, args.am)
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
