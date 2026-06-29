# Post-Mortem Report: LLM Service Rate Limits Exhaustion

> **Reference:** [Checklist 28](../../checklists/28_POSTMORTEM_CHECKLIST.md) | [Template](./postmortem_template.md)

---

## Incident Metadata

| Field | Value |
|---|---|
| **Incident ID** | `inc-2026-002` |
| **Title** | LLM Service Rate Limits Exhaustion |
| **Severity** | P2 — Degraded Performance |
| **Status** | Resolved |
| **Detected At (UTC)** | `2026-06-04T05:00:00Z` |
| **Resolved At (UTC)** | `2026-06-04T06:15:00Z` |
| **Total Duration** | 1 hour 15 minutes |
| **Incident Commander** | Backend Engineering Lead |
| **Primary On-Call SRE** | Primary SRE On-Call |
| **Postmortem Author** | Backend Engineering Lead |
| **Postmortem Date** | `2026-06-04` |
| **Review Meeting Scheduled** | `2026-06-07T10:00:00Z` (within 5 days of resolution) |

---

## 1. Incident Timeline

> All timestamps are UTC. Timeline traces from initial alert to final verification.

| Time (UTC) | Actor | Event |
|---|---|---|
| `05:00:00` | System / Prometheus | Alert fired: `HighErrorRateDetected` — LLM agent chat routes returning HTTP 429 responses |
| `05:01:30` | PagerDuty | On-Call SRE paged via mobile app |
| `05:03:00` | On-Call SRE | Alert acknowledged; initial log review started |
| `05:05:00` | On-Call SRE | Logs confirmed `groq.RateLimitError: Rate limit reached for model llama-3.3-70b-versatile` on `/api/v1/agent/chat` |
| `05:08:00` | On-Call SRE | Checked `/metrics` — `openalex_api_requests_total` metric confirmed Groq daily quota was exhausted |
| `05:10:00` | Backend Lead | Groq API dashboard confirmed: daily token quota of 500,000 tokens exceeded |
| `05:12:00` | Backend Lead | OpenRouter fallback model identified (`openai/gpt-4o-mini` via `OPENROUTER_API_KEY`) |
| `05:15:00` | On-Call SRE | Confirmed OpenRouter models taking over LLM traffic via `agent_service.py` fallback chain |
| `05:20:00` | On-Call SRE | Verified agent chat routes returning successful responses via OpenRouter fallback |
| `05:30:00` | Tech Lead | Status page updated: "LLM service degraded — running on backup AI provider. Responses may be slower." |
| `06:00:00` | System | Groq daily quota reset (midnight PST = 08:00 UTC, but quota reset came early at 06:00 UTC) |
| `06:05:00` | SRE | Verified Groq API returning successful responses; confirmed traffic splitting normally |
| `06:10:00` | SRE | Ran synthetic health probe — all endpoints returning 200 OK |
| `06:15:00` | Incident Commander | Incident declared resolved; status page updated: "System Restored" |

---

## 2. Root Cause Analysis (5 Whys)

**Symptom:** Users received HTTP 429 errors or delayed responses on AI research assistant (agent chat) routes.

| # | Why? | Answer |
|---|---|---|
| **Why 1** | Why did users experience `429 Too Many Requests` on LLM routes? | The Groq API rejected requests because the daily token quota was exhausted. |
| **Why 2** | Why was the Groq daily token quota exhausted? | A spike in AI-assisted profile enrichment jobs (`fetch_physics_profiles.py`) ran concurrently with high user-driven agent chat load, exceeding the 500,000 daily token limit. |
| **Why 3** | Why did the background profile enrichment jobs and user chat run concurrently without quota coordination? | Background enrichment jobs and the agent service shared the same Groq API key without any quota tracking or mutual rate limiting. |
| **Why 4** | Why was there no quota coordination between background jobs and the live API? | Token quota tracking was not implemented; the assumption was that background jobs run at low-traffic hours and would not conflict with live user load. |
| **Why 5** | Why was this assumption untested? | No monitoring existed for cumulative token consumption across all consumers of the Groq API key. |

### Root Cause Summary
> **Lack of shared Groq API quota tracking between background enrichment jobs and live user-facing agent chat caused token exhaustion. The implicit assumption that background jobs would not compete with user load was untested and incorrect.**

### Contributing Factors
- [x] No Groq API token consumption metric — quota exhaustion was only discovered when 429s were fired
- [x] Background profile enrichment jobs (`fetch_physics_profiles.py`) ran without rate limiting or quota awareness
- [x] Alert only triggered on generic error rate — no `LlmApiRateLimitApproaching` early warning alert existed
- [x] No documented procedure for proactive quota management in runbooks

---

## 3. Financial & Technical Impact Analysis

### Technical Impact

| Metric | Value |
|---|---|
| **Total Outage Duration** | 1 hour 15 minutes (primary provider degraded; fallback operational) |
| **Estimated DAU Impacted** | ~50 users active during early morning UTC window |
| **API Request Errors** | ~75 HTTP 429 errors before fallback took over (~15 minutes) |
| **Error Rate at Peak** | ~60% of agent chat requests failed before OpenRouter fallback activated |
| **Services Affected** | `/api/v1/agent/chat` (primary Groq path) |
| **Data Integrity Impact** | None |

### Business / Financial Impact

| Metric | Value |
|---|---|
| **LLM API Credits Lost** | $0.40 estimated OpenRouter overage for 1 hour of fallback model usage |
| **SRE Engineer Hours Spent** | ~1.5 hours (0.5h investigation + 0.5h verification + 0.5h monitoring) |
| **Customer-Visible Degradation** | Yes — ~15 minutes of 429 errors before fallback completed activation |
| **SLA Breach** | No — P2 SLA is 24 hours; resolved in 1.25 hours |
| **Credits/Refunds Issued** | None — service is currently in free beta |

---

## 4. Corrective Action Items

| ID | Action | Owner | Target Date | Status | Test Gate |
|---|---|---|---|---|---|
| CA-001 | Add Groq API token consumption tracking metric — expose `groq_tokens_consumed_total` via `/metrics`, sourced from response headers (`x-ratelimit-remaining-tokens`) | Backend Lead | 2026-06-10 | Open | Unit tests + Prometheus metrics validation |
| CA-002 | Add Prometheus alert `LlmApiQuotaApproaching` — warn when Groq remaining tokens < 20% of daily limit | SRE Lead | 2026-06-08 | Open | Alertmanager rule test |
| CA-003 | Rate-limit `fetch_physics_profiles.py` to consume no more than 30% of daily Groq token quota — implement a token budget check before each batch | Backend Lead | 2026-06-11 | Open | Script dry-run test + staging validation |
| CA-004 | Separate Groq API keys for background jobs vs. live user traffic (use secondary key for batch enrichment) | Backend Lead | 2026-06-15 | Open | .env.example update + integration test |
| CA-005 | Document LLM rate limit failure response in runbook — add section to `incident.md`: "LLM Rate Limit Exhaustion Response" | On-Call SRE | 2026-06-07 | Open | Peer review |

### Testing Gate Requirement
Each corrective action involving a code change must:
1. Pass unit/integration tests before merge.
2. Receive peer code review (minimum 1 approver).
3. Be deployed to staging before production.
4. Be verified via a health probe or manual test in production.

---

## 5. Lessons Learned

### What Went Well
- The OpenRouter fallback chain in `agent_service.py` activated correctly — no code change was required to switch providers.
- The Groq rate limit error was detectable in application logs immediately.
- Resolution time was well within P2 SLA (1.25 hours vs. 24-hour SLA).

### What Went Wrong
- There was no early-warning quota alert — the 429 errors were the first signal of quota exhaustion.
- Background enrichment jobs and live user traffic competed for the same quota with no coordination.
- The runbook had no documented procedure for LLM rate limit events — SRE relied on general troubleshooting instincts.
- The 15-minute window before the OpenRouter fallback stabilized caused visible user errors.

### Where We Got Lucky
- The incident occurred during low-traffic early morning UTC hours — impact on DAU was limited.
- The OpenRouter fallback was already implemented and functional without requiring emergency code deployment.

### Runbook Updates Required
| Runbook | Section | Update Required |
|---|---|---|
| `docs/runbooks/incident.md` | Section F (new) | Add "LLM Rate Limit Exhaustion Response": check `groq_tokens_consumed_total`, identify fallback status, escalation steps |
| `infra/alertmanager-alerts.yml` | New rule | Add `LlmApiQuotaApproaching` Prometheus alert |

### Lessons to Publish
> **Engineering Channel Post:** "During the June 4 Groq rate limit incident, we discovered that background profile enrichment jobs were consuming shared Groq API quota, causing exhaustion during concurrent user load. The OpenRouter fallback worked as designed, but there was a 15-minute error window before it stabilized. **Actions:** We are adding per-API-key token consumption metrics, an early-warning quota alert, and separating API keys for background vs. live traffic. **Key learning:** Treat API token quotas as a finite shared resource — apply the same discipline as database connection pools."

---

## 6. Post-Incident Review Meeting

| Field | Value |
|---|---|
| **Meeting Date & Time** | `2026-06-07T10:00:00Z` |
| **Facilitator** | Backend Engineering Lead |
| **Attendees** | Primary SRE, Backend Lead, Engineering Lead |
| **Meeting Notes Link** | (To be posted after meeting) |
| **Action Items Reviewed** | Scheduled |

---

## Sign-off

| Role | Name | Signed | Date |
|---|---|---|---|
| Incident Commander | Backend Engineering Lead | `[ ]` | |
| Engineering Lead | Dr. David Davidson | `[ ]` | |
| Primary SRE | Primary SRE On-Call | `[ ]` | |
