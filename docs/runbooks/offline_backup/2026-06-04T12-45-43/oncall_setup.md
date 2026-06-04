# On-Call Engineer Setup Guide

This runbook details the exact steps an engineer must take before going on-call to ensure they can respond to production incidents immediately and without friction.

---

## 1. Mobile Pager App Setup

### Supported Pager Platforms
SkoLab uses **PagerDuty** for on-call paging. Ensure you have the PagerDuty mobile app installed and configured before your shift starts.

### Setup Steps
1. **Install the PagerDuty app** on your mobile device:
   - iOS: Search "PagerDuty" in the App Store.
   - Android: Search "PagerDuty" in the Google Play Store.
2. **Log in** with your SkoLab SSO credentials via the app.
3. **Enable push notifications** (Settings → Notifications → Allow All).
4. **Enable Critical Alerts** — on iOS, this ensures alerts override "Do Not Disturb" mode:
   - Settings → Notifications → PagerDuty → Critical Alerts → On.
5. **Enable Override with ringtone** — ensure incident alerts use maximum volume by enabling the "High-Urgency" notification sound.

---

## 2. Pre-Shift Test Page Trigger Procedure

**This test MUST be completed before every shift begins.**

### Test Trigger Steps
1. Log in to the [PagerDuty dashboard](https://app.pagerduty.com) on a desktop browser.
2. Navigate to **Services → SkoLab Backend → Trigger a Test Incident**.
3. Set **Incident Name:** `[PRE-SHIFT TEST] On-Call Readiness Check - {Your Name}`
4. Verify the test page is received on your mobile device **within 2 minutes**.
5. Acknowledge the test incident in the mobile app.
6. Resolve the test incident in the PagerDuty dashboard.
7. Log the successful test trigger in the shift handover log (Section 3 of `incident.md`).

### Failure Procedure
If the test page does NOT arrive within 5 minutes:
1. Check app notification settings (push notifications enabled, not in DND mode).
2. Verify your name is active in the PagerDuty on-call schedule for this rotation.
3. **Do NOT proceed to shift** — escalate to the outgoing SRE and Backend Engineering Lead immediately.

---

## 3. On-Call Tooling Access Verification

Before going on-call, confirm you have active access to all critical systems:

| System | Access URL | Required Scope |
|---|---|---|
| Prometheus | `http://localhost:9090` or infra host | Read metrics, silence alerts |
| Grafana | `http://localhost:3000` | View dashboards |
| Alertmanager | `http://localhost:9093` | View/silence alerts |
| PostgreSQL | Via `psql` or pg_admin | `SUPERUSER` or `db-sre` role |
| Cloudflare Dashboard | `https://dash.cloudflare.com` | Firewall, DNS, Load Balancer |
| GitHub Repo | `https://github.com/skolab/resqit` | Write access for hotfix branches |
| Slack | `#incident-war-room` | Messaging |

### Tooling Access Test
Run this verification script immediately before shift start:
```bash
.\\venv\\Scripts\\python scripts/pre_shift_check.py
```
A successful pre-shift check prints:
```
[PASS] Backend health endpoint reachable
[PASS] Prometheus metrics endpoint reachable
[PASS] Alertmanager status OK
[PASS] All required environment variables are set
[PASS] Pre-shift readiness check complete. Shift may begin.
```
If any item shows `[FAIL]`, resolve it before the shift starts.

---

## 4. Key Contacts During Your Shift

| Role | Contact | Method |
|---|---|---|
| Secondary SRE On-Call | See PagerDuty schedule | PagerDuty escalation |
| Backend Engineering Lead | `backend-lead@skolab.open` / `+1-555-0193` | SMS/Call |
| Database SRE Lead | `db-sre@skolab.open` / `+1-555-0192` | SMS/Call |
| Security & Compliance | `security@skolab.open` / `+1-555-0194` | SMS/Call |
