"""
app/core/incident_config.py
===========================
Centralized Incident Response and Alerting Configuration.
Defines severity levels, escalation matrix, SRE contacts, and on-call rotations.
"""

from typing import Dict, List, Any
import os

# ── Incident Severity Definitions (P0-P3) ─────────────────────────────────────
SEVERITY_LEVELS: Dict[str, Dict[str, Any]] = {
    "P0": {
        "title": "Blocker / Total Outage",
        "description": "Critical system down. Complete service unavailability or database failure.",
        "target_resolution_time": "1 hour",
        "pager_escalation_delay_minutes": 10,
    },
    "P1": {
        "title": "Critical / Key Feature Block",
        "description": "Core flows (search, AI recommendation, LLM service) are unusable for users.",
        "target_resolution_time": "4 hours",
        "pager_escalation_delay_minutes": 10,
    },
    "P2": {
        "title": "Major / Degradation",
        "description": "System performance is degraded (e.g. latency > 2s) but core features remain active.",
        "target_resolution_time": "24 hours",
        "pager_escalation_delay_minutes": 30,
    },
    "P3": {
        "title": "Minor / Cosmetic",
        "description": "Non-functional bugs, documentation typos, or minor styling issues.",
        "target_resolution_time": "7 days",
        "pager_escalation_delay_minutes": 120,
    }
}

# ── SRE / Engineering Contacts ────────────────────────────────────────────────
ENGINEERING_CONTACTS: Dict[str, Dict[str, str]] = {
    "database_sre": {
        "name": "Database SRE Lead",
        "email": "db-sre@skolab.open",
        "phone": "+1-555-0192",
    },
    "backend_lead": {
        "name": "Backend Engineering Lead",
        "email": "backend-lead@skolab.open",
        "phone": "+1-555-0193",
    },
    "security_lead": {
        "name": "Security & Compliance Lead",
        "email": "security@skolab.open",
        "phone": "+1-555-0194",
    }
}

# ── Alert Routing & Escalation Matrix ─────────────────────────────────────────
ALERT_ROUTING: Dict[str, Any] = {
    "primary_on_call": {
        "name": "Primary On-Call SRE",
        "email": os.environ.get("PRIMARY_ON_CALL_EMAIL", "sre-primary@skolab.open"),
        "phone": os.environ.get("PRIMARY_ON_CALL_PHONE", "+1-555-0101"),
        "channel": "SMS / Phone Call",
    },
    "secondary_on_call": {
        "name": "Secondary On-Call SRE",
        "email": os.environ.get("SECONDARY_ON_CALL_EMAIL", "sre-secondary@skolab.open"),
        "phone": os.environ.get("SECONDARY_ON_CALL_PHONE", "+1-555-0102"),
        "channel": "SMS / Phone Call",
    },
    "escalation_path": [
        "primary_on_call",
        "secondary_on_call",
        "backend_lead",
    ]
}
