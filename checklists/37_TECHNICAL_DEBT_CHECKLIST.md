# 37 TECHNICAL DEBT — Technical Debt Checklist

> **Purpose:** Log deprecated paths, coordinate code refactorings, prioritize bug resolutions, and optimize tests.
> Copilot: Scan code files for `TODO` comments or deprecated method annotations.
> A release is only approved when every section shows `[x]` on all items.

---

## How to use this file

- Mark each item `[x]` as you complete it.
- Run `grep -c '\[ \]' 37_TECHNICAL_DEBT_CHECKLIST.md` to count remaining open items — zero means ready.
- Copilot instructions at the top of each section describe what to verify automatically where possible.

---

## Pillar 1 — Backlog & Tech Debt Gating

> **Copilot:** Verify that the code satisfies the 'Backlog & Tech Debt Gating' constraints in the current PR diff.

- [ ] Technical debt tickets are prioritized and scheduled for sprint cycles.
- [ ] Tech debt ratio monitored: refactoring cards account for 20% of sprint capacity.

**Sign-off:** `[ ]` Backlog & Tech Debt Gating verified by _______________  Date: _______________

---

## Pillar 2 — Code Refactoring Conventions

> **Copilot:** Verify that the code satisfies the 'Code Refactoring Conventions' constraints in the current PR diff.

- [ ] Inline logic refactored into clean modules using SOLID design guidelines.
- [ ] Comments explain design context rather than detailing simple logic steps.

**Sign-off:** `[ ]` Code Refactoring Conventions verified by _______________  Date: _______________

---

## Pillar 3 — Deprecated Code Removals

> **Copilot:** Verify that the code satisfies the 'Deprecated Code Removals' constraints in the current PR diff.

- [ ] Deprecated functions annotated with removal targets and deleted.
- [ ] Older API routes removed once all active users upgrade clients.

**Sign-off:** `[ ]` Deprecated Code Removals verified by _______________  Date: _______________

---

## Pillar 4 — CI/CD Build Time Optimizations

> **Copilot:** Verify that the code satisfies the 'CI/CD Build Time Optimizations' constraints in the current PR diff.

- [ ] CI pipeline builds cache dependencies to reduce test runs times.
- [ ] Inefficient parallel build configurations tuned to prevent resource starvation.

**Sign-off:** `[ ]` CI/CD Build Time Optimizations verified by _______________  Date: _______________

---

## Pillar 5 — Test Execution optimizations

> **Copilot:** Verify that the code satisfies the 'Test Execution optimizations' constraints in the current PR diff.

- [ ] Slow-running integration tests optimized using database transaction rollbacks.
- [ ] Flaky tests refactored or quarantined to prevent CI build blockings.

**Sign-off:** `[ ]` Test Execution optimizations verified by _______________  Date: _______________

---

## Pillar 6 — Architecture Decision Records (ADRs)

> **Copilot:** Verify that the code satisfies the 'Architecture Decision Records (ADRs)' constraints in the current PR diff.

- [ ] Technical architecture modifications documented in ADR logs.

**Sign-off:** `[ ]` Architecture Decision Records (ADRs) verified by _______________  Date: _______________

---

## Final Go / No-Go Gate

Run this command to count open items before release approval:

```bash
grep -c '\[ \]' 37_TECHNICAL_DEBT_CHECKLIST.md
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
