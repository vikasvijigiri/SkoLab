package com.open.entropy.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.open.entropy.model.ResQitUser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "resqit_prefs")

class UserPreferences(private val context: Context) {
    private val hasSeenOnboardingKey = booleanPreferencesKey("has_seen_onboarding")
    private val userUidKey = stringPreferencesKey("user_uid")
    private val userNameKey = stringPreferencesKey("user_name")
    private val userEmailKey = stringPreferencesKey("user_email")
    private val userResearchFocusKey = stringPreferencesKey("user_research_focus")
    private val userComplexityScoreKey = floatPreferencesKey("user_complexity_score")
    // Saved paper OpenAlex IDs stored as a comma-separated string
    private val savedPaperIdsKey = stringPreferencesKey("saved_paper_ids")

    val hasSeenOnboarding: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[hasSeenOnboardingKey] ?: false
    }

    suspend fun setOnboardingComplete() {
        context.dataStore.edit { prefs ->
            prefs[hasSeenOnboardingKey] = true
        }
    }

    val cachedUser: Flow<ResQitUser?> = context.dataStore.data.map { prefs ->
        val uid = prefs[userUidKey]
        if (uid != null) {
            ResQitUser(
                uid = uid,
                name = prefs[userNameKey] ?: "",
                email = prefs[userEmailKey] ?: "",
                researchFocus = prefs[userResearchFocusKey] ?: "General Physics",
                complexityScore = prefs[userComplexityScoreKey] ?: 0f,
                savedPapers = parseSavedPaperIds(prefs[savedPaperIdsKey])
            )
        } else {
            null
        }
    }

    suspend fun cacheUser(user: ResQitUser) {
        context.dataStore.edit { prefs ->
            prefs[userUidKey] = user.uid
            prefs[userNameKey] = user.name
            prefs[userEmailKey] = user.email
            prefs[userResearchFocusKey] = user.researchFocus
            prefs[userComplexityScoreKey] = user.complexityScore
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
}
