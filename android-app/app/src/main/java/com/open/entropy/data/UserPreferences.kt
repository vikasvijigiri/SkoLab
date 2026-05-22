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
                complexityScore = prefs[userComplexityScoreKey] ?: 0f
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
        }
    }
}

