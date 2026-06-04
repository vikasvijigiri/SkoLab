# 20 INCIDENT RESPONSE — Incident Response Checklist

> **Purpose:** Define severity levels, pager rules, escalation workflows, and runbooks.
> Copilot: Check that the incident config includes contacts for database SRE, backend, and security leads.
> A release is only approved when every section shows `[x]` on all items.

---

## How to use this file

- Mark each item `[x]` as you complete it.
- Run `grep -c '\[ \]' 20_INCIDENT_RESPONSE_CHECKLIST.md` to count remaining open items — zero means ready.
- Copilot instructions at the top of each section describe what to verify automatically where possible.

---

## Pillar 1 — Incident Severity (P0-P3) Definitions

> **Copilot:** Verify that the code satisfies the 'Incident Severity (P0-P3) Definitions' constraints in the current PR diff.

> **Verification:** Severity definitions are defined in `incident_config.py`. Detailed resolution SLAs are documented in `docs/runbooks/incident.md`.

- [x] Severity definitions class outages clearly: P0 for total loss, P1 for key features block, P2 for degradation.
- [x] Target resolution time targets documented per severity class.

**Sign-off:** `[x]` Incident Severity (P0-P3) Definitions verified by Antigravity  Date: 2026-06-04

---

## Pillar 2 — Alert Routing & Escalation Matrix

> **Copilot:** Verify that the code satisfies the 'Alert Routing & Escalation Matrix' constraints in the current PR diff.

> **Verification:** On-call SRE contact rotation details, DB SRE contacts, and 10-minute secondary escalation steps are configured in `incident_config.py` and runbooks.

- [x] Critical alerts route immediately to primary on-call engineers via phone/SMS.
- [x] Alert escalation routes to secondary on-call contacts if unacknowledged for 10 minutes.

**Sign-off:** `[x]` Alert Routing & Escalation Matrix verified by Antigravity  Date: 2026-06-04

---

## Pillar 3 — Pager Trigger Rules & Shift Handover

> **Copilot:** Verify that the code satisfies the 'Pager Trigger Rules & Shift Handover' constraints in the current PR diff.

> **Verification:** Prometheus alerting rules and SRE shift handover templates are documented in `incident.md`.

- [x] Pager duty systems receive alerts based on Prometheus/Datadog metric rules.
- [x] On-call shifts transition using structured hand-off logs and pending tickets review.

**Sign-off:** `[x]` Pager Trigger Rules & Shift Handover verified by Antigravity  Date: 2026-06-04

---

## Pillar 4 — Incident Command & Bridge Logistics

> **Copilot:** Verify that the code satisfies the 'Incident Command & Bridge Logistics' constraints in the current PR diff.

> **Verification:** Incident Commander role, Google Meet War Room bridge link, and emergency channels are defined in `incident.md`.

- [x] War room communication bridge urls defined and accessible to all tech leads.
- [x] Incident Commander role defined to guide debugging actions, delegating code edits.

**Sign-off:** `[x]` Incident Command & Bridge Logistics verified by Antigravity  Date: 2026-06-04

---

## Pillar 5 — Operational Runbooks & Kill Switches

> **Copilot:** Verify that the code satisfies the 'Operational Runbooks & Kill Switches' constraints in the current PR diff.

> **Verification:** Centralized troubleshooting runbooks created for DB lockups, rollback guides, and hotfixes. `kill_switch_middleware` implemented in `main.py` allowing instant endpoint blocking.

- [x] Runbook procedures cover common failures: database lockups, rate-limiting rules tuning.
- [x] Feature flags serve as kill switches to block high-load routes dynamically.

**Sign-off:** `[x]` Operational Runbooks & Kill Switches verified by Antigravity  Date: 2026-06-04

---

## Pillar 6 — Customer Communication Pipelines

> **Copilot:** Verify that the code satisfies the 'Customer Communication Pipelines' constraints in the current PR diff.

> **Verification:** Public status page incident update and resolution notification templates are prepared in `incident.md`.

- [x] Public system updates schedule templates prepared for communication leads.

**Sign-off:** `[x]` Customer Communication Pipelines verified by Antigravity  Date: 2026-06-04

---

## Final Go / No-Go Gate

Run this command to count open items before release approval:

```bash
grep -c '\[ \]' 20_INCIDENT_RESPONSE_CHECKLIST.md
```

**Approval is granted only when the output is `0`.**

| Check | Status |
|---|---|
| All Pillar 1 items complete | `[x]` |
| All Pillar 2 items complete | `[x]` |
| All Pillar 3 items complete | `[x]` |
| All Pillar 4 items complete | `[x]` |
| All Pillar 5 items complete | `[x]` |
| All Pillar 6 items complete | `[x]` |

| **Final Sign-off** | `[x]` Antigravity Date: 2026-06-04 |

---

*Last updated: 2026-06-04 — maintain this file as part of every iteration cycle.*
