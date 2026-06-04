# 33 FEATURE FLAG — Feature Flag Checklist

> **Purpose:** Audit configuration options, dynamic updates, targeting criteria, and flag cleanup tickets.
> Copilot: Scan backend configuration files and verify flag definitions include clean fallback default states.
> A release is only approved when every section shows `[x]` on all items.

---

## How to use this file

- Mark each item `[x]` as you complete it.
- Run `grep -c '\[ \]' 33_FEATURE_FLAG_CHECKLIST.md` to count remaining open items — zero means ready.
- Copilot instructions at the top of each section describe what to verify automatically where possible.

---

## Pillar 1 — Dynamic Configuration Gating

> **Copilot:** Verify that the code satisfies the 'Dynamic Configuration Gating' constraints in the current PR diff.

- [ ] Feature flags control new UI elements and routes dynamically.
- [ ] Client apps poll for configuration updates periodically.

**Sign-off:** `[ ]` Dynamic Configuration Gating verified by _______________  Date: _______________

---

## Pillar 2 — Beta Targeting Rules

> **Copilot:** Verify that the code satisfies the 'Beta Targeting Rules' constraints in the current PR diff.

- [ ] Targeting rules restrict beta features access to specific user IDs.
- [ ] Regional rollout parameters allow gradual flag deployment.

**Sign-off:** `[ ]` Beta Targeting Rules verified by _______________  Date: _______________

---

## Pillar 3 — Fallback Default State Safety

> **Copilot:** Verify that the code satisfies the 'Fallback Default State Safety' constraints in the current PR diff.

- [ ] Feature flags default states are configured safely (default: OFF).
- [ ] Database fallback queries remain active if flag parameters fail.

**Sign-off:** `[ ]` Fallback Default State Safety verified by _______________  Date: _______________

---

## Pillar 4 — Backlog Flag Cleanup scheduling

> **Copilot:** Verify that the code satisfies the 'Backlog Flag Cleanup scheduling' constraints in the current PR diff.

- [ ] A cleanup ticket is created in the product backlog to remove flag references after launch.
- [ ] Code paths keep flag checks separated to allow easy deletion.

**Sign-off:** `[ ]` Backlog Flag Cleanup scheduling verified by _______________  Date: _______________

---

## Pillar 5 — Remote Config Sync Latency

> **Copilot:** Verify that the code satisfies the 'Remote Config Sync Latency' constraints in the current PR diff.

- [ ] Feature flag status updates propagate to clients within 60s of change.
- [ ] Client local caches store configurations to support offline startup.

**Sign-off:** `[ ]` Remote Config Sync Latency verified by _______________  Date: _______________

---

## Pillar 6 — Performance Overhead Auditing

> **Copilot:** Verify that the code satisfies the 'Performance Overhead Auditing' constraints in the current PR diff.

- [ ] Remote config network queries checked for latency impacts on app startup.

**Sign-off:** `[ ]` Performance Overhead Auditing verified by _______________  Date: _______________

---

## Final Go / No-Go Gate

Run this command to count open items before release approval:

```bash
grep -c '\[ \]' 33_FEATURE_FLAG_CHECKLIST.md
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
