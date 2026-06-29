# Post-Mortem Report Template

> **Usage:** Copy this file to `docs/postmortems/inc-YYYY-NNN_<short-title>.md` and fill in all sections.
> A postmortem is only considered complete when ALL sections are filled. Partial postmortems are not accepted.
> Reference: [Checklist 28](../../checklists/28_POSTMORTEM_CHECKLIST.md)

---

## Incident Metadata

| Field | Value |
|---|---|
| **Incident ID** | `inc-YYYY-NNN` |
| **Title** | Brief, descriptive title |
| **Severity** | P0 / P1 / P2 / P3 |
| **Status** | open / resolved |
| **Detected At (UTC)** | `YYYY-MM-DDTHH:MM:SSZ` |
| **Resolved At (UTC)** | `YYYY-MM-DDTHH:MM:SSZ` |
| **Total Duration** | `X hours Y minutes` |
| **Incident Commander** | Name |
| **Primary On-Call SRE** | Name |
| **Postmortem Author** | Name |
| **Postmortem Date** | `YYYY-MM-DD` |
| **Review Meeting Scheduled** | `YYYY-MM-DD HH:MM UTC` (must be within 5 days of resolution) |

---

## 1. Incident Timeline

> **Requirement (Pillar 1):** Every significant event must be logged with a UTC timestamp. The timeline must trace from the initial alert trigger to the final fix verification.

| Time (UTC) | Actor | Event |
|---|---|---|
| `HH:MM:SS` | System/Prometheus | Alert triggered: `<alert_name>` |
| `HH:MM:SS` | On-Call SRE | Alert acknowledged in PagerDuty |
| `HH:MM:SS` | On-Call SRE | Initial investigation started |
| `HH:MM:SS` | SRE / Dev | Root cause identified |
| `HH:MM:SS` | SRE / Dev | Mitigation applied |
| `HH:MM:SS` | System | Service restored |
| `HH:MM:SS` | SRE | Fix verification complete — incident resolved |

---

## 2. Root Cause Analysis (5 Whys)

> **Requirement (Pillar 2):** Apply the Five Whys method to trace the fault to its root cause. Each "Why" must have a concrete, verifiable answer.

**Symptom:** *Describe the user-visible or system-visible symptom.*

| # | Why? | Answer |
|---|---|---|
| **Why 1** | Why did users experience `<symptom>`? | `<answer>` |
| **Why 2** | Why did `<answer 1>` happen? | `<answer>` |
| **Why 3** | Why did `<answer 2>` happen? | `<answer>` |
| **Why 4** | Why did `<answer 3>` happen? | `<answer>` |
| **Why 5** | Why did `<answer 4>` happen? | `<root cause>` |

### Root Cause Summary
> One-sentence summary of the systemic root cause.

### Contributing Factors
List contributing factors that worsened the incident or delayed resolution:
- [ ] Missing/delayed alerting (e.g. alert fired late or not at all)
- [ ] Documentation gap (e.g. runbook missing for this failure mode)
- [ ] Insufficient monitoring coverage
- [ ] Human error (e.g. incorrect configuration, missed review)
- [ ] External dependency failure

---

## 3. Financial & Technical Impact Analysis

> **Requirement (Pillar 3):** Quantify the impact of the incident in both technical and business terms.

### Technical Impact

| Metric | Value |
|---|---|
| **Total Outage Duration** | `X hours Y minutes` |
| **Estimated DAU Impacted** | `~N users` |
| **API Request Errors** | `~N requests affected` |
| **Error Rate at Peak** | `N%` |
| **Services Affected** | List affected endpoints/services |
| **Data Integrity Impact** | None / Partial / Full |

### Business / Financial Impact

| Metric | Value |
|---|---|
| **LLM API Credits Lost** | `$N.NN` estimated |
| **SRE Engineer Hours Spent** | `N hours` |
| **Customer-Visible Degradation** | Yes / No |
| **SLA Breach** | Yes / No |
| **Credits/Refunds Issued** | `$N.NN` or None |

---

## 4. Corrective Action Items

> **Requirement (Pillar 4):** All action items must be assigned to an owner with a target resolution date. Each item must undergo peer testing before release.

| ID | Action | Owner | Target Date | Status | Test Gate |
|---|---|---|---|---|---|
| CA-001 | `<Describe corrective action>` | `Name` | `YYYY-MM-DD` | Open | Peer review required |
| CA-002 | `<Describe corrective action>` | `Name` | `YYYY-MM-DD` | Open | Peer review required |

### Testing Gate Requirement
Each corrective action involving a code change must:
1. Pass unit/integration tests before merge.
2. Receive peer code review (minimum 1 approver).
3. Be deployed to staging before production.
4. Be verified via a health probe or manual test in production.

---

## 5. Lessons Learned

> **Requirement (Pillar 5):** Capture what the team learned and what systemic improvements follow. These lessons must be published to the engineering channel and incorporated into runbooks.

### What Went Well
- (List things that worked as expected during the incident response)

### What Went Wrong
- (List failures in detection, response, tooling, communication, or process)

### Where We Got Lucky
- (List near-misses where luck prevented a worse outcome)

### Runbook Updates Required
| Runbook | Section | Update Required |
|---|---|---|
| `docs/runbooks/incident.md` | Section X | Describe the update |

### Lessons to Publish
> Summarize the key engineering lesson in 2-3 sentences for the `#engineering` channel and the [Lessons Learned Knowledge Base](../lessons_learned.md).

---

## 6. Post-Incident Review Meeting

> **Requirement (Pillar 6):** A review meeting must be scheduled within 5 days of incident resolution.

| Field | Value |
|---|---|
| **Meeting Date & Time** | `YYYY-MM-DD HH:MM UTC` |
| **Facilitator** | Name |
| **Attendees** | SRE, Backend Lead, Engineering Lead |
| **Meeting Notes Link** | Link to notes doc / Confluence / Notion page |
| **Action Items Reviewed** | Yes / No |

---

## Sign-off

| Role | Name | Signed | Date |
|---|---|---|---|
| Incident Commander | | `[ ]` | |
| Engineering Lead | | `[ ]` | |
| Primary SRE | | `[ ]` | |
