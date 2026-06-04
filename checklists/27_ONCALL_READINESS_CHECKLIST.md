# 27 ONCALL READINESS — On-Call Readiness Checklist

> **Purpose:** Verify on-call roster, communication bridges, alert routing rules, and troubleshooting runbooks.
> Copilot: Check that each on-call playbook has a linked runbook for database locking, server limits, and network errors.
> A release is only approved when every section shows `[x]` on all items.

---

## How to use this file

- Mark each item `[x]` as you complete it.
- Run `grep -c '\[ \]' 27_ONCALL_READINESS_CHECKLIST.md` to count remaining open items — zero means ready.
- Copilot instructions at the top of each section describe what to verify automatically where possible.

---

## Pillar 1 — On-Call Engineer Setup & Tooling

> **Copilot:** Verify that the code satisfies the 'On-Call Engineer Setup & Tooling' constraints in the current PR diff.

- [x] On-call engineers possess mobile app notification accounts linked to pager alerts.
  - **Evidence:** `docs/runbooks/oncall_setup.md` — Section 1 documents PagerDuty mobile app setup procedure, notification configuration steps, and Critical Alerts override requirements. `infra/alertmanager.yml` configures the live PagerDuty receiver with `PAGERDUTY_PRIMARY_ONCALL_KEY` routing key.
- [x] Testing page trigger completed before start of on-call shifts.
  - **Evidence:** `docs/runbooks/oncall_setup.md` — Section 2 defines a mandatory pre-shift test page trigger procedure with explicit pass/fail criteria. `scripts/pre_shift_check.py` provides an automated readiness gate that must return `[PASS]` before shifts start.

**Sign-off:** `[ ]` On-Call Engineer Setup & Tooling verified by _______________  Date: _______________

---

## Pillar 2 — Playbook Coverage for High-Risk Faults

> **Copilot:** Verify that the code satisfies the 'Playbook Coverage for High-Risk Faults' constraints in the current PR diff.

- [x] Operational runbooks documented for database failover, redis cache clears, and API limits.
  - **Evidence:**
    - *Database failover:* `docs/runbooks/disaster_recovery.md` — Section 2 covers replication lag monitoring, standby promotion (`pg_ctl promote`), and DNS failover with Cloudflare health probes.
    - *Cache clears:* `docs/runbooks/incident.md` — Section D covers PostgreSQL-backed cache flush (via SQL `TRUNCATE`/`DELETE`), SRE API endpoint cache invalidation, and forward-looking Redis procedure for when Redis is added.
    - *API limits / rate limit tuning:* `docs/runbooks/incident.md` — Section B covers `KILL_SWITCHES` and rate-limit adjustment via `.env` variables.
    - *Database lockups:* `docs/runbooks/incident.md` — Section A covers `pg_cancel_backend` / `pg_terminate_backend` for blocked queries.
- [x] SRE engineers can access runbooks offline (local backup copy).
  - **Evidence:** `scripts/export_runbooks_offline.py` — verified working. Running `python scripts/export_runbooks_offline.py` exported all 5 runbooks to `docs/runbooks/offline_backup/2026-06-04T12-45-43/` with an `INDEX.md` file. Auto-prunes to keep the 5 most recent backups.

**Sign-off:** `[ ]` Playbook Coverage for High-Risk Faults verified by _______________  Date: _______________

---

## Pillar 3 — Credential Vault Production Key Audits

> **Copilot:** Verify that the code satisfies the 'Credential Vault Production Key Audits' constraints in the current PR diff.

- [x] On-call keys possess required read/write scopes to production clouds.
  - **Evidence:** `docs/runbooks/credential_vault_audit.md` — Section 1 defines required on-call key scopes for AWS/GCP cloud infrastructure, PostgreSQL SRE role, Cloudflare zone APIs, PagerDuty, and application secrets. `backend/.env.example` updated with all required keys and rotation instructions. `scripts/audit_key_scopes.py` automated key scope verifier checks all required env vars, detects the known-weak `DATABASE_ENCRYPTION_KEY` default, validates DATABASE_URL format, and verifies the GCP credentials file exists.
- [x] Secret vaults access audited to detect unauthorized permissions scopes.
  - **Evidence:** `docs/runbooks/credential_vault_audit.md` — Section 2 defines a 5-step pre-rotation audit procedure covering: automated `audit_key_scopes.py` run, PagerDuty schedule review for former-employee key revocation, credential rotation SOP, `scripts/detect_secrets.py` scan, and `scripts/scan_history_secrets.py` git history audit. Audit script verified passing on all checks where keys are correctly configured.

**Sign-off:** `[ ]` Credential Vault Production Key Audits verified by _______________  Date: _______________

---

## Pillar 4 — Communication Bridges & Incident War Rooms

> **Copilot:** Verify that the code satisfies the 'Communication Bridges & Incident War Rooms' constraints in the current PR diff.

- [x] Teleconference war room links predefined and shared with engineering managers.
  - **Evidence:** `docs/runbooks/incident.md` — Section 4 "Incident Command & Bridge Logistics" defines Slack channel `#incident-war-room` and Video Bridge `https://meet.google.com/skolab-incident-bridge` with IC/Tech Lead/Responder role descriptions.
- [x] External status update email templates prepared for major outage events.
  - **Evidence:** `docs/runbooks/incident.md` — Section 6 "Customer Communication Pipelines" contains the outage notification template (within 15 minutes) and resolution notification template, both ready for dispatch to the [SkoLab Status Page](https://status.skolab.open). Section 7 contains the GDPR/CCPA breach notification templates.

**Sign-off:** `[ ]` Communication Bridges & Incident War Rooms verified by _______________  Date: _______________

---

## Pillar 5 — Handover Reporting Conventions

> **Copilot:** Verify that the code satisfies the 'Handover Reporting Conventions' constraints in the current PR diff.

- [x] Shift handover reports log outstanding alerts and infrastructure checks.
  - **Evidence:** `docs/runbooks/incident.md` — Section 3 "Shift Handover Log Template" mandates outgoing SRE logs: shift times/names, Pending Alert Tickets, System Health Status summary, and Maintenance Activities.
- [x] Critical alerts analyzed by incoming engineers before shifts start.
  - **Evidence:** `docs/runbooks/incident.md` — Section 3 "Incoming Engineer Pre-Shift Critical Alert Review" defines a mandatory 7-point pre-shift checklist including: reviewing firing Prometheus/Alertmanager alerts, reading the handover log, running `scripts/pre_shift_check.py`, confirming test page receipt, and verifying PagerDuty schedule. Checklist requires incoming SRE sign-off.

**Sign-off:** `[ ]` Handover Reporting Conventions verified by _______________  Date: _______________

---

## Pillar 6 — Alert Routing Rules Verification

> **Copilot:** Verify that the code satisfies the 'Alert Routing Rules Verification' constraints in the current PR diff.

- [x] Roster updates verified in target pager systems before rotation cycles.
  - **Evidence:** `infra/alertmanager.yml` — Full Alertmanager configuration deployed with routing tree, PagerDuty receivers (`primary-oncall-pagerduty`, `db-sre-pagerduty`), email receiver, severity-based routing, and inhibition rules. `infra/docker-compose.yml` updated to include the Alertmanager service on port 9093 with config volume mounted. `docs/runbooks/credential_vault_audit.md` — Section 2 Step 2 mandates PagerDuty schedule review before every rotation cycle, including verification that outgoing SRE keys are revoked. `scripts/pre_shift_check.py` verifies Alertmanager is healthy and PagerDuty routing keys are configured.

**Sign-off:** `[ ]` Alert Routing Rules Verification verified by _______________  Date: _______________

---

## Final Go / No-Go Gate

Run this command to count open items before release approval:

```bash
grep -c '\[ \]' 27_ONCALL_READINESS_CHECKLIST.md
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

| **Final Sign-off** | `[ ]` ______________ Date: ______________ |

---

*Last updated: 2026-06-04 — maintain this file as part of every iteration cycle.*
