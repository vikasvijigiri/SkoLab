#!/usr/bin/env python3
"""
create_postmortem.py
--------------------
Scaffolds a new post-mortem document from the template for a given incident.

Ensures that every incident has a corresponding postmortem file created
immediately after resolution — satisfying Pillar 6 (review scheduling within
5 days) by pre-filling the review meeting date automatically.

Usage:
    python scripts/create_postmortem.py \\
        --id inc-2026-003 \\
        --title "Database Connection Pool Exhaustion" \\
        --severity P1 \\
        --detected "2026-06-10T14:00:00Z" \\
        --resolved "2026-06-10T16:30:00Z"

Output:
    Creates: docs/postmortems/inc-2026-003_database_connection_pool_exhaustion.md
    Updates: docs/incidents.json (adds postmortem_path and review_meeting_at)
"""

import argparse
import datetime
import json
import re
import sys
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = SCRIPT_DIR.parent
TEMPLATE_PATH = PROJECT_ROOT / "docs" / "postmortems" / "postmortem_template.md"
POSTMORTEMS_DIR = PROJECT_ROOT / "docs" / "postmortems"
INCIDENTS_PATH = PROJECT_ROOT / "docs" / "incidents.json"


def slugify(title: str) -> str:
    """Convert a title to a filesystem-safe slug."""
    slug = title.lower()
    slug = re.sub(r"[^a-z0-9]+", "_", slug)
    slug = slug.strip("_")
    return slug[:60]  # Limit length


def compute_review_date(resolved_at: str, days: int = 3) -> str:
    """Compute the review meeting date N days after resolution (default: 3 days, max allowed: 5)."""
    resolved = datetime.datetime.fromisoformat(resolved_at.replace("Z", "+00:00"))
    review = resolved + datetime.timedelta(days=days)
    # Default meeting time: 10:00 UTC
    review = review.replace(hour=10, minute=0, second=0, microsecond=0)
    return review.strftime("%Y-%m-%dT%H:%M:%SZ")


def compute_duration(detected_at: str, resolved_at: str) -> str:
    """Compute the human-readable duration between detection and resolution."""
    detected = datetime.datetime.fromisoformat(detected_at.replace("Z", "+00:00"))
    resolved = datetime.datetime.fromisoformat(resolved_at.replace("Z", "+00:00"))
    delta = resolved - detected
    hours, remainder = divmod(int(delta.total_seconds()), 3600)
    minutes = remainder // 60
    if hours > 0:
        return f"{hours} hour{'s' if hours != 1 else ''} {minutes} minute{'s' if minutes != 1 else ''}"
    return f"{minutes} minute{'s' if minutes != 1 else ''}"


def scaffold_postmortem(
    incident_id: str,
    title: str,
    severity: str,
    detected_at: str,
    resolved_at: str,
) -> Path:
    """Create a postmortem file from the template with pre-filled metadata."""
    if not TEMPLATE_PATH.exists():
        print(f"[FAIL] Template not found at: {TEMPLATE_PATH}")
        sys.exit(1)

    review_date = compute_review_date(resolved_at)
    duration = compute_duration(detected_at, resolved_at)
    slug = slugify(title)
    output_path = POSTMORTEMS_DIR / f"{incident_id}_{slug}.md"

    if output_path.exists():
        print(f"[WARN] Postmortem already exists: {output_path}")
        print("       Delete the existing file to regenerate from template.")
        return output_path

    with open(TEMPLATE_PATH, encoding="utf-8") as f:
        template = f.read()

    # Placeholder substitution is done by the explicit content.replace() calls
    # below, which match whole metadata-table rows rather than bare values. An
    # earlier dict of {placeholder: value} pairs sat here unused and was
    # removed -- it looked like the substitution table while contributing
    # nothing to the output.
    content = template
    # Simple substitutions for the metadata table detected/resolved rows
    content = content.replace(
        "| **Detected At (UTC)** | `YYYY-MM-DDTHH:MM:SSZ` |",
        f"| **Detected At (UTC)** | `{detected_at}` |",
    )
    content = content.replace(
        "| **Resolved At (UTC)** | `YYYY-MM-DDTHH:MM:SSZ` |",
        f"| **Resolved At (UTC)** | `{resolved_at}` |",
    )
    content = content.replace(
        "| **Total Duration** | `X hours Y minutes` |",
        f"| **Total Duration** | {duration} |",
    )
    content = content.replace(
        "| **Incident ID** | `inc-YYYY-NNN` |",
        f"| **Incident ID** | `{incident_id}` |",
    )
    content = content.replace(
        "| **Title** | Brief, descriptive title |",
        f"| **Title** | {title} |",
    )
    content = content.replace(
        "| **Severity** | P0 / P1 / P2 / P3 |",
        f"| **Severity** | {severity} |",
    )
    content = content.replace(
        "| **Status** | open / resolved |",
        "| **Status** | Open |",
    )
    content = content.replace(
        "| **Postmortem Date** | `YYYY-MM-DD` |",
        f"| **Postmortem Date** | `{datetime.date.today().isoformat()}` |",
    )
    content = content.replace(
        "| **Review Meeting Scheduled** | `YYYY-MM-DD HH:MM UTC` (must be within 5 days of resolution) |",
        f"| **Review Meeting Scheduled** | `{review_date}` (must be within 5 days of resolution) |",
    )

    POSTMORTEMS_DIR.mkdir(parents=True, exist_ok=True)
    with open(output_path, "w", encoding="utf-8") as f:
        f.write(content)

    print(f"[PASS] Postmortem scaffolded: {output_path}")
    return output_path


def update_incidents_json(
    incident_id: str,
    postmortem_path: Path,
    review_date: str,
) -> None:
    """Update incidents.json to link the postmortem path and review meeting date."""
    if not INCIDENTS_PATH.exists():
        print(f"[WARN] incidents.json not found at: {INCIDENTS_PATH}. Skipping update.")
        return

    with open(INCIDENTS_PATH, encoding="utf-8") as f:
        incidents = json.load(f)

    relative_path = str(postmortem_path.relative_to(PROJECT_ROOT)).replace("\\", "/")
    updated = False
    for incident in incidents:
        if incident.get("id") == incident_id:
            incident["postmortem_path"] = relative_path
            incident["review_meeting_at"] = review_date
            updated = True
            break

    if not updated:
        print(f"[WARN] Incident '{incident_id}' not found in incidents.json.")
        print("       Add it manually or update incidents.json first.")
        return

    with open(INCIDENTS_PATH, "w", encoding="utf-8") as f:
        json.dump(incidents, f, indent=2)
        f.write("\n")

    print("[PASS] incidents.json updated with postmortem_path and review_meeting_at")


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Scaffold a SkoLab postmortem document"
    )
    parser.add_argument("--id", required=True, help="Incident ID, e.g. inc-2026-003")
    parser.add_argument("--title", required=True, help="Incident title")
    parser.add_argument(
        "--severity",
        required=True,
        choices=["P0", "P1", "P2", "P3"],
        help="Incident severity",
    )
    parser.add_argument(
        "--detected",
        required=True,
        help="Detection timestamp ISO8601 UTC, e.g. 2026-06-10T14:00:00Z",
    )
    parser.add_argument(
        "--resolved",
        required=True,
        help="Resolution timestamp ISO8601 UTC, e.g. 2026-06-10T16:30:00Z",
    )
    args = parser.parse_args()

    print("=" * 60)
    print(" SkoLab Post-Mortem Scaffolder")
    print("=" * 60)
    print()

    review_date = compute_review_date(args.resolved)
    output_path = scaffold_postmortem(
        incident_id=args.id,
        title=args.title,
        severity=args.severity,
        detected_at=args.detected,
        resolved_at=args.resolved,
    )
    update_incidents_json(
        incident_id=args.id,
        postmortem_path=output_path,
        review_date=review_date,
    )

    print()
    print(f"Review meeting pre-filled for: {review_date}")
    print("Next steps:")
    print(
        f"  1. Fill in the timeline, 5-Whys, impact, and corrective actions in: {output_path.name}"
    )
    print(f"  2. Schedule the review meeting at the pre-filled date: {review_date}")
    print("  3. Publish lessons to #engineering channel when complete.")
    print("  4. Add lessons to: docs/lessons_learned.md")


if __name__ == "__main__":
    main()
