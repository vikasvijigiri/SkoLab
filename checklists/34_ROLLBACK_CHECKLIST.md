# 34 ROLLBACK — Rollback & Contingency Checklist

> **Purpose:** Review procedures for fast rolling back of mobile app and backend releases.
> Copilot: Scan CI deployment scripts and verify the rollback command is documented and automated.
> A release is only approved when every section shows `[x]` on all items.

---

## How to use this file

- Mark each item `[x]` as you complete it.
- Run `grep -c '\[ \]' 34_ROLLBACK_CHECKLIST.md` to count remaining open items — zero means ready.
- Copilot instructions at the top of each section describe what to verify automatically where possible.

---

## Pillar 1 — DB Migration Rollback Safety

> **Copilot:** Verify that the code satisfies the 'DB Migration Rollback Safety' constraints in the current PR diff.

- [ ] Alembic down migrations tested in staging to ensure zero data loss on rollback.
- [ ] Rolling back database schemas does not break older active client requests.

**Sign-off:** `[ ]` DB Migration Rollback Safety verified by _______________  Date: _______________

---

## Pillar 2 — API Version Backward Compatibility

> **Copilot:** Verify that the code satisfies the 'API Version Backward Compatibility' constraints in the current PR diff.

- [ ] Previous API routers remain active on rollback events.
- [ ] Database modifications preserve backward compatibility constraints.

**Sign-off:** `[ ]` API Version Backward Compatibility verified by _______________  Date: _______________

---

## Pillar 3 — Automated Server Rollback Scripts

> **Copilot:** Verify that the code satisfies the 'Automated Server Rollback Scripts' constraints in the current PR diff.

- [ ] Rollback deployment scripts trigger automatically on deploy failure alerts.
- [ ] Container rollback tasks execute in less than 3 minutes.

**Sign-off:** `[ ]` Automated Server Rollback Scripts verified by _______________  Date: _______________

---

## Pillar 4 — Mobil Client Version Gating

> **Copilot:** Verify that the code satisfies the 'Mobil Client Version Gating' constraints in the current PR diff.

- [ ] Play Store minimum version configurations set to force updates on bugged clients.
- [ ] Client API checks force update dialogs on deprecation events.

**Sign-off:** `[ ]` Mobil Client Version Gating verified by _______________  Date: _______________

---

## Pillar 5 — Client Update Notifications

> **Copilot:** Verify that the code satisfies the 'Client Update Notifications' constraints in the current PR diff.

- [ ] In-app alerts notify active users if a backend rollback limits functionality.
- [ ] Push notifications trigger automatically on critical patch rollouts.

**Sign-off:** `[ ]` Client Update Notifications verified by _______________  Date: _______________

---

## Pillar 6 — Data Rollback Verification

> **Copilot:** Verify that the code satisfies the 'Data Rollback Verification' constraints in the current PR diff.

- [ ] Verification scripts run post-rollback to audit database sync states.

**Sign-off:** `[ ]` Data Rollback Verification verified by _______________  Date: _______________

---

## Final Go / No-Go Gate

Run this command to count open items before release approval:

```bash
grep -c '\[ \]' 34_ROLLBACK_CHECKLIST.md
```

**Approval is granted only when the output is `0`.**

| Check | Status |
|---|---|
| All Pillar 1 items complete | `[ ]` |
| All Pillar 2 items complete | `[ ]` |
| All Pillar 3 items complete | `[ ]` |
| All Pillar 4 items complete | `[ ]` |
| All Pillar 5 items complete | `[ ]` |
| All Pillar 6 items complete | `[ ]` |

| **Final Sign-off** | `[ ]` ______________ Date: ______________ |

---

*Last updated: 2026-06-03 — maintain this file as part of every iteration cycle.*
