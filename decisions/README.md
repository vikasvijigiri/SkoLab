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
