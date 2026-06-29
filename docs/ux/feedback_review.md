# SkoLab User Feedback & NPS Review Cadence

_Last updated: 2026-06-04_

## Feedback Collection Points

| Surface | Trigger | Channel |
|---|---|---|
| In-app "Help & Feedback" | User-initiated tap in Profile → Settings | Firestore `feedback` collection |
| NPS prompt | After session 5 (first time only, `nps_shown` flag guards repeat) | In-app bottom sheet → Firestore |
| Google Play reviews | Organic + in-app review API | Play Console |
| Firebase Crashlytics | Crash / ANR | Firebase Console |

---

## NPS Scoring Rubric

| Score | Category | Action |
|---|---|---|
| 9–10 | Promoter | Thank-you message; prompt to leave a Play Store review |
| 7–8 | Passive | No immediate action; recheck at 30-day mark |
| 0–6 | Detractor | Follow-up dialog with open text: "What can we improve?" |

---

## Review Schedule

| Cadence | Owner | Scope |
|---|---|---|
| **Weekly (Monday)** | Product Lead | Triage new Firestore feedback; flag critical bugs |
| **Bi-weekly (Sprint Review)** | Engineering | Aggregate NPS deltas; pick top 3 issues |
| **Monthly** | Leadership | NPS trend, cohort breakdown, D7/D30 retention |
| **Quarterly** | Full team | Deep-dive on churned users; competitor benchmarks |

---

## Feedback-to-Issue Pipeline

1. **Collect** — `SkoLabAnalytics.logFeedbackSubmitted()` fires and writes to Firestore `/feedback/{uid}/{timestamp}`.
2. **Tag** — A Cloud Function auto-labels each document with `sentiment: positive|neutral|negative` via the Natural Language API.
3. **Triage** — Product Lead reviews tagged documents weekly in the Firebase Console and opens GitHub issues for actionable items.
4. **Close the loop** — When a reported issue ships in a release, link the issue in the GitHub PR and notify the user via email if they opted in.

---

## NPS Implementation Reference

```kotlin
// In DiscoveryScreen / wherever the NPS bottom sheet is shown:
val sessionCount by userPrefs.sessionCount.collectAsState(0)
val npsShown by userPrefs.npsShown.collectAsState(false)

LaunchedEffect(sessionCount) {
    if (sessionCount >= 5 && !npsShown) {
        showNpsDialog = true
        userPrefs.setNpsShown(true)
    }
}
```

---

## Privacy Notice

- NPS responses are linked to a hashed user ID, never to PII.
- Open-text responses are stored in Firestore with Firestore Security Rules restricting read access to service accounts only.
- Users can request deletion of their feedback data via the in-app "Delete Account (GDPR Purge)" flow.
