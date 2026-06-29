# SkoLab Analytics Instrumentation Schema (v2)

> **Implementation file:** [`SkoLabAnalytics.kt`](../android-app/app/src/main/java/com.company.skolab/analytics/SkoLabAnalytics.kt)  
> **Checklist:** [`29_ANALYTICS_INSTRUMENTATION_CHECKLIST.md`](../checklists/29_ANALYTICS_INSTRUMENTATION_CHECKLIST.md)  
> **Last updated:** 2026-06-04

---

## Analytics Stack

| Tool | Purpose | SDK |
|---|---|---|
| **Firebase Analytics** | Core product event tracking, funnels, retention | `firebase-analytics` (via Firebase BoM 33.9.0) |
| **Firebase Crashlytics** | Crash reporting + analytics event mirroring | `firebase-crashlytics` |
| **UserActivityTracker** | Local behavioral event buffer (offline-first, PII-free) | Custom (DataStore + JSON) |

No Amplitude or Mixpanel. All analytics flow through Firebase Analytics.

---

## Consent & Collection Control (Pillar 6)

| Condition | Firebase Collection State |
|---|---|
| App launch (before consent) | `setAnalyticsCollectionEnabled(false)` — **no events sent** |
| After user grants consent | `setAnalyticsCollectionEnabled(true)` |
| After user revokes consent | `setAnalyticsCollectionEnabled(false)` |
| User signs out | `setUserId(null)` — identity cleared |

Consent state is read from `UserPreferences.isConsentGiven` (DataStore) and wired into `SkoLabAnalytics.setConsent()` via a `LaunchedEffect` in `MainActivity.kt`.

---

## User Identity (Pillar 1)

`SkoLabAnalytics.identify(uid)` is called at every login success path:
- Google Sign-In success → `authManager.currentUser?.uid`
- Email Sign-In success → `authManager.currentUser?.uid`
- Email Registration success → `authManager.currentUser?.uid`

`SkoLabAnalytics.clearIdentity()` is called before every sign-out (via `ProfileScreen.kt`).

> **Note:** `session_id` is automatically tracked by the Firebase SDK per session. It is visible in the Firebase Console and in BigQuery exports as `ga_session_id`.

---

## Event Schema (v2)

All events: `snake_case`, max 40 characters, guaranteed by implementation review.

| Event Name | Trigger | `screen_name` | Custom Params | Notes |
|---|---|---|---|---|
| `onboarding_started` | AuthScreen shown | `auth_screen` | — | Fired once per new install flow |
| `onboarding_completed` | ProfileSetupScreen complete | `profile_setup_screen` | `research_focus` | Marks funnel conversion |
| `login_success` | Email/Google sign-in OK | `auth_screen` | `method: email\|google` | Followed immediately by `identify(uid)` |
| `daily_feed_opened` | FeedScreen loaded with user | `feed_screen` | `author_id` | Logged per session open |
| `paper_saved` | Save paper tapped | `feed_screen` or `library_screen` | `paper_id` | screen_name disambiguates call site |
| `quest_completed` | Quest marked complete | `logic_engine_screen` | `quest_id` | Core retention conversion event |
| `roadmap_opened` | Roadmap screen shown | `industry_screen` | `author_id` | |
| `agent_chat_started` | New agent conversation | `agent_screen` | — | |
| `agent_chat_export` | Export triggered | `agent_screen` | `format: csv\|html\|bib\|md` | |
| `discovery_search` | Author searched | `discovery_screen` | — | |
| `collaborator_synergy` | Synergy analysis run | `discovery_screen` | — | |
| `citation_heatmap_opened` | Citation heatmap loaded | `discovery_screen` | `author_id` | |
| `profile_screen_opened` | Profile tab opened | `profile_screen` | — | |

---

## Retention Tracker — Local Events (UserActivityTracker)

`UserActivityTracker` records behavioral events **locally** (never transmitted to a third-party analytics service). Events are stored in `activity_buffer.json` in app internal storage and used to derive `UserMemoryProfile` for AI personalization.

### Privacy Design
- **No PII fields:** `ActivityEvent` contains only `paperTitle`, `paperDomain`, `paperJournal`, `query` (search terms), `authorName`, `authorInstitution`, `collaboratorName`. No email, phone, or device identifiers.
- **Max buffer:** 500 events (trimmed automatically).
- **Cleared on logout:** `clearMemory()` is available for GDPR right-to-erasure flows.

### Event Types
`SESSION_START`, `PAPER_OPENED`, `PAPER_CLOSED`, `PAPER_SAVED`, `AUTHOR_VISITED`, `SEARCH_QUERY`, `AGENT_QUERY`, `FEED_FILTER`, `COLLAB_CONNECTED`

---

## Funnel Coverage (Pillar 2)

| Funnel Step | Event | Location |
|---|---|---|
| 1. App opened | Firebase auto_session_start | Firebase SDK automatic |
| 2. Onboarding started | `onboarding_started` | `AuthScreen.kt:59` |
| 3. Login success | `login_success` | `AuthScreen.kt:107,240,381` |
| 4. Onboarding completed | `onboarding_completed` | `ProfileSetupScreen.kt:403` |
| 5. Daily feed opened | `daily_feed_opened` | `FeedScreen.kt:169` |
| 6. Quest completed | `quest_completed` | `LogicEngineScreen.kt:242` |

---

## Known Gaps / Remaining Risks

| Gap | Severity | Notes |
|---|---|---|
| **No UTM / deep link attribution** | Medium | No marketing campaign tracking. Firebase Dynamic Links or App Links with UTM params needed for campaign ROI measurement. |
| **No analytics validation tests** | Medium | No automated test verifying event payloads. Firebase Debug View must be used manually. See Checklist Pillar 3. |
| **No Amplitude/Mixpanel dashboard** | Low | Firebase Analytics is the single tool. Checklist reference to "Amplitude or Mixpanel dashboard" is N/A — Firebase serves the same purpose. |
| **`paper_saved` UI duplicate risk** | Low | `FeedScreen.kt` calls `logPaperSaved` directly AND `LibraryViewModel.toggleSaved()` calls it. These cover different user-initiated paths — the `screen_name` field now distinguishes them. |

---

## Firebase Dashboard Verification

To verify analytics are reaching Firebase:
1. Enable **Debug View** on a development device:
   ```bash
   adb shell setprop debug.firebase.analytics.app com.company.skolab
   ```
2. Open Firebase Console → Analytics → Debug View.
3. Interact with the app: sign in → feed → save a paper → complete a quest.
4. Confirm events appear in Debug View within ~1 minute.
