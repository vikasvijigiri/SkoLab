# 39 MOBILE RELEASE — Mobile Release Checklist

> **Purpose:** Verify Android App Bundle (AAB), signing certificates, screenshots, Play Console setups, and rollout staging.
> Copilot: Verify the app version code is incremented in build.gradle compared to the last production tag.
> A release is only approved when every section shows `[x]` on all items.

---

## How to use this file

- Mark each item `[x]` as you complete it.
- Run `grep -c '\[ \]' 39_MOBILE_RELEASE_CHECKLIST.md` to count remaining open items — zero means ready.
- Copilot instructions at the top of each section describe what to verify automatically where possible.

---

## Pillar 1 — Play Store Release Signing

> **Copilot:** Verify that the code satisfies the 'Play Store Release Signing' constraints in the current PR diff.

- [ ] Release AAB is signed using production release keys.
- [ ] Signing keys stored securely in Play Console vaults.

**Sign-off:** `[ ]` Play Store Release Signing verified by _______________  Date: _______________

---

## Pillar 2 — Obfuscation & R8 Mapping

> **Copilot:** Verify that the code satisfies the 'Obfuscation & R8 Mapping' constraints in the current PR diff.

- [ ] Proguard/R8 code obfuscation enabled on release builds.
- [ ] R8 mapping files uploaded to console to enable crash log de-obfuscation.

**Sign-off:** `[ ]` Obfuscation & R8 Mapping verified by _______________  Date: _______________

---

## Pillar 3 — Staged Rollout Management

> **Copilot:** Verify that the code satisfies the 'Staged Rollout Management' constraints in the current PR diff.

- [ ] Staged rollouts configured: initial deploy to 5% of active users.
- [ ] Rollout percentages increase step-wise after tracking stability for 24h.

**Sign-off:** `[ ]` Staged Rollout Management verified by _______________  Date: _______________

---

## Pillar 4 — App Store Listing Assets

> **Copilot:** Verify that the code satisfies the 'App Store Listing Assets' constraints in the current PR diff.

- [ ] Store listings, screenshots, and promo copy approved in Play Store.
- [ ] What's New copy lists new feature updates clearly.

**Sign-off:** `[ ]` App Store Listing Assets verified by _______________  Date: _______________

---

## Pillar 5 — Client Cache Invalidation

> **Copilot:** Verify that the code satisfies the 'Client Cache Invalidation' constraints in the current PR diff.

- [ ] Database migrations on mobile clear older SQLite caches to prevent crashes.
- [ ] Local app preferences reset safely if structure updates fail.

**Sign-off:** `[ ]` Client Cache Invalidation verified by _______________  Date: _______________

---

## Pillar 6 — Release Rollback & Gating

> **Copilot:** Verify that the code satisfies the 'Release Rollback & Gating' constraints in the current PR diff.

- [ ] Play Store rollout halts immediately on crash rate spikes (>1%).

**Sign-off:** `[ ]` Release Rollback & Gating verified by _______________  Date: _______________

---

## Final Go / No-Go Gate

Run this command to count open items before release approval:

```bash
grep -c '\[ \]' 39_MOBILE_RELEASE_CHECKLIST.md
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
