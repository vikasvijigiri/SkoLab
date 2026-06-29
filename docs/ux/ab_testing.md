# SkoLab A/B Testing Strategy

_Last updated: 2026-06-04_

## Overview

SkoLab uses Firebase A/B Testing (backed by Remote Config + Firebase Analytics) as its experimentation platform. All product variants that affect the user journey should be registered here before shipping.

---

## Variant Registration Template

| Field | Description |
|---|---|
| **Test ID** | `snake_case` identifier, max 40 chars (matches `ab_test_assigned` event param) |
| **Hypothesis** | One sentence explaining the expected outcome |
| **Primary Metric** | The single metric this test optimises for |
| **Secondary Metrics** | Up to 3 guard-rail metrics |
| **Traffic Split** | e.g., `50% Control / 50% Treatment` |
| **Min Sample Size** | Calculated at 80% power, α=0.05 |
| **Start Date** | ISO-8601 |
| **Stop Criteria** | Significance threshold or fixed run duration |

---

## Active / Planned Tests

### 1. `feed_card_style_v2`
- **Hypothesis:** Showing the abstract excerpt on feed cards increases paper-detail open rate by ≥10%.
- **Primary metric:** `paper_detail_opened` rate per session.
- **Secondary metrics:** session duration, papers saved, crash-free sessions.
- **Split:** 50/50 (Control = current card, Treatment = card + abstract excerpt).
- **Min sample size:** 1 200 unique users per variant.
- **Implementation:** `SkoLabAnalytics.getOrAssignVariant("feed_card_style_v2", listOf("control", "treatment"))` — returned variant drives a `when` branch inside `FeedCard.kt`.

### 2. `confetti_delight_save`
- **Hypothesis:** Canvas confetti burst on paper-save increases 7-day retention.
- **Primary metric:** D7 retention rate.
- **Secondary metrics:** saved paper count, NPS score delta.
- **Split:** 50/50 (Control = silent save, Treatment = `ConfettiCelebration`).

### 3. `nps_session_trigger`
- **Hypothesis:** Prompting NPS after session 5 (vs. never) gives actionable feedback without disrupting new-user flow.
- **Primary metric:** NPS response rate.
- **Secondary metrics:** D1 drop-off, search events in same session.

---

## How to Register a New Test

1. Add a row to the **Active / Planned Tests** table above.
2. Create the Remote Config parameter in Firebase Console with the same Test ID.
3. Call `SkoLabAnalytics.getOrAssignVariant(testId, variants)` at the decision point.
4. Wrap the variant-dependent code in a `when(variant)` block.
5. After the run, document results in the **Completed Tests** section below.

---

## Completed Tests

_None yet._

---

## Anti-Patterns to Avoid

- **Don't re-use test IDs** — always create a new ID for each iteration.
- **Don't ship tests longer than 30 days** — stale tests accumulate technical debt.
- **Don't optimise for single metrics** — always track ≥1 guard-rail metric.
- **Don't A/B test on less than 200 users per variant** — underpowered results are misleading.
