package com.open.skolab.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.open.skolab.model.SkoLabUser
import com.open.skolab.model.UserConnection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "skolab_prefs")

class UserPreferences(private val context: Context) {
    private val hasSeenOnboardingKey = booleanPreferencesKey("has_seen_onboarding")
    private val userUidKey = stringPreferencesKey("user_uid")
    private val userNameKey = stringPreferencesKey("user_name")
    private val userEmailKey = stringPreferencesKey("user_email")
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

    val isGuestSignedIn: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[guestSignedInKey] ?: false
    }

    suspend fun setGuestSignedIn(signedIn: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[guestSignedInKey] = signedIn
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
        val uid = prefs[userUidKey]
        if (uid != null) {
            SkoLabUser(
                uid = uid,
                name = prefs[userNameKey] ?: "",
                email = prefs[userEmailKey] ?: "",
                researchFocus = prefs[userResearchFocusKey] ?: "",
                complexityScore = prefs[userComplexityScoreKey] ?: 0f,
                savedPapers = parseSavedPaperIds(prefs[savedPaperIdsKey]),
                academicStatus = prefs[userAcademicStatusKey] ?: "Researcher",
                cvUri = prefs[userCvUriKey] ?: "",
                cvFileName = prefs[userCvFileNameKey] ?: "",
                about = prefs[userAboutKey] ?: ""
            )
        } else {
            null
        }
    }

    suspend fun cacheUser(user: SkoLabUser) {
        context.dataStore.edit { prefs ->
            prefs[userUidKey] = user.uid
            prefs[userNameKey] = user.name
            prefs[userEmailKey] = user.email
            prefs[userResearchFocusKey] = user.researchFocus
            prefs[userComplexityScoreKey] = user.complexityScore
            prefs[userAcademicStatusKey] = user.academicStatus
            prefs[userCvUriKey] = user.cvUri
            prefs[userCvFileNameKey] = user.cvFileName
            prefs[userAboutKey] = user.about
        }
    }

    suspend fun clearCachedUser() {
        context.dataStore.edit { prefs ->
            prefs.remove(userUidKey)
            prefs.remove(userNameKey)
            prefs.remove(userEmailKey)
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
