# Post-Mortem Report: OpenAlex Upstream API Outage

> **Reference:** [Checklist 28](../../checklists/28_POSTMORTEM_CHECKLIST.md) | [Template](./postmortem_template.md)

---

## Incident Metadata

| Field | Value |
|---|---|
| **Incident ID** | `inc-2026-001` |
| **Title** | OpenAlex Upstream API Outage |
| **Severity** | P1 — Core Feature Outage |
| **Status** | Resolved |
| **Detected At (UTC)** | `2026-06-03T10:00:00Z` |
| **Resolved At (UTC)** | `2026-06-03T11:30:00Z` |
| **Total Duration** | 1 hour 30 minutes |
| **Incident Commander** | Backend Engineering Lead |
| **Primary On-Call SRE** | Primary SRE On-Call |
| **Postmortem Author** | Backend Engineering Lead |
| **Postmortem Date** | `2026-06-04` |
| **Review Meeting Scheduled** | `2026-06-06T10:00:00Z` (within 5 days of resolution) |

---

## 1. Incident Timeline

> All timestamps are UTC. Timeline traces from initial alert to final verification.

| Time (UTC) | Actor | Event |
|---|---|---|
| `10:00:00` | System / Prometheus | Alert fired: `HighErrorRateDetected` — OpenAlex search queries returning HTTP 502/503 |
| `10:02:30` | PagerDuty | On-Call SRE paged via mobile app push notification |
| `10:04:00` | On-Call SRE | Alert acknowledged in PagerDuty; war room opened in `#incident-war-room` |
| `10:06:00` | On-Call SRE | Checked Grafana — confirmed high 5xx rate on `/api/v1/papers/search` and `/api/v1/authors/search` routes |
| `10:09:00` | On-Call SRE | Confirmed backend was running; logs showed `httpx.HTTPStatusError: 502 Bad Gateway` from OpenAlex API responses |
| `10:11:00` | On-Call SRE | Verified OpenAlex status page: upstream outage confirmed at `https://openalex.org/` |
| `10:14:00` | Backend Lead | Verified PgBackedCache (`suggestions_cache`, `profile_cache`) was serving cached responses to users — partial functionality preserved |
| `10:18:00` | SRE | Applied kill switch: `KILL_SWITCHES=papers/search,authors/search` to prevent uncached requests from hitting the downed API and generating 502 cascades |
| `10:20:00` | SRE | Verified kill switch applied — affected routes now return `HTTP 503 Service Unavailable` with informative message |
| `10:25:00` | Tech Lead | Status page updated: "Investigating upstream data provider outage" notification posted |
| `11:10:00` | System | OpenAlex upstream API restored — `openalex_api_requests_total` metric resumed incrementing |
| `11:15:00` | SRE | Removed kill switch, restored search routes |
| `11:20:00` | SRE | Ran synthetic health probe `scripts/synthetic_health_probe.py` — returned HTTP 200 on all monitored endpoints |
| `11:25:00` | SRE | Validated live search results returning correct data |
| `11:30:00` | Incident Commander | Incident declared resolved; status page updated: "System Restored" |

---

## 2. Root Cause Analysis (5 Whys)

**Symptom:** Users received HTTP 502 and 503 errors when performing researcher profile searches and academic paper searches.

| # | Why? | Answer |
|---|---|---|
| **Why 1** | Why did users receive 502/503 errors on search routes? | The backend forwarded requests to the OpenAlex API but received HTTP 502 responses from OpenAlex's servers. |
| **Why 2** | Why did the OpenAlex API return 502 responses? | OpenAlex experienced an upstream infrastructure outage affecting their API gateway, causing service unavailability. |
| **Why 3** | Why did the backend not gracefully degrade before the SRE intervened? | The automatic fallback to cached data (`PgBackedCache`) worked for users whose profiles were already cached, but uncached requests still hit the downed API and surfaced 502s to users. |
| **Why 4** | Why were uncached requests still reaching the downed upstream API? | No automatic circuit breaker existed to stop forwarding requests to OpenAlex once the error rate exceeded a threshold. |
| **Why 5** | Why was no automatic circuit breaker configured? | External API circuit-breaking was not part of the original `httpx` client implementation; it was identified as a future improvement but not yet prioritized. |

### Root Cause Summary
> **Absence of an automatic circuit breaker for the OpenAlex API integration allowed a full upstream outage to cascade into user-visible 502/503 errors. The kill switch required manual SRE intervention to apply.**

### Contributing Factors
- [x] Missing automatic circuit breaker for the OpenAlex API client — manual SRE intervention required to stop cascading errors
- [x] No alerting specifically for upstream API availability (alert only triggered on generic error rate, not on OpenAlex-specific failure mode)
- [x] Runbook did not include a documented OpenAlex-specific failure response procedure
- [ ] Documentation gap (not applicable — general runbook procedures were sufficient once issue was identified)
- [ ] Human error

---

## 3. Financial & Technical Impact Analysis

### Technical Impact

| Metric | Value |
|---|---|
| **Total Outage Duration** | 1 hour 30 minutes |
| **Estimated DAU Impacted** | ~120 users attempting live search during the window |
| **API Request Errors** | ~350 requests received HTTP 502/503 |
| **Error Rate at Peak** | ~40% of search traffic (cached users unaffected) |
| **Services Affected** | `/api/v1/papers/search`, `/api/v1/authors/search`, `/api/v1/collaborators/suggest` |
| **Data Integrity Impact** | None — read-only searches, no data corruption |

### Business / Financial Impact

| Metric | Value |
|---|---|
| **LLM API Credits Lost** | $0 — LLM services were not involved in this incident |
| **SRE Engineer Hours Spent** | ~2 hours (0.5h investigation + 0.5h mitigation + 1h monitoring) |
| **Customer-Visible Degradation** | Yes — users saw 503 errors on search routes for ~18 minutes (post-kill-switch) and 502s for 18 minutes prior |
| **SLA Breach** | No — P1 SLA is 4 hours; resolved in 1.5 hours |
| **Credits/Refunds Issued** | None — service is currently in free beta |

---

## 4. Corrective Action Items

| ID | Action | Owner | Target Date | Status | Test Gate |
|---|---|---|---|---|---|
| CA-001 | Implement automatic circuit breaker for `httpx` calls to OpenAlex API (trip on >20% 5xx rate over 30s, recover after 60s backoff) | Backend Lead | 2026-06-10 | Open | Unit tests + integration test + staging validation |
| CA-002 | Add Prometheus alert rule `OpenAlexApiHighErrorRate` — trigger if `openalex_api_requests_total{status=~"5.."}` > 10 in 2 minutes | SRE Lead | 2026-06-08 | Open | Alertmanager rule validation |
| CA-003 | Update `docs/runbooks/incident.md` to add Section E: "OpenAlex API Upstream Failure Response" with step-by-step kill switch and monitoring procedure | On-Call SRE | 2026-06-07 | Open | Peer review |
| CA-004 | Configure `openalex_api_requests_total` metric to track per-status-code (4xx, 5xx, 2xx) for early upstream degradation detection | Backend Lead | 2026-06-10 | Open | Metrics endpoint verification |

### Testing Gate Requirement
Each corrective action involving a code change must:
1. Pass unit/integration tests before merge.
2. Receive peer code review (minimum 1 approver).
3. Be deployed to staging before production.
4. Be verified via a health probe or manual test in production.

---

## 5. Lessons Learned

### What Went Well
- The PgBackedCache served cached results effectively, shielding ~60% of users from the outage entirely.
- The SRE kill switch mechanism worked correctly and was applied within 18 minutes of the initial alert.
- Shift handover procedures were not triggered (incident resolved within one shift).
- The `/status` endpoint correctly reflected degraded state during the incident.

### What Went Wrong
- No OpenAlex-specific circuit breaker: the system blindly forwarded requests to a known-dead upstream for 18 minutes before manual kill switch application.
- No upstream-specific alert: the generic `HighErrorRateDetected` alert fired, but no specific `OpenAlexApiDown` alert existed.
- The kill switch response procedure was not in the runbook — the SRE had to improvise.

### Where We Got Lucky
- The outage occurred during low-traffic hours (early morning UTC) — DAU impact was limited to ~120 users.
- The PgBackedCache happened to have warm entries for most affected users.

### Runbook Updates Required
| Runbook | Section | Update Required |
|---|---|---|
| `docs/runbooks/incident.md` | Section E (new) | Add "OpenAlex API Upstream Failure" response procedure with kill switch steps |
| `infra/alertmanager-alerts.yml` | New rule | Add `OpenAlexApiHighErrorRate` Prometheus alert rule |

### Lessons to Publish
> **Engineering Channel Post:** "During the June 3 OpenAlex upstream outage, we discovered that while our PgBackedCache provided graceful degradation for cached data, uncached requests still cascaded to 502 errors for 18 minutes before a manual kill switch was applied. **Action:** We are implementing automatic circuit breaking on external API clients and adding an upstream-specific Prometheus alert. Key learning: always add a circuit breaker to every external API dependency."

---

## 6. Post-Incident Review Meeting

| Field | Value |
|---|---|
| **Meeting Date & Time** | `2026-06-06T10:00:00Z` |
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
