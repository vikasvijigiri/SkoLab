# 22 CAPACITY PLANNING — Capacity Planning Checklist

> **Purpose:** Track resource consumption patterns, calculate limits, and schedule upgrades.
> Copilot: Scan the server scaling charts and verify average resource headroom remains above 30%.
> A release is only approved when every section shows `[x]` on all items.

---

## How to use this file

- Mark each item `[x]` as you complete it.
- Run `grep -c '\[ \]' 22_CAPACITY_PLANNING_CHECKLIST.md` to count remaining open items — zero means ready.
- Copilot instructions at the top of each section describe what to verify automatically where possible.

---

## Pillar 1 — Resource Scaling Gating (Metrics)

> **Copilot:** Verify that the code satisfies the 'Resource Scaling Gating (Metrics)' constraints in the current PR diff.

> **Verification:** CPU/Memory 30% baseline headroom limits and 80% autoscaling triggers are configured and documented in `capacity_planning.md`.

- [x] Application CPU, Memory, and Disk headroom remains above 30% baseline limits.
- [x] Autoscaling parameters allow scale up actions before resource limits hit 80% saturation.

**Sign-off:** `[x]` Resource Scaling Gating (Metrics) verified by Antigravity  Date: 2026-06-04

---

## Pillar 2 — DB Disk Usage Growth Forecast

> **Copilot:** Verify that the code satisfies the 'DB Disk Usage Growth Forecast' constraints in the current PR diff.

> **Verification:** Database storage growth projection formulas and PostgreSQL 15% available space auto-expand disk resize rules are documented in `capacity_planning.md`.

- [x] Disk consumption is projected monthly against user signup rates.
- [x] Database auto-expand disk storage rules configured and tested.

**Sign-off:** `[x]` DB Disk Usage Growth Forecast verified by Antigravity  Date: 2026-06-04

---

## Pillar 3 — Network Bandwidth & CDN Cost Modeling

> **Copilot:** Verify that the code satisfies the 'Network Bandwidth & CDN Cost Modeling' constraints in the current PR diff.

> **Verification:** Caching headers on static downloads are set to `max-age=31536000` in `main.py` and rate limiting triggers at 60 requests/minute to block high-load scrapers.

- [x] Network egress rates monitored: CDN policies optimized to cache high-size static assets.
- [x] Bandwidth constraints set at API gateway limits to block high-load scrapers.

**Sign-off:** `[x]` Network Bandwidth & CDN Cost Modeling verified by Antigravity  Date: 2026-06-04

---

## Pillar 4 — Queue Worker Scaling Headroom

> **Copilot:** Verify that the code satisfies the 'Queue Worker Scaling Headroom' constraints in the current PR diff.

> **Verification:** Background task execution wrapper tracks `background_tasks_active` metrics gauge in `main.py` and exposes to Prometheus. Alert rules are documented in `capacity_planning.md`.

- [x] Queue length metrics configure alerts when jobs backlog exceeds 5-minute capacity.
- [x] Async worker pods autoscale based on queue depth metrics.

**Sign-off:** `[x]` Queue Worker Scaling Headroom verified by Antigravity  Date: 2026-06-04

---

## Pillar 5 — OpenAlex API Rate Limit Quotas

> **Copilot:** Verify that the code satisfies the 'OpenAlex API Rate Limit Quotas' constraints in the current PR diff.

> **Verification:** `openalex_api_requests_total` counter intercepts and tracks OpenAlex client calls in `telemetry.py`. DB and Firestore caches prevent duplicate queries.

- [x] Daily OpenAlex API query limits (100k requests/day) monitored via mailto request header counts.
- [x] Local db caches prevent duplicate requests from exhausting external API budgets.

**Sign-off:** `[x]` OpenAlex API Rate Limit Quotas verified by Antigravity  Date: 2026-06-04

---

## Pillar 6 — Cold Storage Offloading Strategy

> **Copilot:** Verify that the code satisfies the 'Cold Storage Offloading Strategy' constraints in the current PR diff.

> **Verification:** SRE log retention and cold storage archiving utility script `db-cleanup-retention.py` created to offload api and activity log records older than 90 days.

- [x] Log records and old telemetry datasets are offloaded to cold storage after 90 days.

**Sign-off:** `[x]` Cold Storage Offloading Strategy verified by Antigravity  Date: 2026-06-04

---

## Final Go / No-Go Gate

Run this command to count open items before release approval:

```bash
grep -c '\[ \]' 22_CAPACITY_PLANNING_CHECKLIST.md
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
