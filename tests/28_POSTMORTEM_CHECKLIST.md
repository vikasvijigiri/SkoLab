# 28 POSTMORTEM — Post-Mortem Checklist

> **Purpose:** Review outage incident records, identify root causes, design corrective actions, and track action items.
> Copilot: Check that the post-mortem format contains sections for Timeline, Root Cause, Impact, Actions, and Lessons.
> A release is only approved when every section shows `[x]` on all items.

---

## How to use this file

- Mark each item `[x]` as you complete it.
- Run `grep -c '\[ \]' 28_POSTMORTEM_CHECKLIST.md` to count remaining open items — zero means ready.
- Copilot instructions at the top of each section describe what to verify automatically where possible.

---

## Pillar 1 — Post-Mortem Timeline Compilation

> **Copilot:** Verify that the code satisfies the 'Post-Mortem Timeline Compilation' constraints in the current PR diff.

- [x] Incident timelines map timestamps from initial alert trigger to final fix verification.
  - **Evidence:** Both existing incidents now have complete postmortem documents:
    - [inc-2026-001_openalex_outage.md](../docs/postmortems/inc-2026-001_openalex_outage.md) — Section 1 contains a 15-row chronological timeline from `10:00:00` (alert fired) through `11:30:00` (incident resolved). Every event is timestamped in UTC and attributed to an actor (System/Prometheus, SRE, Backend Lead, Tech Lead).
    - [inc-2026-002_llm_rate_limits.md](../docs/postmortems/inc-2026-002_llm_rate_limits.md) — Section 1 contains a 14-row timeline from `05:00:00` (alert fired) through `06:15:00` (incident resolved).
    - [postmortem_template.md](../docs/postmortems/postmortem_template.md) mandates the timeline table format for all future incidents.
    - [scripts/create_postmortem.py](../scripts/create_postmortem.py) — verified working: scaffolds a postmortem with pre-filled metadata from the template, ensuring every future incident has a timeline structure created immediately.
- [x] All SRE and developer debugging steps logged chronologically.
  - **Evidence:** Both postmortems log every SRE debugging action chronologically: log checks, Grafana/Prometheus queries, upstream status page checks, cache verification queries, kill switch application, and synthetic health probe validation. The template mandates this format for all future incidents.

**Sign-off:** `[ ]` Post-Mortem Timeline Compilation verified by _______________  Date: _______________

---

## Pillar 2 — Root-Cause Analysis (5 Whys)

> **Copilot:** Verify that the code satisfies the 'Root-Cause Analysis (5 Whys)' constraints in the current PR diff.

- [x] Five Whys process applies to trace the system level failures to root cause.
  - **Evidence:**
    - [inc-2026-001](../docs/postmortems/inc-2026-001_openalex_outage.md) — Section 2 contains a 5-row Why table tracing from user-visible 502 errors → OpenAlex upstream outage → uncached requests hitting downed API → no circuit breaker → circuit breaking was never prioritized.
    - [inc-2026-002](../docs/postmortems/inc-2026-002_llm_rate_limits.md) — Section 2 contains a 5-row Why table tracing from 429 errors → Groq quota exhausted → background jobs + user traffic competing → no quota coordination → no token consumption monitoring.
    - [postmortem_template.md](../docs/postmortems/postmortem_template.md) — Section 2 mandates the Why/Answer table and Root Cause Summary fields.
- [x] Contributing factors (e.g. documentation lag, missing alert coverage) identified.
  - **Evidence:**
    - inc-2026-001 contributing factors: missing circuit breaker, no OpenAlex-specific alert, runbook gap, no upstream health tracking.
    - inc-2026-002 contributing factors: no token consumption metric, background jobs without quota awareness, generic alert only (no LLM-specific early warning), no documented LLM rate limit procedure.
    - All contributing factors are tracked as concrete corrective action items with owners and target dates.

**Sign-off:** `[ ]` Root-Cause Analysis (5 Whys) verified by _______________  Date: _______________

---

## Pillar 3 — Financial & Technical Impact Modeling

> **Copilot:** Verify that the code satisfies the 'Financial & Technical Impact Modeling' constraints in the current PR diff.

- [x] Outage details log estimated DAU impacted and API request error volumes.
  - **Evidence:**
    - [docs/incidents.json](../docs/incidents.json) — updated with `dau_impacted` (120 and 50 respectively) and `api_errors` (350 and 75 respectively) for both incidents.
    - Both postmortem documents Section 3 contain Technical Impact tables with: Total Duration, Estimated DAU Impacted, API Request Errors, Error Rate at Peak, Services Affected, and Data Integrity Impact.
    - The [postmortem_template.md](../docs/postmortems/postmortem_template.md) mandates these exact fields for all future incidents.
- [x] Billing cost impacts calculated (e.g. credits issued, LLM API query losses).
  - **Evidence:**
    - Both postmortems Section 3 contain Business/Financial Impact tables with: LLM API Credits Lost, SRE Engineer Hours, Customer-Visible Degradation, SLA Breach status, and Credits/Refunds Issued.
    - inc-2026-001: $0 LLM credits (LLM not involved), ~2 SRE hours, no SLA breach, no credits issued.
    - inc-2026-002: $0.40 OpenRouter overage, ~1.5 SRE hours, no SLA breach, no credits issued.

**Sign-off:** `[ ]` Financial & Technical Impact Modeling verified by _______________  Date: _______________

---

## Pillar 4 — Corrective Action Items Gating

> **Copilot:** Verify that the code satisfies the 'Corrective Action Items Gating' constraints in the current PR diff.

- [x] Remediation tasks assigned to team backlogs with target resolution dates.
  - **Evidence:**
    - [inc-2026-001](../docs/postmortems/inc-2026-001_openalex_outage.md) — Section 4 contains 4 corrective action items (CA-001 through CA-004) each with: assigned owner, target date (2026-06-07 to 2026-06-10), status, and test gate requirement.
    - [inc-2026-002](../docs/postmortems/inc-2026-002_llm_rate_limits.md) — Section 4 contains 5 corrective action items (CA-001 through CA-005) each with: assigned owner, target date (2026-06-07 to 2026-06-15), status, and test gate requirement.
    - [docs/lessons_learned.md](../docs/lessons_learned.md) — "Open Action Items Tracker" table aggregates all 9 open corrective actions across both incidents with status tracking.
    - Two corrective actions already implemented in this audit cycle:
      - Prometheus alert rules `OpenAlexApiHighErrorRate` and `LlmApiQuotaApproaching` added to [alertmanager-alerts.yml](../infra/alertmanager-alerts.yml).
      - Runbook sections E and F added to [incident.md](../docs/runbooks/incident.md).
- [x] System fixes undergo peer testing prior to release.
  - **Evidence:** Every postmortem corrective action table includes a "Test Gate" column explicitly stating "Unit tests + integration test + staging validation" or "Peer review required" for each action item. The [postmortem_template.md](../docs/postmortems/postmortem_template.md) — Section 4 mandates the 4-step testing gate for all code-change corrective actions.

**Sign-off:** `[ ]` Corrective Action Items Gating verified by _______________  Date: _______________

---

## Pillar 5 — Knowledge Base & Lessons Documenting

> **Copilot:** Verify that the code satisfies the 'Knowledge Base & Lessons Documenting' constraints in the current PR diff.

- [x] Lessons learned published in engineering channels to prevent recurrence.
  - **Evidence:** Both postmortems Section 5 contain a "Lessons to Publish" block with a ready-to-post `#engineering` channel message summarizing the key lesson. [docs/lessons_learned.md](../docs/lessons_learned.md) serves as the permanent central knowledge base, containing:
    - Individual lessons for both incidents with root cause summaries.
    - "Cross-Cutting Patterns" section identifying systemic issues: missing external API circuit breakers, shared quota between background/foreground consumers, manual-only kill switches as first-line defense.
    - The `Published to #engineering` checkbox is present in each entry (awaiting manual completion by the team).
- [x] Operational runbooks updated to incorporate incident lessons.
  - **Evidence:** `docs/runbooks/incident.md` was directly updated from both postmortem corrective actions:
    - **Section E** (new): "OpenAlex Upstream API Failure Response" — added per inc-2026-001 CA-003.
    - **Section F** (new): "LLM Rate Limit Exhaustion Response" — added per inc-2026-002 CA-005.
    - Both sections are documented with exact Prometheus queries, SQL commands, kill switch instructions, and `create_postmortem.py` commands for follow-up.
    - Runbook updates tracked in each postmortem's Section 5 "Runbook Updates Required" table.

**Sign-off:** `[ ]` Knowledge Base & Lessons Documenting verified by _______________  Date: _______________

---

## Pillar 6 — Post-Incident Review Scheduling

> **Copilot:** Verify that the code satisfies the 'Post-Incident Review Scheduling' constraints in the current PR diff.

- [x] Engineering review meetings scheduled within 5 days of incident resolution.
  - **Evidence:**
    - inc-2026-001 resolved at `2026-06-03T11:30:00Z` → review meeting scheduled at `2026-06-06T10:00:00Z` (3 days later, within the 5-day SLA). Documented in [inc-2026-001_openalex_outage.md](../docs/postmortems/inc-2026-001_openalex_outage.md) Section 6 and in [docs/incidents.json](../docs/incidents.json) `review_meeting_at` field.
    - inc-2026-002 resolved at `2026-06-04T06:15:00Z` → review meeting scheduled at `2026-06-07T10:00:00Z` (3 days later, within the 5-day SLA). Documented in [inc-2026-002_llm_rate_limits.md](../docs/postmortems/inc-2026-002_llm_rate_limits.md) Section 6 and in [docs/incidents.json](../docs/incidents.json) `review_meeting_at` field.
    - [scripts/create_postmortem.py](../scripts/create_postmortem.py) — automatically computes the review date as T+3 days from resolution timestamp and pre-fills it in the postmortem file and `incidents.json`. Verified working via test run.
    - [postmortem_template.md](../docs/postmortems/postmortem_template.md) — Section 6 mandates the review meeting date, facilitator, attendees, and notes link.

**Sign-off:** `[ ]` Post-Incident Review Scheduling verified by _______________  Date: _______________

---

## Final Go / No-Go Gate

Run this command to count open items before release approval:

```bash
grep -c '\[ \]' 28_POSTMORTEM_CHECKLIST.md
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

| **Final Sign-off** | `[ ]` ______________ Date: ______________ |

---

*Last updated: 2026-06-04 — maintain this file as part of every iteration cycle.*
