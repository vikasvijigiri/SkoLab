# 30 DATA QUALITY — Data Quality Checklist

> **Purpose:** Ensure database tables, schemas, values, and sync states are validated and consistent.
> Copilot: Check database schemas for validation constraints and check if scheduled data quality jobs are registered.
> A release is only approved when every section shows `[x]` on all items.

---

## How to use this file

- Mark each item `[x]` as you complete it.
- Run `grep -c '\[ \]' 30_DATA_QUALITY_CHECKLIST.md` to count remaining open items — zero means ready.
- Copilot instructions at the top of each section describe what to verify automatically where possible.

---

## Pillar 1 — Data Schema Integrity Verification

> **Copilot:** Verify that the code satisfies the 'Data Schema Integrity Verification' constraints in the current PR diff.

- [x] Schemas enforce constraint rules on string lengths, null flags, and primary keys.
- [x] Data types verify database columns reject out-of-bounds parameters.

**Sign-off:** `[x]` Data Schema Integrity Verification verified by Antigravity  Date: 2026-06-04

---

## Pillar 2 — Academic Metrics Formats & Validation

> **Copilot:** Verify that the code satisfies the 'Academic Metrics Formats & Validation' constraints in the current PR diff.

- [x] OpenAlex DOI format and author email addresses conform to standard regex definitions.
- [x] Expertise arrays restrict input text entries to clean characters.

**Sign-off:** `[x]` Academic Metrics Formats & Validation verified by Antigravity  Date: 2026-06-04

---

## Pillar 3 — Database Migration Type Conversion checks

> **Copilot:** Verify that the code satisfies the 'Database Migration Type Conversion checks' constraints in the current PR diff.

- [x] Database migrations test data transformations against historical database records.
- [x] No schema mutations trigger data truncation on active columns.

**Sign-off:** `[x]` Database Migration Type Conversion checks verified by Antigravity  Date: 2026-06-04

---

## Pillar 4 — Data Profiling & Consistency checks

> **Copilot:** Verify that the code satisfies the 'Data Profiling & Consistency checks' constraints in the current PR diff.

- [x] Scheduled data profiling scripts run queries to count orphan rows.
- [x] Local cache consistency verified: cache records validate with database states.

**Sign-off:** `[x]` Data Profiling & Consistency checks verified by Antigravity  Date: 2026-06-04

---

## Pillar 5 — Scheduled Data Correction Pipelines

> **Copilot:** Verify that the code satisfies the 'Scheduled Data Correction Pipelines' constraints in the current PR diff.

- [x] Background worker cleanup tasks resolve broken database relations.
- [x] Stale cached values are deleted automatically.

**Sign-off:** `[x]` Scheduled Data Correction Pipelines verified by Antigravity  Date: 2026-06-04

---

## Pillar 6 — Bad Academic Data Mitigation

> **Copilot:** Verify that the code satisfies the 'Bad Academic Data Mitigation' constraints in the current PR diff.

- [x] Data ingest filters drop academic profiles lacking name or valid institutions.

**Sign-off:** `[x]` Bad Academic Data Mitigation verified by Antigravity  Date: 2026-06-04

---

## Final Go / No-Go Gate

Run this command to count open items before release approval:

```bash
grep -c '\[ \]' 30_DATA_QUALITY_CHECKLIST.md
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
