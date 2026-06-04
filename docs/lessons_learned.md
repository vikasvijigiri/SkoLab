# SkoLab Engineering — Lessons Learned Knowledge Base

> This document aggregates lessons learned from all resolved incidents, postmortems, and near-misses.
> It is updated automatically after every postmortem is completed.
> Reference: [Post-Mortem Checklist (Pillar 5)](../checklists/28_POSTMORTEM_CHECKLIST.md)

---

## How to Use This Document

1. After every postmortem, add a new entry to this file under the relevant category.
2. Post a summary to the `#engineering` Slack channel (copy the "Published Lesson" block).
3. Update any relevant operational runbooks (see "Runbook Updates" column).

---

## Incident Lessons

### inc-2026-001 — OpenAlex Upstream API Outage
**Date:** 2026-06-03 | **Severity:** P1 | **Duration:** 1h 30m

**Root Cause:** No circuit breaker existed for the OpenAlex API HTTP client. When OpenAlex had an upstream outage, uncached backend requests cascaded into HTTP 502 errors for 18 minutes before a manual kill switch was applied.

**Key Lesson:**
> Every external API dependency must have an automatic circuit breaker configured on the HTTP client. Do not rely on manual kill switches as the first line of defense against upstream failures. Treat external API outages as a first-class failure mode, not an edge case.

**Actions Taken (see [full postmortem](./postmortems/inc-2026-001_openalex_outage.md)):**
- CA-001: Implement circuit breaker on OpenAlex `httpx` client
- CA-002: Add `OpenAlexApiHighErrorRate` Prometheus alert
- CA-003: Add OpenAlex failure runbook section
- CA-004: Track OpenAlex API status codes as separate Prometheus label dimensions

**Runbooks Updated:** `docs/runbooks/incident.md` (Section E — pending CA-003 completion)

**Published to #engineering:** `[ ]` Pending

---

### inc-2026-002 — LLM Service Rate Limits Exhaustion
**Date:** 2026-06-04 | **Severity:** P2 | **Duration:** 1h 15m

**Root Cause:** Background profile enrichment jobs (`fetch_physics_profiles.py`) and live user-facing agent chat shared the same Groq API key with no token quota coordination. Concurrent load exhausted the daily token quota, triggering 429 errors before the OpenRouter fallback stabilized (15-minute error window).

**Key Lesson:**
> Treat API token quotas as a finite shared resource — apply the same discipline as database connection pools. Background jobs must be rate-limited against the same quota they share with user-facing traffic, with dedicated separate keys where volume demands it. Always add an early-warning quota alert that fires before exhaustion, not after.

**Actions Taken (see [full postmortem](./postmortems/inc-2026-002_llm_rate_limits.md)):**
- CA-001: Add `groq_tokens_consumed_total` Prometheus metric
- CA-002: Add `LlmApiQuotaApproaching` early-warning Prometheus alert
- CA-003: Rate-limit background enrichment jobs to 30% of daily Groq token quota
- CA-004: Separate Groq API keys for background vs. live traffic
- CA-005: Add LLM rate limit runbook section

**Runbooks Updated:** `docs/runbooks/incident.md` (Section F — pending CA-005 completion)

**Published to #engineering:** `[ ]` Pending

---

## Cross-Cutting Patterns

### Pattern: Missing External API Circuit Breakers
**Observed in:** inc-2026-001
**Risk:** Every external API integration without a circuit breaker is a potential cascade failure point.
**Mitigation:** Apply a circuit breaker to every `httpx` client that calls an external API. Trip on >20% 5xx rate over 30 seconds, recover after 60-second backoff.

---

### Pattern: Shared Quota Between Background and Foreground Consumers
**Observed in:** inc-2026-002
**Risk:** Background batch jobs and live user traffic competing for the same API quota cause unexpected exhaustion during concurrent load.
**Mitigation:** Separate API credentials for background vs. live traffic. Add quota consumption metrics and early-warning alerts at 80% quota utilization.

---

### Pattern: Manual-Only Kill Switch as First-Line Defense
**Observed in:** inc-2026-001
**Risk:** Kill switches require SRE awareness and manual action; 18-minute error window before application.
**Mitigation:** Automatic circuit breakers should trip before manual kill switches are needed. Kill switches remain for intentional feature disabling, not emergency protection.

---

## Open Action Items Tracker

| Action ID | Incident | Action | Owner | Target Date | Status |
|---|---|---|---|---|---|
| CA-001 | inc-2026-001 | Circuit breaker on OpenAlex httpx client | Backend Lead | 2026-06-10 | Open |
| CA-002 | inc-2026-001 | `OpenAlexApiHighErrorRate` alert rule | SRE Lead | 2026-06-08 | Open |
| CA-003 | inc-2026-001 | OpenAlex failure runbook section | On-Call SRE | 2026-06-07 | Open |
| CA-004 | inc-2026-001 | Per-status-code metric labels for OpenAlex | Backend Lead | 2026-06-10 | Open |
| CA-001 | inc-2026-002 | `groq_tokens_consumed_total` metric | Backend Lead | 2026-06-10 | Open |
| CA-002 | inc-2026-002 | `LlmApiQuotaApproaching` alert rule | SRE Lead | 2026-06-08 | Open |
| CA-003 | inc-2026-002 | Rate-limit background enrichment jobs | Backend Lead | 2026-06-11 | Open |
| CA-004 | inc-2026-002 | Separate Groq API keys for background/live | Backend Lead | 2026-06-15 | Open |
| CA-005 | inc-2026-002 | LLM rate limit runbook section | On-Call SRE | 2026-06-07 | Open |
