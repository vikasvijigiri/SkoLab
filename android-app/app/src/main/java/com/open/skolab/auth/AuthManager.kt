package com.open.skolab.auth

import android.content.Context
import android.util.Log
import androidx.credentials.Credential
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.open.skolab.BuildConfig
import com.open.skolab.R
import com.open.skolab.model.SkoLabUser
import com.open.skolab.model.UserConnection
import kotlinx.coroutines.tasks.await

import com.open.skolab.data.UserPreferences
import kotlinx.coroutines.flow.firstOrNull

class AuthManager(private val context: Context) {
    private val auth = Firebase.auth
    private val db = Firebase.firestore
    private val credentialManager = CredentialManager.create(context)
    private val userPrefs = UserPreferences(context)

    val currentUser: FirebaseUser?
        get() = auth.currentUser

    val isSignedIn: Boolean
        get() = currentUser != null

    val cachedUser = userPrefs.cachedUser

    private fun resolveWebClientId(): String? {
        val fromBuild = BuildConfig.GOOGLE_WEB_CLIENT_ID.trim()
        if (fromBuild.isNotEmpty() && !fromBuild.contains("xxxx")) {
            return fromBuild
        }
        val fromResources = context.getString(R.string.default_web_client_id).trim()
        if (fromResources.isNotEmpty() && !fromResources.contains("YOUR_WEB_CLIENT")) {
            return fromResources
        }
        return null
    }

    suspend fun initiateGoogleSignIn(): Result<FirebaseUser> {
        val webClientId = resolveWebClientId()
            ?: return Result.failure(
                IllegalStateException(
                    "Google Sign-In is not configured. Add GOOGLE_WEB_CLIENT_ID to android-app/local.properties " +
                        "(see local.properties.example) or set default_web_client_id in strings.xml."
                )
            )

        // First try: filter by authorized accounts (fast, silent)
        // If that finds nothing, fall back to showing the full account picker
        return try {
            val result = tryGetCredential(webClientId, filterByAuthorized = true)
            handleSignInResult(result.credential)
        } catch (e: NoCredentialException) {
            // No previously authorized account — show full account picker
            Log.d("AuthManager", "No authorized account found, showing full picker")
            try {
                val result = tryGetCredential(webClientId, filterByAuthorized = false)
                handleSignInResult(result.credential)
            } catch (e2: GetCredentialCancellationException) {
                Log.i("AuthManager", "User cancelled sign-in")
                Result.failure(Exception("Sign-in cancelled"))
            } catch (e2: Exception) {
                Log.e("AuthManager", "Google Sign-In flow failed", e2)
                Result.failure(friendlyError(e2))
            }
        } catch (e: GetCredentialCancellationException) {
            Log.i("AuthManager", "User cancelled sign-in")
            Result.failure(Exception("Sign-in cancelled"))
        } catch (e: Exception) {
            Log.e("AuthManager", "Google Sign-In flow failed", e)
            Result.failure(friendlyError(e))
        }
    }

    private suspend fun tryGetCredential(
        webClientId: String,
        filterByAuthorized: Boolean
    ): androidx.credentials.GetCredentialResponse {
        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(filterByAuthorized)
            .setServerClientId(webClientId)
            .setAutoSelectEnabled(false)   // always show picker — avoids silent failures
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        return credentialManager.getCredential(context = context, request = request)
    }

    private fun friendlyError(e: Exception): Exception {
        val msg = e.message ?: ""
        return when {
            msg.contains("DEVELOPER_ERROR") ->
                Exception("Auth configuration error. Check SHA-1 fingerprint in Firebase console.")
            msg.contains("network") || msg.contains("offline") ->
                Exception("No internet connection. Please check your network and try again.")
            msg.contains("cancel") ->
                Exception("Sign-in cancelled")
            else -> e
        }
    }

    private suspend fun handleSignInResult(credential: Credential): Result<FirebaseUser> {
        return try {
            when (credential.type) {
                GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL -> {
                    val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                    signInWithGoogle(googleIdTokenCredential.idToken)
                }
                else -> Result.failure(Exception("Unsupported credential type: ${credential.type}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun signInWithGoogle(idToken: String): Result<FirebaseUser> {
        return try {
            val credential = GoogleAuthProvider.getCredential(idToken, null)
            val result = auth.signInWithCredential(credential).await()
            val user = result.user
            if (user != null) {
                // Save to Firestore in background — don't block or fail sign-in if offline
                saveUserToFirestore(user)
                Result.success(user)
            } else {
                Result.failure(Exception("Sign-in completed but user is null"))
            }
        } catch (e: Exception) {
            Log.e("AuthManager", "Firebase credential sign-in failed", e)
            Result.failure(e)
        }
    }

    private suspend fun saveUserToFirestore(user: FirebaseUser) {
        try {
            // Try cache first — avoids offline error on initial sign-in with no network
            val userDoc = try {
                db.collection("researchers").document(user.uid).get(Source.CACHE).await()
            } catch (cacheEx: Exception) {
                // Cache miss — attempt server fetch
                db.collection("researchers").document(user.uid).get(Source.SERVER).await()
            }

            val userData = if (userDoc.exists()) {
                userDoc.toObject(SkoLabUser::class.java)
            } else {
                val newUserData = SkoLabUser(
                    uid = user.uid,
                    name = user.displayName ?: "",
                    email = user.email ?: "",
                    researchFocus = ""
                )
                // Use set() with merge — queued offline and synced when reconnected
                db.collection("researchers").document(user.uid).set(newUserData).await()
                newUserData
            }
            if (userData != null) {
                userPrefs.cacheUser(userData)
            }
        } catch (e: Exception) {
            // Firestore offline — not fatal. Cache basic user info from Firebase Auth instead
            Log.w("AuthManager", "Firestore unavailable during sign-in, using Auth data as fallback", e)
            val fallback = SkoLabUser(
                uid = user.uid,
                name = user.displayName ?: "",
                email = user.email ?: "",
                researchFocus = ""
            )
            userPrefs.cacheUser(fallback)
        }
    }

    suspend fun getUserData(uid: String): SkoLabUser? {
        // Return cached data immediately if available — avoids Firestore offline error in UI
        val cached = userPrefs.cachedUser.firstOrNull()
        if (cached != null && cached.uid == uid) return cached

        return try {
            // Try cache first, then server
            val doc = try {
                db.collection("researchers").document(uid).get(Source.CACHE).await()
            } catch (cacheEx: Exception) {
                db.collection("researchers").document(uid).get(Source.SERVER).await()
            }
            val userData = doc.toObject(SkoLabUser::class.java)
            if (userData != null) {
                userPrefs.cacheUser(userData)
            }
            userData
        } catch (e: Exception) {
            Log.w("AuthManager", "Error getting user data (offline?), returning cached if available", e)
            userPrefs.cachedUser.firstOrNull() // return local cache rather than null
        }
    }

    suspend fun updateUserResearchFocus(focus: String) {
        val user = currentUser ?: return
        try {
            // Update Firestore
            db.collection("researchers").document(user.uid).update("researchFocus", focus).await()
            // Update local cache
            val cached = userPrefs.cachedUser.firstOrNull()
            if (cached != null) {
                userPrefs.cacheUser(cached.copy(researchFocus = focus))
            }
        } catch (e: Exception) {
            Log.w("AuthManager", "Failed to update research focus", e)
        }
    }

    suspend fun addConnectionToFirestore(connection: UserConnection) {
        val user = currentUser ?: return
        try {
            val cleanId = connection.id.substringAfterLast("/")
            db.collection("researchers")
                .document(user.uid)
                .collection("connections")
                .document(cleanId)
                .set(connection)
                .await()
        } catch (e: Exception) {
            Log.w("AuthManager", "Failed to sync connection to Firestore, queuing offline", e)
        }
    }

    suspend fun removeConnectionFromFirestore(connectionId: String) {
        val user = currentUser ?: return
        try {
            val cleanId = connectionId.substringAfterLast("/")
            db.collection("researchers")
                .document(user.uid)
                .collection("connections")
                .document(cleanId)
                .delete()
                .await()
        } catch (e: Exception) {
            Log.w("AuthManager", "Failed to sync connection deletion to Firestore", e)
        }
    }

    suspend fun signOut() {
        auth.signOut()
        userPrefs.clearCachedUser()
    }
}
