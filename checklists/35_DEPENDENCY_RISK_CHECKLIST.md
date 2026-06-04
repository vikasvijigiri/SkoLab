# 35 DEPENDENCY RISK — Dependency Risk Checklist

> **Purpose:** Monitor libraries licenses, deprecated APIs, packages health, and security updates.
> Copilot: Verify that there are no copyleft licenses (e.g. GPLv3) in the third-party dependency list.
> A release is only approved when every section shows `[x]` on all items.

---

## How to use this file

- Mark each item `[x]` as you complete it.
- Run `grep -c '\[ \]' 35_DEPENDENCY_RISK_CHECKLIST.md` to count remaining open items — zero means ready.
- Copilot instructions at the top of each section describe what to verify automatically where possible.

---

## Pillar 1 — Dependency License Audits (permissive)

> **Copilot:** Verify that the code satisfies the 'Dependency License Audits (permissive)' constraints in the current PR diff.

- [ ] All third-party libraries comply with permissive corporate licensing policies (Apache/MIT/BSD).
- [ ] License verification tool runs in CI to flag copyleft dependencies.

**Sign-off:** `[ ]` Dependency License Audits (permissive) verified by _______________  Date: _______________

---

## Pillar 2 — Version Pinning & Dependency Lock

> **Copilot:** Verify that the code satisfies the 'Version Pinning & Dependency Lock' constraints in the current PR diff.

- [ ] Package files pin exact library versions (no wildcard upgrades allowed).
- [ ] Lock files (e.g. poetry.lock, Cargo.lock) checked into the git repository.

**Sign-off:** `[ ]` Version Pinning & Dependency Lock verified by _______________  Date: _______________

---

## Pillar 3 — Vulnerability & CVE Scanning

> **Copilot:** Verify that the code satisfies the 'Vulnerability & CVE Scanning' constraints in the current PR diff.

- [ ] Dependency scanner scans package files daily to flag high-severity CVEs.
- [ ] Security patches applied immediately on high-priority vulnerability alerts.

**Sign-off:** `[ ]` Vulnerability & CVE Scanning verified by _______________  Date: _______________

---

## Pillar 4 — Deprecated APIs & Outdated Packages

> **Copilot:** Verify that the code satisfies the 'Deprecated APIs & Outdated Packages' constraints in the current PR diff.

- [ ] Compiler checks flag deprecated API calls inside client/server code.
- [ ] Outdated packages are reviewed monthly and scheduled for updates.

**Sign-off:** `[ ]` Deprecated APIs & Outdated Packages verified by _______________  Date: _______________

---

## Pillar 5 — Transitive Dependency audits

> **Copilot:** Verify that the code satisfies the 'Transitive Dependency audits' constraints in the current PR diff.

- [ ] Transitive dependencies audited to verify they do not introduce license risks.
- [ ] Gradle dependency graph parsed to resolve package version conflicts.

**Sign-off:** `[ ]` Transitive Dependency audits verified by _______________  Date: _______________

---

## Pillar 6 — Alternative Package evaluations

> **Copilot:** Verify that the code satisfies the 'Alternative Package evaluations' constraints in the current PR diff.

- [ ] New third-party dependencies undergo risk evaluation before inclusion in source code.

**Sign-off:** `[ ]` Alternative Package evaluations verified by _______________  Date: _______________

---

## Final Go / No-Go Gate

Run this command to count open items before release approval:

```bash
grep -c '\[ \]' 35_DEPENDENCY_RISK_CHECKLIST.md
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
