# Decisions

One file per real architectural or product-scope decision — lightweight ADR
(Architecture Decision Record) style, extended to cover scope/product calls,
not just technical architecture.

**Only write one when:**
- The choice isn't already fully spelled out elsewhere (code, `AGENTS.md`,
  `PLAN.md`), *and*
- It could plausibly be revisited or done differently later — i.e. a future
  reader would benefit from knowing the alternatives and the reasoning, not
  just the outcome.

Don't write one for a bug fix, a routine dependency bump, or anything that's
just "the obviously correct implementation of what was already decided."

## Format

Each file: `NNNN-short-title.md`, numbered sequentially, never renumbered or
reordered.

```markdown
# NNNN. Title

**Date:** YYYY-MM-DD
**Status:** Proposed | Accepted | Superseded by NNNN

## Context
What situation forced this decision? What was actually observed/measured?

## Decision
What was decided, stated plainly.

## Alternatives considered
What else was on the table, and why it lost.

## Consequences
What this makes easier, what it makes harder, what it leaves unresolved.
```

**Append-only.** Never edit a decision file after the fact to reflect new
information — if a decision is reversed or outdated, add a new file and mark
the old one's Status as `Superseded by NNNN`.

## Index

| # | Title |
|---|---|
| [0001](0001-two-tier-caching.md) | Two-tier caching: Postgres + Firestore, not one store |
| [0002](0002-go-gateway-in-front-of-python.md) | Go gateway in front of the Python backend |
| [0003](0003-self-hosted-embeddings.md) | Self-hosted embeddings over an API provider or TF-IDF |
| [0004](0004-firebase-for-colab-profile.md) | Firebase/Firestore direct-write for CoLab and Profile, no REST layer |
| [0005](0005-similar-researchers-via-authorship.md) | Similar-researchers channel via authorship, not author-name search |
| [0006](0006-decoupled-ranking-vs-display-score.md) | Decouple ranking score (pool-relative) from displayed match % (absolute) |
| [0007](0007-retire-dormant-unified-recommendations.md) | Retire the dormant unified-recommendations endpoint (keep peer/invite/check-registered) |
| [0008](0008-recommendation-peers-to-go-gateway.md) | `/recommendations/peers*` move to the Go gateway |
| [0009](0009-phase1-authors-assessment.md) | Phase 1 (`authors.py` → Go) assessment: nothing moves yet |
| [0010](0010-llm-python-rest-go-boundary.md) | The bright line: LLM/embedding work is Python, everything else is Go |
| [0018](0018-firestore-security-rules-over-rest.md) | Firestore security rules, not a REST layer, close the CoLab/Profile access-control gap |
| [0019](0019-colab-workspace-consolidated-writing.md) | CoLab Workspace: Chat/Equations fold into the Documents tab's dock, Share stays a modal, journal-template picker added |
| [0020](0020-global-topbar-solid-brand-blue.md) | Global top bar: compact 48px solid brand-blue bar, applied to every screen |
| [0021](0021-discovery-horizon-fused-highlights.md) | Discovery + Horizon: fused researcher Highlights, Track/Compare, TL;DR-first Papers mode |
| [0022](0022-signals-unified-alerts.md) | Signals: unified citation/tracked-people/topic/CoLab alerts, extending the real `NotificationsBell` |
| [0023](0023-profile-redesign-and-cv-export.md) | Profile redesign (closes the original CoLab+Profile scope) + CV export/share, click-first |
| [0024](0024-route-verification-home-paper-settings.md) | Route-level verification: Home feed, Paper detail, and Settings redesigned to close three previously-untouched routes |
| [0025](0025-backend-skill-pack-wshobson-agents.md) | Add a scoped backend slice of `wshobson/agents` alongside (not replacing) `addyosmani/agent-skills` |
