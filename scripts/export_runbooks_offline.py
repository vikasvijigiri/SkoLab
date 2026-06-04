#!/usr/bin/env python3
"""
export_runbooks_offline.py
--------------------------
Exports all SkoLab operational runbooks to a local offline directory
(docs/runbooks/offline_backup/) so that on-call SREs can access them
without internet connectivity.

Usage:
    .\\venv\\Scripts\\python scripts/export_runbooks_offline.py

Output:
    A timestamped subdirectory in docs/runbooks/offline_backup/ containing
    copies of all runbook markdown files.
"""

import os
import sys
import shutil
import datetime

# Resolve project root relative to this script's location
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.dirname(SCRIPT_DIR)

RUNBOOKS_SRC = os.path.join(PROJECT_ROOT, "docs", "runbooks")
OFFLINE_BACKUP_BASE = os.path.join(RUNBOOKS_SRC, "offline_backup")


def export_runbooks() -> bool:
    """Copy all runbook markdown files to a timestamped offline backup directory."""
    timestamp = datetime.datetime.now().strftime("%Y-%m-%dT%H-%M-%S")
    backup_dir = os.path.join(OFFLINE_BACKUP_BASE, timestamp)

    # Ensure backup directory exists
    os.makedirs(backup_dir, exist_ok=True)

    # Collect runbook markdown files (exclude the offline_backup directory itself)
    runbook_files = [
        f for f in os.listdir(RUNBOOKS_SRC)
        if f.endswith(".md") and os.path.isfile(os.path.join(RUNBOOKS_SRC, f))
    ]

    if not runbook_files:
        print("[FAIL] No runbook markdown files found in:", RUNBOOKS_SRC)
        return False

    exported = []
    for filename in runbook_files:
        src_path = os.path.join(RUNBOOKS_SRC, filename)
        dst_path = os.path.join(backup_dir, filename)
        shutil.copy2(src_path, dst_path)
        exported.append(filename)
        print(f"  [COPIED] {filename}")

    # Write an index file in the backup directory
    index_path = os.path.join(backup_dir, "INDEX.md")
    with open(index_path, "w", encoding="utf-8") as f:
        f.write(f"# SkoLab Runbook Offline Backup\n\n")
        f.write(f"**Exported at:** {timestamp}\n\n")
        f.write("## Contents\n\n")
        for name in sorted(exported):
            f.write(f"- [{name}](./{name})\n")

    print()
    print(f"[PASS] Exported {len(exported)} runbooks to:")
    print(f"       {backup_dir}")
    print()
    print("Share this directory on a USB drive or network share so on-call SREs")
    print("can access runbooks without internet connectivity.")

    # Clean up old backups — keep only the 5 most recent
    _prune_old_backups(OFFLINE_BACKUP_BASE, keep=5)

    return True


def _prune_old_backups(backup_base: str, keep: int = 5) -> None:
    """Remove oldest backup directories, keeping only the N most recent."""
    if not os.path.isdir(backup_base):
        return

    subdirs = sorted([
        d for d in os.listdir(backup_base)
        if os.path.isdir(os.path.join(backup_base, d))
    ])

    to_remove = subdirs[: max(0, len(subdirs) - keep)]
    for d in to_remove:
        full_path = os.path.join(backup_base, d)
        shutil.rmtree(full_path, ignore_errors=True)
        print(f"[PRUNED] Old backup removed: {d}")


if __name__ == "__main__":
    print("=" * 60)
    print(" SkoLab Offline Runbook Export")
    print("=" * 60)

    success = export_runbooks()
    sys.exit(0 if success else 1)
