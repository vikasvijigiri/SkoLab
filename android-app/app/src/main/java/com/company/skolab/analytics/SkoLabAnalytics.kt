package com.company.skolab.analytics

import android.content.Context
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics

/**
 * SkoLabAnalytics — centralized Firebase Analytics event tracker.
 *
 * All product-critical user actions are logged through this singleton.
 * Events follow the naming convention: snake_case, max 40 characters.
 *
 * Usage:
 *   SkoLabAnalytics.init(applicationContext)
 *   SkoLabAnalytics.identify(uid)             // call once after login
 *   SkoLabAnalytics.logFeedOpened(authorId = "A123")
 *
 * PRODUCT EVENT SCHEMA (v2)
 * ──────────────────────────────────────────────────────────────────────
 * Event                   | Trigger                       | Params
 * ──────────────────────────────────────────────────────────────────────
 * onboarding_started      | AuthScreen shown              | screen_name
 * onboarding_completed    | ProfileSetupScreen complete   | screen_name, research_focus
 * login_success           | Email/Google sign-in OK       | screen_name, method: email|google
 * daily_feed_opened       | FeedScreen loaded             | screen_name, author_id
 * paper_saved             | Save paper tapped             | screen_name, paper_id
 * quest_completed         | Quest marked complete         | screen_name, quest_id
 * roadmap_opened          | Roadmap screen shown          | screen_name, author_id
 * agent_chat_started      | New agent conversation        | screen_name
 * agent_chat_export       | Export triggered from chat    | screen_name, format: csv|html|bib|md
 * discovery_search        | Author searched               | screen_name
 * collaborator_synergy    | Synergy analysis run          | screen_name
 * citation_heatmap_opened | Citation heatmap loaded       | screen_name, author_id
 * profile_screen_opened   | Profile tab opened            | screen_name
 * ──────────────────────────────────────────────────────────────────────
 *
 * Note: Firebase automatically assigns `user_id` once [identify] is called.
 * Firebase also auto-captures `session_id` per session (visible in BigQuery export).
 * `screen_name` is attached to every event as a custom param for funnel analysis.
 */
object SkoLabAnalytics {

    private var analytics: FirebaseAnalytics? = null
    private var isConsentGiven = false

    /** Must be called once in [com.company.skolab.SkoLabApplication.onCreate]. */
    fun init(context: Context) {
        analytics = FirebaseAnalytics.getInstance(context.applicationContext)
        // Default to false prior to explicit consent validation
        analytics?.setAnalyticsCollectionEnabled(false)
    }

    /**
     * Set the Firebase Analytics user identity.
     * Must be called once after successful login so all subsequent events
     * are attributed to the correct user in Firebase Console and BigQuery.
     * NOTE: Pass a hashed or opaque user ID — never an email or PII directly.
     */
    fun identify(userId: String) {
        analytics?.setUserId(userId.take(256).ifBlank { null })
    }

    /** Clear user identity on logout to prevent cross-session attribution. */
    fun clearIdentity() {
        analytics?.setUserId(null)
    }

    fun setConsent(hasConsented: Boolean) {
        isConsentGiven = hasConsented
        analytics?.setAnalyticsCollectionEnabled(hasConsented)
    }

    // ── Onboarding ─────────────────────────────────────────────────────────────

    fun logOnboardingStarted() {
        log("onboarding_started", screen = "auth_screen")
    }

    fun logOnboardingCompleted(researchFocus: String) {
        log("onboarding_completed", screen = "profile_setup_screen") {
            putString("research_focus", researchFocus.take(36))
        }
    }

    fun logLoginSuccess(method: String) {
        // method: "email" | "google"
        log("login_success", screen = "auth_screen") {
            putString("method", method)
        }
    }

    // ── Daily Feed ─────────────────────────────────────────────────────────────

    fun logDailyFeedOpened(authorId: String) {
        log("daily_feed_opened", screen = "feed_screen") {
            putString("author_id", authorId.take(36))
        }
    }

    fun logPaperSaved(paperId: String, screenName: String = "feed_screen") {
        log("paper_saved", screen = screenName) {
            putString("paper_id", paperId.take(36))
        }
    }

    // ── Quests ─────────────────────────────────────────────────────────────────

    fun logQuestCompleted(questId: String) {
        log("quest_completed", screen = "logic_engine_screen") {
            putString("quest_id", questId.take(36))
        }
    }

    // ── Roadmap ────────────────────────────────────────────────────────────────

    fun logRoadmapOpened(authorId: String) {
        log("roadmap_opened", screen = "industry_screen") {
            putString("author_id", authorId.take(36))
        }
    }

    // ── Agent Chat ─────────────────────────────────────────────────────────────

    fun logAgentChatStarted() {
        log("agent_chat_started", screen = "agent_screen")
    }

    fun logAgentChatExport(format: String) {
        // format: "csv" | "html" | "bib" | "md"
        log("agent_chat_export", screen = "agent_screen") {
            putString("format", format)
        }
    }

    // ── Discovery ──────────────────────────────────────────────────────────────

    fun logDiscoverySearch() {
        log("discovery_search", screen = "discovery_screen")
    }

    // ── Networking ─────────────────────────────────────────────────────────────

    fun logCollaboratorSynergy() {
        log("collaborator_synergy", screen = "discovery_screen")
    }

    fun logCitationHeatmapOpened(authorId: String) {
        log("citation_heatmap_opened", screen = "discovery_screen") {
            putString("author_id", authorId.take(36))
        }
    }

    // ── Profile ────────────────────────────────────────────────────────────────

    fun logProfileScreenOpened() {
        log("profile_screen_opened", screen = "profile_screen")
    }

    // ── Internal helper ────────────────────────────────────────────────────────

    /**
     * Core log helper. Attaches `screen_name` to every event bundle so every
     * event can be segmented by origin screen in Firebase Analytics dashboards.
     * Guards against unauthenticated/unconsented logging.
     */
    private fun log(event: String, screen: String, params: (Bundle.() -> Unit)? = null) {
        if (!isConsentGiven) return
        val bundle = Bundle().apply {
            putString("screen_name", screen)
            params?.invoke(this)
        }
        analytics?.logEvent(event, bundle)
        try {
            com.google.firebase.crashlytics.FirebaseCrashlytics.getInstance()
                .log("Analytics[$screen] Event: $event")
        } catch (_: Exception) {
            // Safe fallback if Crashlytics is not initialized
        }
    }
}
