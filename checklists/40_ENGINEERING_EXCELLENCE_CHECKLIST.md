# 40 ENGINEERING EXCELLENCE — Engineering Excellence Checklist

> **Purpose:** Validate code style guides, architectural integrity, and developer onboarding workflows.
> Copilot: Check that developer onboarding guides and environment setup scripts are up-to-date.
> A release is only approved when every section shows `[x]` on all items.

---

## How to use this file

- Mark each item `[x]` as you complete it.
- Run `grep -c '\[ \]' 40_ENGINEERING_EXCELLENCE_CHECKLIST.md` to count remaining open items — zero means ready.
- Copilot instructions at the top of each section describe what to verify automatically where possible.

---

## Pillar 1 — Onboarding Guides & Bootstrap scripts

> **Copilot:** Verify that the code satisfies the 'Onboarding Guides & Bootstrap scripts' constraints in the current PR diff.

- [ ] Developer environment bootstrap script validated on clean workspace setup.
- [ ] Local virtualenv setup scripts run without errors.

**Sign-off:** `[ ]` Onboarding Guides & Bootstrap scripts verified by _______________  Date: _______________

---

## Pillar 2 — Code Review Conventions

> **Copilot:** Verify that the code satisfies the 'Code Review Conventions' constraints in the current PR diff.

- [ ] Peer programming guidelines and code review conventions documented.
- [ ] Merge requirements enforce at least one peer approval code review before merge.

**Sign-off:** `[ ]` Code Review Conventions verified by _______________  Date: _______________

---

## Pillar 3 — Architecture Decision Records (ADRs)

> **Copilot:** Verify that the code satisfies the 'Architecture Decision Records (ADRs)' constraints in the current PR diff.

- [ ] Technical architecture modifications documented in ADR logs.
- [ ] Database schema modifications ADRs linked inside migrations code.

**Sign-off:** `[ ]` Architecture Decision Records (ADRs) verified by _______________  Date: _______________

---

## Pillar 4 — Developer Documentation integrity

> **Copilot:** Verify that the code satisfies the 'Developer Documentation integrity' constraints in the current PR diff.

- [ ] API reference guides generated and updated automatically.
- [ ] Readme guides detail core local debug commands clearly.

**Sign-off:** `[ ]` Developer Documentation integrity verified by _______________  Date: _______________

---

## Pillar 5 — Automation Scripts maintenance

> **Copilot:** Verify that the code satisfies the 'Automation Scripts maintenance' constraints in the current PR diff.

- [ ] Build and install scripts verified on target dev workstations.
- [ ] Database seed scripts configure realistic test data states.

**Sign-off:** `[ ]` Automation Scripts maintenance verified by _______________  Date: _______________

---

## Pillar 6 — Continuous Learning & Retrospectives

> **Copilot:** Verify that the code satisfies the 'Continuous Learning & Retrospectives' constraints in the current PR diff.

- [ ] Sprint retrospectives review engineering bottlenecks and code issues.

**Sign-off:** `[ ]` Continuous Learning & Retrospectives verified by _______________  Date: _______________

---

## Final Go / No-Go Gate

Run this command to count open items before release approval:

```bash
grep -c '\[ \]' 40_ENGINEERING_EXCELLENCE_CHECKLIST.md
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
