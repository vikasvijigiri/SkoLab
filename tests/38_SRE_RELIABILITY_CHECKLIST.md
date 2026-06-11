# 38 SRE RELIABILITY — SRE & Site Reliability Checklist

> **Purpose:** Maintain SLA, SLI, and SLO boundaries for availability, response times, and failure rates.
> Copilot: Verify that Service Level Indicators (SLIs) are tracked for API latency, error rates, and database connections.
> A release is only approved when every section shows `[x]` on all items.

---

## How to use this file

- Mark each item `[x]` as you complete it.
- Run `grep -c '\[ \]' 38_SRE_RELIABILITY_CHECKLIST.md` to count remaining open items — zero means ready.
- Copilot instructions at the top of each section describe what to verify automatically where possible.

---

## Pillar 1 — SLO & SLI Definition

> **Copilot:** Verify that the code satisfies the 'SLO & SLI Definition' constraints in the current PR diff.

- [ ] SLO target: 99.9% availability of main API endpoints over any 30-day window.
- [ ] Service Level Indicators (SLIs) defined for API latency, error rates, and DB pool connection counts.

**Sign-off:** `[ ]` SLO & SLI Definition verified by _______________  Date: _______________

---

## Pillar 2 — Error Budget Gating

> **Copilot:** Verify that the code satisfies the 'Error Budget Gating' constraints in the current PR diff.

- [ ] Error budgets defined: alerts trigger when error rates exceed 0.1% for 5 minutes.
- [ ] Engineering releases block if error budgets for the month are exhausted.

**Sign-off:** `[ ]` Error Budget Gating verified by _______________  Date: _______________

---

## Pillar 3 — Public System Status Updates

> **Copilot:** Verify that the code satisfies the 'Public System Status Updates' constraints in the current PR diff.

- [ ] System status metrics page syncs status updates on system outages automatically.
- [ ] Incident command playbooks detail public notification templates.

**Sign-off:** `[ ]` Public System Status Updates verified by _______________  Date: _______________

---

## Pillar 4 — Availability Dashboard metrics

> **Copilot:** Verify that the code satisfies the 'Availability Dashboard metrics' constraints in the current PR diff.

- [ ] Grafana dashboards monitor real-time SRE metrics (latency, traffic, errors, saturation).
- [ ] Database lock alerts notify engineers immediately on transaction conflicts.

**Sign-off:** `[ ]` Availability Dashboard metrics verified by _______________  Date: _______________

---

## Pillar 5 — Infrastructure Resilience verification

> **Copilot:** Verify that the code satisfies the 'Infrastructure Resilience verification' constraints in the current PR diff.

- [ ] Kubernetes pod auto-heals: bugged server nodes restart automatically on health probe failures.
- [ ] Load balancers distribute user traffic evenly across available availability zones.

**Sign-off:** `[ ]` Infrastructure Resilience verification verified by _______________  Date: _______________

---

## Pillar 6 — Chaos Engineering Drills

> **Copilot:** Verify that the code satisfies the 'Chaos Engineering Drills' constraints in the current PR diff.

- [ ] Chaos tests run in staging to simulate node restarts and database disconnects.

**Sign-off:** `[ ]` Chaos Engineering Drills verified by _______________  Date: _______________

---

## Final Go / No-Go Gate

Run this command to count open items before release approval:

```bash
grep -c '\[ \]' 38_SRE_RELIABILITY_CHECKLIST.md
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
