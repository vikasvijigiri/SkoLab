# 36 COST OPTIMIZATION — Cost Optimization Checklist

> **Purpose:** Track resource utilization costs, database query profiles, and cloud pricing configurations.
> Copilot: Analyze the log metrics and verify no endpoint triggers database N+1 query patterns.
> A release is only approved when every section shows `[x]` on all items.

---

## How to use this file

- Mark each item `[x]` as you complete it.
- Run `grep -c '\[ \]' 36_COST_OPTIMIZATION_CHECKLIST.md` to count remaining open items — zero means ready.
- Copilot instructions at the top of each section describe what to verify automatically where possible.

---

## Pillar 1 — Database Query Profiling

> **Copilot:** Verify that the code satisfies the 'Database Query Profiling' constraints in the current PR diff.

- [ ] Database queries profiled: slow-running queries are optimized or indexed to reduce CPU burn.
- [ ] N+1 query patterns eliminated from SQLAlchemy ORM loops.
- [ ] Cache hit rates monitored to optimize database read transactions.

**Sign-off:** `[ ]` Database Query Profiling verified by _______________  Date: _______________

---

## Pillar 2 — Host Server Sizing & Autoscaling

> **Copilot:** Verify that the code satisfies the 'Host Server Sizing & Autoscaling' constraints in the current PR diff.

- [ ] Cloud resources are sized to handle average DAU limits without over-provisioning.
- [ ] Autoscaling scale-down parameters return nodes to baseline counts on traffic drops.

**Sign-off:** `[ ]` Host Server Sizing & Autoscaling verified by _______________  Date: _______________

---

## Pillar 3 — CDN Caching Efficiency

> **Copilot:** Verify that the code satisfies the 'CDN Caching Efficiency' constraints in the current PR diff.

- [ ] Static assets caching configurations hit rate exceeds 85% on edge PoPs.
- [ ] Images are resized by device DPR to minimize network bandwidth costs.

**Sign-off:** `[ ]` CDN Caching Efficiency verified by _______________  Date: _______________

---

## Pillar 4 — LLM API Cost Optimization

> **Copilot:** Verify that the code satisfies the 'LLM API Cost Optimization' constraints in the current PR diff.

- [ ] Model cache layer stores LLM outputs to prevent redundant external API queries.
- [ ] Token sizes trimmed dynamically by summarizing conversation history segments.

**Sign-off:** `[ ]` LLM API Cost Optimization verified by _______________  Date: _______________

---

## Pillar 5 — Storage Tier Tiering (Glacier)

> **Copilot:** Verify that the code satisfies the 'Storage Tier Tiering (Glacier)' constraints in the current PR diff.

- [ ] Older log files and telemetry backups offloaded to cheaper cold storage tiers.
- [ ] Database partition schemes archive inactive data to secondary tables.

**Sign-off:** `[ ]` Storage Tier Tiering (Glacier) verified by _______________  Date: _______________

---

## Pillar 6 — Billing Alerts & Budgets

> **Copilot:** Verify that the code satisfies the 'Billing Alerts & Budgets' constraints in the current PR diff.

- [ ] Billing threshold notifications page team leads on cost budget overruns.

**Sign-off:** `[ ]` Billing Alerts & Budgets verified by _______________  Date: _______________

---

## Final Go / No-Go Gate

Run this command to count open items before release approval:

```bash
grep -c '\[ \]' 36_COST_OPTIMIZATION_CHECKLIST.md
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
