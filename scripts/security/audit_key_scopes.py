#!/usr/bin/env python3
"""
audit_key_scopes.py
-------------------
Verifies that all required on-call environment variables are configured
before a shift rotation begins.

Checks:
  - Required application secrets are set (non-empty).
  - DATABASE_ENCRYPTION_KEY is NOT left at the known weak default value.
  - DATABASE_URL uses a valid PostgreSQL scheme.
  - PagerDuty routing keys are configured (for alert paging).
  - No secrets are left at obvious placeholder values.

Usage:
    python scripts/audit_key_scopes.py

Exit code:
    0 = all checks passed
    1 = one or more checks failed
"""

import os
import sys
import re

# Resolve project root (parent of this script's directory)
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.dirname(SCRIPT_DIR)

# Load backend/.env — this is where production credentials live
_ENV_CANDIDATES = [
    os.path.join(PROJECT_ROOT, "backend", ".env"),
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
                # Strip surrounding quotes
                _value = _value.strip().strip('"').strip("'")
                os.environ.setdefault(_key.strip(), _value)
        break


# ---------------------------------------------------------------------------
# Key Definitions — mapped to the ACTUAL env var names used in the codebase
# ---------------------------------------------------------------------------

# Keys that MUST be set (non-empty)
REQUIRED_KEYS = {
    # Core database (config.py: Settings.database_url)
    "DATABASE_URL": "PostgreSQL async connection string",
    # Encryption/HMAC key (config.py: Settings.database_encryption_key)
    "DATABASE_ENCRYPTION_KEY": "AES-128/HMAC-SHA256 key for record-level encryption and integrity signing",
    # LLM API integrations
    "GROQ_API": "Groq LLM API key for AI research assistant features",
    "OPENROUTER_API_KEY": "OpenRouter API key for multi-model LLM routing",
    # Firebase service account
    "GOOGLE_APPLICATION_CREDENTIALS": "Path to Firebase/GCP service account JSON file",
    # On-call paging (added via alertmanager.yml)
    "PAGERDUTY_PRIMARY_ONCALL_KEY": "PagerDuty routing key for primary on-call SRE",
    "PAGERDUTY_DB_SRE_KEY": "PagerDuty routing key for DB SRE escalation",
}

# Keys that should NOT be the known weak default shipped with the codebase
WEAK_DEFAULTS = {
    "DATABASE_ENCRYPTION_KEY": "MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MTI=",
}

# Placeholder patterns to reject
PLACEHOLDER_PATTERNS = [
    r"^changeme$",
    r"^your[-_]secret[-_]here$",
    r"^placeholder$",
    r"^todo$",
    r"^example$",
    r"^replace[-_]me$",
    r"^\${.*}$",   # Un-substituted shell variable references like ${VAR}
]

# Minimum lengths for security-critical secrets
MIN_LENGTH_KEYS = {
    "DATABASE_ENCRYPTION_KEY": 16,
    "PAGERDUTY_PRIMARY_ONCALL_KEY": 12,
    "PAGERDUTY_DB_SRE_KEY": 12,
}


# ---------------------------------------------------------------------------
# Audit Checks
# ---------------------------------------------------------------------------

def check_required_keys() -> list:
    """Verify all required environment variables are set and non-empty."""
    failures = []
    for key, description in REQUIRED_KEYS.items():
        value = os.environ.get(key)
        if value is None:
            failures.append(f"MISSING: {key} ({description})")
        elif value.strip() == "":
            failures.append(f"EMPTY: {key} ({description})")
    return failures


def check_weak_defaults() -> list:
    """Detect keys still set to the known insecure default values."""
    failures = []
    for key, weak_value in WEAK_DEFAULTS.items():
        actual = os.environ.get(key, "")
        if actual == weak_value:
            failures.append(
                f"WEAK DEFAULT: {key} is still the hardcoded development default. "
                f"Rotate it before going on-call in production."
            )
    return failures


def check_placeholder_values() -> list:
    """Detect keys that still hold placeholder values."""
    failures = []
    for key in REQUIRED_KEYS:
        value = os.environ.get(key, "")
        for pattern in PLACEHOLDER_PATTERNS:
            if re.fullmatch(pattern, value, flags=re.IGNORECASE):
                failures.append(
                    f"PLACEHOLDER DETECTED: {key} still has placeholder value: '{value}'"
                )
                break
    return failures


def check_minimum_length() -> list:
    """Verify secret keys meet minimum length requirements."""
    failures = []
    for key, min_len in MIN_LENGTH_KEYS.items():
        value = os.environ.get(key, "")
        if value and len(value) < min_len:
            failures.append(
                f"TOO SHORT: {key} must be at least {min_len} chars (currently {len(value)})."
            )
    return failures


def check_database_url_format() -> list:
    """Verify DATABASE_URL has a valid PostgreSQL scheme."""
    failures = []
    db_url = os.environ.get("DATABASE_URL", "")
    if db_url and not (
        db_url.startswith("postgresql://")
        or db_url.startswith("postgres://")
        or db_url.startswith("postgresql+asyncpg://")
    ):
        failures.append(
            f"INVALID FORMAT: DATABASE_URL must start with 'postgresql://', "
            f"'postgres://', or 'postgresql+asyncpg://'. Got: '{db_url[:40]}...'"
        )
    return failures


def check_gcp_credentials_file() -> list:
    """Verify the GOOGLE_APPLICATION_CREDENTIALS file actually exists."""
    failures = []
    creds_path = os.environ.get("GOOGLE_APPLICATION_CREDENTIALS", "")
    if creds_path:
        # Resolve relative paths against the backend directory
        if not os.path.isabs(creds_path):
            backend_dir = os.path.join(PROJECT_ROOT, "backend")
            creds_path = os.path.join(backend_dir, creds_path)
        if not os.path.isfile(creds_path):
            failures.append(
                f"FILE NOT FOUND: GOOGLE_APPLICATION_CREDENTIALS points to "
                f"non-existent file: '{creds_path}'"
            )
    return failures


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def run_audit() -> bool:
    print("=" * 60)
    print(" SkoLab On-Call Key Scope Audit")
    print("=" * 60)
    print()

    all_failures = []

    checks = [
        ("Required Keys Present", check_required_keys),
        ("No Weak Defaults", check_weak_defaults),
        ("No Placeholder Values", check_placeholder_values),
        ("Minimum Key Length", check_minimum_length),
        ("Database URL Format", check_database_url_format),
        ("GCP Credentials File Exists", check_gcp_credentials_file),
    ]

    for check_name, check_fn in checks:
        failures = check_fn()
        if failures:
            print(f"[FAIL] {check_name}:")
            for f in failures:
                print(f"         x {f}")
            all_failures.extend(failures)
        else:
            print(f"[PASS] {check_name}")

    print()
    if all_failures:
        print(f"[FAIL] Key scope audit FAILED with {len(all_failures)} issue(s).")
        print("       Resolve all failures before starting the on-call shift.")
        return False
    else:
        print("[PASS] All key scope checks PASSED. On-call credentials are in order.")
        return True


if __name__ == "__main__":
    ok = run_audit()
    sys.exit(0 if ok else 1)
