package com.company.skolab.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.company.skolab.model.SkoLabUser
import com.company.skolab.model.UserConnection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "skolab_prefs")

class UserPreferences(private val context: Context) {
    private val encryptedPrefs = EncryptedPreferences(context)
    private val hasSeenOnboardingKey = booleanPreferencesKey("has_seen_onboarding")
    private val userNameKey = stringPreferencesKey("user_name")
    private val userResearchFocusKey = stringPreferencesKey("user_research_focus")
    private val userComplexityScoreKey = floatPreferencesKey("user_complexity_score")
    // Saved paper OpenAlex IDs stored as a comma-separated string
    private val savedPaperIdsKey = stringPreferencesKey("saved_paper_ids")
    private val connectionsKey = stringPreferencesKey("user_connections_json")
    private val streakCountKey = intPreferencesKey("streak_count")
    private val lastCheckedInDateKey = stringPreferencesKey("last_checked_in_date")
    private val subscriptionTypeKey = stringPreferencesKey("subscription_type")
    private val guestSignedInKey = booleanPreferencesKey("is_guest_signed_in")
    private val userAcademicStatusKey = stringPreferencesKey("user_academic_status")
    private val userCvUriKey = stringPreferencesKey("user_cv_uri")
    private val userCvFileNameKey = stringPreferencesKey("user_cv_file_name")
    private val userAboutKey = stringPreferencesKey("user_about")
    private val isConsentGivenKey = booleanPreferencesKey("is_consent_given")
    private val pushNotificationsEnabledKey = booleanPreferencesKey("push_notifications_enabled")
    private val appThemeKey = stringPreferencesKey("app_theme")
    private val dynamicColorEnabledKey = booleanPreferencesKey("dynamic_color_enabled")
    private val sessionCountKey = intPreferencesKey("session_count")
    private val npsShownKey = booleanPreferencesKey("nps_shown")

    val appTheme: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[appThemeKey] ?: "SYSTEM"
    }

    suspend fun setAppTheme(theme: String) {
        context.dataStore.edit { prefs ->
            prefs[appThemeKey] = theme
        }
    }

    val dynamicColorEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[dynamicColorEnabledKey] ?: false
    }

    suspend fun setDynamicColorEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[dynamicColorEnabledKey] = enabled
        }
    }

    /** Increments the persistent session count on every app cold start. */
    suspend fun incrementSessionCount() {
        context.dataStore.edit { prefs ->
            prefs[sessionCountKey] = (prefs[sessionCountKey] ?: 0) + 1
        }
    }

    val sessionCount: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[sessionCountKey] ?: 0
    }

    val npsShown: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[npsShownKey] ?: false
    }

    suspend fun setNpsShown(shown: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[npsShownKey] = shown
        }
    }

    val pushNotificationsEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[pushNotificationsEnabledKey] ?: false
    }

    suspend fun setPushNotificationsEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[pushNotificationsEnabledKey] = enabled
        }
    }

    val isGuestSignedIn: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[guestSignedInKey] ?: false
    }

    suspend fun setGuestSignedIn(signedIn: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[guestSignedInKey] = signedIn
        }
    }

    val isConsentGiven: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[isConsentGivenKey] ?: false
    }

    suspend fun setConsentGiven(given: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[isConsentGivenKey] = given
        }
    }

    val subscriptionType: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[subscriptionTypeKey] ?: "Basic"
    }

    suspend fun setSubscriptionType(type: String) {
        context.dataStore.edit { prefs ->
            prefs[subscriptionTypeKey] = type
        }
    }

    val streakCount: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[streakCountKey] ?: 5
    }

    val lastCheckedInDate: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[lastCheckedInDateKey]
    }

    suspend fun incrementStreakAndCheckIn() {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        context.dataStore.edit { prefs ->
            val lastCheckIn = prefs[lastCheckedInDateKey]
            if (lastCheckIn != today) {
                val currentStreak = prefs[streakCountKey] ?: 5
                prefs[streakCountKey] = currentStreak + 1
                prefs[lastCheckedInDateKey] = today
            }
        }
    }

    val hasSeenOnboarding: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[hasSeenOnboardingKey] ?: false
    }

    suspend fun setOnboardingComplete() {
        context.dataStore.edit { prefs ->
            prefs[hasSeenOnboardingKey] = true
        }
    }

    val cachedUser: Flow<SkoLabUser?> = context.dataStore.data.map { prefs ->
        val uid = encryptedPrefs.getString("user_uid")
        if (uid != null) {
            // Read from EncryptedPreferences
            var name = encryptedPrefs.getString("user_name")
            var researchFocus = encryptedPrefs.getString("user_research_focus")
            var about = encryptedPrefs.getString("user_about")

            // Backward compatibility: migrate plaintext values if they exist
            val oldName = prefs[userNameKey]
            val oldFocus = prefs[userResearchFocusKey]
            val oldAbout = prefs[userAboutKey]

            if (name == null && oldName != null) {
                name = oldName
                encryptedPrefs.putString("user_name", oldName)
            }
            if (researchFocus == null && oldFocus != null) {
                researchFocus = oldFocus
                encryptedPrefs.putString("user_research_focus", oldFocus)
            }
            if (about == null && oldAbout != null) {
                about = oldAbout
                encryptedPrefs.putString("user_about", oldAbout)
            }

            SkoLabUser(
                uid = uid,
                name = name ?: "",
                email = encryptedPrefs.getString("user_email") ?: "",
                researchFocus = researchFocus ?: "",
                complexityScore = prefs[userComplexityScoreKey] ?: 0f,
                savedPapers = parseSavedPaperIds(prefs[savedPaperIdsKey]),
                academicStatus = prefs[userAcademicStatusKey] ?: "Researcher",
                cvUri = prefs[userCvUriKey] ?: "",
                cvFileName = prefs[userCvFileNameKey] ?: "",
                about = about ?: ""
            )
        } else {
            null
        }
    }

    suspend fun cacheUser(user: SkoLabUser) {
        encryptedPrefs.putString("user_uid", user.uid)
        encryptedPrefs.putString("user_email", user.email)
        encryptedPrefs.putString("user_name", user.name)
        encryptedPrefs.putString("user_research_focus", user.researchFocus)
        encryptedPrefs.putString("user_about", user.about)
        context.dataStore.edit { prefs ->
            // Clear unencrypted duplicates if any exist
            if (prefs.contains(userNameKey)) prefs.remove(userNameKey)
            if (prefs.contains(userResearchFocusKey)) prefs.remove(userResearchFocusKey)
            if (prefs.contains(userAboutKey)) prefs.remove(userAboutKey)

            prefs[userComplexityScoreKey] = user.complexityScore
            prefs[userAcademicStatusKey] = user.academicStatus
            prefs[userCvUriKey] = user.cvUri
            prefs[userCvFileNameKey] = user.cvFileName
        }
    }

    suspend fun clearCachedUser() {
        encryptedPrefs.remove("user_uid")
        encryptedPrefs.remove("user_email")
        encryptedPrefs.remove("user_name")
        encryptedPrefs.remove("user_research_focus")
        encryptedPrefs.remove("user_about")
        context.dataStore.edit { prefs ->
            prefs.remove(userNameKey)
            prefs.remove(userResearchFocusKey)
            prefs.remove(userComplexityScoreKey)
            prefs.remove(savedPaperIdsKey)
            prefs.remove(guestSignedInKey)
            prefs.remove(userAcademicStatusKey)
            prefs.remove(userCvUriKey)
            prefs.remove(userCvFileNameKey)
            prefs.remove(userAboutKey)
        }
    }

    /** Flow of saved OpenAlex paper IDs (persisted locally). */
    val savedPaperIds: Flow<List<String>> = context.dataStore.data.map { prefs ->
        parseSavedPaperIds(prefs[savedPaperIdsKey])
    }

    /**
     * Toggles a paper ID in the saved set.
     * @return true if it was ADDED, false if it was REMOVED.
     */
    suspend fun toggleSavedPaper(openAlexId: String): Boolean {
        var added = false
        context.dataStore.edit { prefs ->
            val current = parseSavedPaperIds(prefs[savedPaperIdsKey]).toMutableList()
            if (current.contains(openAlexId)) {
                current.remove(openAlexId)
                added = false
            } else {
                current.add(openAlexId)
                added = true
            }
            prefs[savedPaperIdsKey] = current.joinToString(",")
        }
        return added
    }

    private fun parseSavedPaperIds(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(",").filter { it.isNotBlank() }
    }

    /** Flow of connected user researchers (persisted locally). */
    val userConnections: Flow<List<UserConnection>> = context.dataStore.data.map { prefs ->
        parseConnections(prefs[connectionsKey])
    }

    suspend fun addConnection(connection: UserConnection) {
        context.dataStore.edit { prefs ->
            val current = parseConnections(prefs[connectionsKey]).toMutableList()
            if (!current.any { it.id == connection.id }) {
                current.add(connection)
                prefs[connectionsKey] = kotlinx.serialization.json.Json.encodeToString(current)
            }
        }
    }

    suspend fun removeConnection(connectionId: String) {
        context.dataStore.edit { prefs ->
            val current = parseConnections(prefs[connectionsKey]).toMutableList()
            current.removeAll { it.id == connectionId }
            prefs[connectionsKey] = kotlinx.serialization.json.Json.encodeToString(current)
        }
    }

    private fun parseConnections(raw: String?): List<UserConnection> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            kotlinx.serialization.json.Json.decodeFromString(raw)
        } catch (e: Exception) {
            emptyList()
        }
    }
}
